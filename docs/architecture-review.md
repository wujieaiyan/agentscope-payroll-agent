# 架构复审（资深架构师 + 产品经理视角）

- 复审日期：2026-11-22
- 代码基线：`feature/dev-team` @ `4ca9382`（批次 1–6 已落地）
- 复审范围：模块划分、运行时形态、产品边界、质量工程
- **关键决策（2026-11-22，D1 已拍板）：产品主线为「Agent 平台 + 薪酬运营」双主线**，落地解释见 §6，工作流见 `docs/architecture-remediation-spec.md` §3.1 / M8
- 结论摘要：工程纪律明显高于同类 AI 应用，但已越过模块化单体可承受的临界点。下一阶段优先「收口边界、按双主线分层、补齐质量门」，而非继续加功能。

---

## 0. 结论先行

| 等级 | 问题 | 判据 | 建议 |
| --- | --- | --- | --- |
| P0-1 | 共享内核实为迷你平台 | `payroll-common` 48.8k 行 / 316 文件，占主代码 55%，web 与 runtime 共同依赖 | 先 ArchUnit 固化依赖 + 包内分层，不急于拆 Maven 模块 |
| P0-2 | 信息架构未按双主线分层 | 定位已定为双主线，但 15 个管理页签仍平铺在 3 个侧栏分组；路由无工作区归属；`devteam` 45+ 文件零 REST/UI | 按平台/薪酬双工作台重组 IA（M8）；`devteam` 降为场景包 |
| P0-3 | Nacos 当配置数据库 | agents/flows/skills/models/settings 均为 JSON 整文档 + 自研 revision | 中期把定义元数据落关系库，Nacos 退化为推送通道 |
| P0-4 | 无质量门 | 无 CI、无性能基线、无依赖/secret 扫描 | 本周补 GitHub Actions，成本一天（已完成：`.github/workflows/ci.yml` 三 job + 测试标签，后端快速档本地 51.6s/1216 例绿） |
| P1-1 | 组合爆炸 | 双库 × 双缓存 × 双检索 × 六种路由路径 | 定义「生产唯一形态」，profile 强制组合 |
| P1-2 | 控制平面大泥球 | `PayrollChatService` 2991 行 / 70 import，单 `run()` 叠加六层策略 | 抽 `RouteResolver` 纯函数 |
| P1-3 | 数据面反向依赖控制面 | runtime 的 `kb_search` 同步调 Web `/internal/kb`（8s） | 只读检索下沉 |
| P1-4 | 模型连接非一等资源 | 故障切换仅连接级 5s、单次切换 | 加半开探测、按连接熔断与成本归因 |
| P1-5 | 有指标无 SLO | Micrometer 指标齐备，无 SLI/告警规则 | 定 5 个 SLI 并落告警 |

---

## 1. 现状画像（数字优先）

| 指标 | 数值 | 解读 |
| --- | --- | --- |
| Maven 模块 | 6（protocol / common / agent / backend / sandbox / tests） | `payroll-protocol` 仅 3 文件 / 411 行，近乎空转 |
| 主代码 | ≈88k 行 / 470 文件 | 中等规模单体 |
| 测试代码 | ≈40k 行 / 1246 个用例 | 测试:主代码 ≈ 1:2.2，显著高于行业均值 |
| `payroll-common` | **48.8k 行 / 316 文件** | 包分布：domain 66、service 49、devteam 45、agent 30、skill 27、db 20 |
| HTTP 映射 | 184 个（含类级 `@RequestMapping`）/ 29 个 Controller | 管理面极宽 |
| 管理台页面 | 15 个 admin view + 对话页 | 横跨薪酬运营、知识库、Agent 平台、研发团队 |
| 配置开关 | 24 个 `enabled()`，239 处 fallback 相关代码 | 降级组合矩阵巨大 |
| 数据库迁移 | MySQL Flyway V1–V20，另有 H2 内联 DDL | 双方言长期并存 |
| CI | **无** `.github/workflows` | 最大工程缺口 |

进程形态本身合理：web（控制面：REST / 知识库 / 管理）+ runtime（数据面：模型执行）+ sandbox（执行隔离）。问题不在「拆没拆」，而在**拆完之后共享层长成了第二个应用**。

依赖事实：`payroll-common` 直接依赖 `spring-boot-starter-web`、`agentscope-core/harness`、ES client、JDBC/MySQL/H2/Flyway/Hikari、Redis、Actuator、tree-sitter、POI/PDF、JGit；`payroll-agent` 与 `payroll-backend` 都只依赖 `payroll-common`。任何共享层改动都会双端重建、双端回归。

---

## 2. 做得对、应当保留的

1. **确定性计算与模型彻底隔离**。金额只来自 `PayrollEngine`，模型负责「听懂 / 解释」。这是 HR 薪酬场景唯一正确的底座，价值高于任何编排技巧。
2. **轮次生命周期达到平台级**：`chat_turns` 租约、围栏令牌、事件总线、SSE 续流、冷恢复、幂等终态。多数团队要到线上第一次「刷新丢答案」才会补这一层。
3. **能力声明驱动工具注册**：`AgentDefinition` 的 `kb:read / kb:config / calc / video`，工具按能力挂载，天然最小权限。比「提示词里写别乱用」高一个维度。
4. **失败可见的降级**：嵌入熔断、模型首包切换、事件总线降级、Nacos 读失败保留上次快照——降级同时留指标与日志，方向正确。
5. **写确认 + 审批 + 审计**三件套，是薪酬场景刚需而非锦上添花。
6. **文档先行**：40+ 份规格 / PRD 与代码同步演进，加固项逐条有落地说明与单测。

---

## 3. P0：结构性风险

### P0-1 共享内核实为「迷你平台」

`payroll-common` 同时承担领域模型、会话服务、技能注册、研发团队、模型连接、用量统计、健康检查。它不再是公共层，而是平台的第二个应用，只是没有独立进程和独立构建。

后果：

- 模块边界名存实亡，任何改动双端回归；
- 测试无法按模块归属，40k 行测试集中在 `payroll-tests`；
- 新成员无法从模块图理解系统，只能读 48.8k 行代码。

建议（短期不拆 Maven 模块，成本高收益慢）：

1. 引入 ArchUnit 固化规则：`domain` 不得依赖 `service/web`；KB 不得反向依赖 chat；`db` 只能被 `store/repository` 使用；
2. 包内分层收口为 `api / service / store`，禁止 `service` 包继续做垃圾桶；
3. 为 `payroll-common` 画出「对外 API 面」（哪些类允许被 agent/backend 引用），其余标记 internal。

### P0-2 信息架构未按双主线分层

| 页面簇 | 归属工作台 | 现状问题 |
| --- | --- | --- |
| 员工 / 考勤 / 绩效 / 政策 / 批次 / 待办 | 薪酬工作台 | 与平台能力混排，HR 需要跳过编排/模型概念 |
| 知识库 / 智能问答 / 引用溯源 | 平台 + 薪酬共用 | 放在「系统」组，看起来像管理员专属 |
| 智能体 / 流程 / 技能 / 团队 / 模型 / 用量 | 平台工作台 + 平台治理 | 与薪酬业务并列，缺少工作区边界 |
| 研发团队智能体（`devteam` 45+ 文件） | 场景包候选 | **零 REST、零 UI**，仅 JUnit E2E；22 个文件直接依赖薪酬域类型 |

现状是三个手写侧栏分组（协同 / 薪酬 / 系统）、无路由工作区归属（`AppLayout.vue:18-51`、`router/index.js:16-34`），系统组同时混放平台治理（模型 / 用量 / 知识库）与组织治理（用户 / 审计）。

建议：不改页面组件，按「平台工作台 / 薪酬工作台 / 平台治理」重组菜单与路由归属（M8）；`devteam` 先冻结，待场景包契约稳定后按「研发场景包」重启（见 §9 决策 2）。

### P0-3 把 Nacos 当配置数据库用

agents / flows / skills / models / settings 全部是 Nacos 上的 JSON 整文档（`payroll-agents.json`、`payroll-flows.json`、`payroll-models.json` 等），为此自研了 revision 乐观锁、单键写、写前重读、整文档合并保护——本质上是在**替代数据库本应提供的事务与并发控制**。

短期可接受（实现质量高于多数同类项目）。规模上升后必然遭遇：整文档覆盖写冲突、无索引检索、无法迁移与审计回溯、列表翻页截断（曾出现 200 条上限问题）。

建议：中期把「定义」的权威副本落关系库（agents / flows / skills 元数据表 + Flyway），Nacos 只做变更推送。应在技能数量破百之前完成。

### P0-4 没有质量门

- 无 CI：1246 个测试只在本机跑；
- 无性能基线：SSE 并发、轮次吞吐、KB 检索延迟均无数字；
- 无依赖漏洞 / secret 扫描；
- 无契约测试，前后端依赖人工联调。

前文所有架构优点，只要回归靠人手动跑，半年内必然退化。这是投入产出比最高的一项：GitHub Actions 跑「编译 + 全量测试 + 前端 build + 依赖扫描」一天可落地。

---

## 4. P1：演进债

### P1-1 组合爆炸：双库 × 双缓存 × 双检索 × 多编排

- 双库：H2 内联 DDL + MySQL Flyway；
- 双缓存：Redis / 内存；
- 双检索：ES / BM25+标题 / Hash 向量兜底；
- 六种路径：`PIPELINE / GRAPH / AGENT / FLOW / TEAM_ACTIVE / ACTION`。

理论组合 2^N，实际测试只覆盖少数路径。建议明确「生产唯一形态」（MySQL + Redis + ES + Nacos），H2 / 内存 / 降级路径只做测试档；用 Spring profile 强制组合，禁止生产混搭。

### P1-2 控制平面已成「大泥球」

`PayrollChatService` 2991 行、70 个 import。单个 `run()` 内叠加：KB 规则 → 语义分类 → 轻量模型路由 → 流程能力过滤 → B0/B1 执行者保留 → 缓存 → 预取 → 执行。每加一条策略，所有策略的可读性一起下降。

建议：抽出纯函数 `RouteResolver`（输入：问题 / 画像 / KB 判定 / 流程可用性 / 上一轮角色；输出：`RoutePlan{path, agent, reason}`），`run()` 只负责执行与事件。这是可测试性收益最大的一次重构。

### P1-3 数据面反向依赖控制面

runtime 中的 `kb_search` 工具经 `KbSkillGateway` 同步调 Web 的 `/internal/kb`，工具循环最热路径是一次跨进程 8s 超时调用。好处是权限集中，代价是**模型执行可用性被 Web 绑定**。

建议：只读检索下沉（共享存储或独立 KB 服务），Web 只保留写与权限管理。可与 P0-3 合并为「知识库服务化」议题。

### P1-4 模型连接还不是一等资源

批次 6 的故障切换只覆盖「首包 5s 内连接类错误、最多切一次」，缺少半开探测、按连接维度的熔断与配额、恢复回切语义。业务需要的是：连接异常 → 降权 → 探测恢复 → 回切，成本可归因到连接。

### P1-5 有指标，无 SLO

Micrometer 指标已齐备，但无 SLI / SLO / 告警规则。建议先定 5 个：轮次成功率、首字延迟 P95、提交 429 率、KB 检索超时率、模型失败率，落成 Grafana 告警。

---

## 5. P2：可延后但需记录

| 项 | 问题 | 建议 |
| --- | --- | --- |
| 文档漂移 | `README.md` 仍描述「固定三阶段编排」，与当前 AGENT / FLOW / TEAM 不符 | README 只留定位与快速开始，架构单一事实源放 `docs/architecture.md` |
| 包名语义 | `io.agentscope.examples.payroll` 中的 `examples` 是脚手架语义 | 平台化后迁移 |
| 测试单点 | 40k 行测试全在 `payroll-tests` | 按 unit / integration / e2e 打 tag，PR 只跑 unit，夜间跑全量 |
| 前端规模 | 对话页承担路由 chip、引用、断点续传、附件、确认卡片 | 先量化 `ChatView` 行数，再按功能拆分 |
| 协议模块空转 | `payroll-protocol` 仅 411 行 | 合并回 common 或明确其唯一职责（sandbox 协议） |

---

## 6. 产品经理视角：双主线定位（D1 已拍板）

**2026-11-22 决策：产品主线为「Agent 平台 + 薪酬运营」双主线**。平台为骨，薪酬为首个旗舰场景；不是两个产品，而是一套控制台的两个工作台，共享对话入口、权限体系与审计。

| 维度 | Agent 平台主线 | 薪酬运营主线 |
| --- | --- | --- |
| 买家 | 平台管理员、业务线开发者、外部选型人 | HR、财务、薪酬专员、员工 |
| 产品本体 | 智能体 / 流程编排 / 技能 / 模型连接 / 知识库 / 用量与成本 / 审计 | 员工、考勤、绩效、政策、批次、审批、问答与引用 |
| 成功指标 | 智能体与技能复用数、场景包交付周期、单轮成本 | 核算准确率、驳回率、人工替代率、首字延迟 |
| 不做什么 | 不承载具体薪酬规则，不被单一场景绑架 | 不自建编排 / 模型层，全部复用平台能力 |

### 边界规则（进入 M1 的 ArchUnit 规则）

1. **代码分层**：平台包（`skill / model / usage / memory / profile / team / kb / health / support`）不得依赖薪酬业务类型与 `PayrollEngine / PayrollTools / PayrollAgentFactory`。当前基线接近干净——平台包对薪酬域仅导入 4 个异常类型（14 个文件），业务类型零依赖；三件套只被场景层引用。
2. **场景接入**：薪酬是第一个场景包（薪酬流程、技能、智能体），新增场景以同一包契约接入，不修改平台层。`devteam` 是第二个场景包雏形，但 22 个文件直接依赖薪酬域，暂不可移植。
3. **信息架构**：平台工作台（对话 / 智能体 / 流程 / 技能 / 团队 / 知识库）、薪酬工作台（员工 / 考勤 / 绩效 / 政策 / 批次 / 待办）、平台治理（模型 / 用量 / 审计 / 用户 / 偏好）三组。现有侧栏已具备分组能力，缺的是归属、命名与默认落地页。
4. **权限**：已有权限码不重命名（避免角色种子与前端判断大面积改动）；新能力一律带工作台前缀；权限管理页按工作台分组展示。

### 定位不变的部分

价值锚点仍是 **可解释、可审计、可复算 > 聪明**。引用溯源、写确认、审批、成本闸门是双主线共同底座，新场景接入必须继承，不允许绕过。

指标缺口不变：现有「用量看板」只回答「花了多少」，未回答「值不值」。平台主线补「场景包交付周期 / 技能复用率」，薪酬主线补「每轮成本、检索命中率、误答率、写操作驳回率、人工替代率」。

---

## 7. 建议节奏

| 时间 | 动作 | 理由 |
| --- | --- | --- |
| 本周 | 补 GitHub Actions（编译 + 全量测试 + 前端 build + 依赖扫描）；README 架构对齐；`devteam` 冻结标注 EXPERIMENTAL | 一天成本，防止后续优化被回归抵消 |
| 2 周 | 抽 `RouteResolver`；ArchUnit 7 条规则（含平台/场景边界）；定义「生产唯一形态」profile；**双工作台信息架构重组** | 大泥球止血，边界硬化，买家入口分层 |
| 1 个月 | agents / flows 定义元数据落库（5.1 提前）；KB 只读检索下沉决策；场景包接入契约 v0 | 定义可管理是平台主线的对外承诺 |
| 季度 | 按域拆模块（`payroll-kb`、`payroll-runtime`）；Nacos 退化为推送通道；`devteam` 按场景包标准重启评估 | 到此时才有拆分收益 |

---

## 8. 证据索引

| 结论 | 证据位置 |
| --- | --- |
| 模块拆分与依赖 | `pom.xml`；`payroll-common/pom.xml` 依赖列表；`payroll-agent/pom.xml`、`payroll-backend/pom.xml` |
| 共享层规模 | `payroll-common/src/main/java` 316 文件 / 48.8k 行；包分布见 §1 |
| 控制平面规模 | `payroll-agent/.../service/PayrollChatService.java`（2991 行，70 import） |
| 路径类型枚举 | `payroll-common/.../domain/ChatRouting.java:59-64` |
| 能力声明 | `payroll-common/.../domain/AgentDefinition.java:32-36` |
| 流程执行语义 | `docs/agent-orchestration.md:83-102` |
| 双库并存 | `payroll-common/.../db/PayrollDatabase.java:29-38` |
| KB 反向调用 | `payroll-common/.../agent/KbQueryTools.java`（`KbSkillGateway`，检索在 Web 侧） |
| Nacos 文档存储 | `payroll-common/.../skill/NacosAgentRegistry.java`、`NacosFlowRegistry.java`、`ModelConnectionRegistry.java` |
| devteam 零 REST | `payroll-backend/src/main/java` 无 devteam Controller；`payroll-common/.../devteam` 45 文件 |
| 侧栏分组现状 | `frontend/src/components/AppLayout.vue:18-51`（协同 / 薪酬 / 系统，手写数组） |
| 路由无工作区归属 | `frontend/src/router/index.js:16-34`（15 条平铺路由） |
| 平台包对薪酬域耦合 | `payroll-common/.../{skill,model,profile}` 仅导入 4 个薪酬异常类型（14 个文件），业务类型 0 依赖 |
| 场景锚点引用面 | `PayrollEngine`（`common/service`，10 文件）、`PayrollTools`（`common/agent`，4 文件）、`PayrollAgentFactory`（`agent/agent`） |
| devteam 域耦合 | `devteam` 22 个文件导入薪酬 `domain` 类型 |
| 无 CI | 仓库根目录无 `.github/workflows` |

---

## 9. 待拍板事项

1. ~~产品主线~~ **已拍板（2026-11-22）：Agent 平台 + 薪酬运营双主线**。落地解释见 §6；对应工作流：M1（平台/场景边界规则）、M5（定义落库提前）、M8（双工作台 IA）。
2. `devteam`：**先冻结（选项 B）**，菜单与文档标注 EXPERIMENTAL；场景包接入契约稳定后，下季度按「研发场景包」重启（选项 A 的变体），作为平台分层是否成立的第一块试金石。
3. 生产唯一形态：是否同意 MySQL + Redis + ES + Nacos，其余仅测试档？
4. 定义落库时间点：双主线确认后建议一个迭代内启动 5.1（原「技能破百」门槛取消）。
5. 质量门优先级：CI 先行是否认可？
