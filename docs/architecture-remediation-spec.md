# 架构整改实施规格（v0.2）

- 依据：`docs/architecture-review.md`（2026-11-22，基线 `2dbaa65`）
- 决策记录：**D1 已于 2026-11-22 拍板——产品主线为「Agent 平台 + 薪酬运营」双主线**。本版据此新增 M8（双工作台信息架构）、上调 M5（定义落库）优先级、扩展 M1 边界规则、调整 D2 建议。
- 性质：可执行规格，按工作流（M0–M8）拆分，每个工作流包含改动清单、步骤、验收、回滚
- 估算口径：人日，含自测，不含评审等待；单人 + AI 协作节奏
- 状态：D1 已拍板；D2–D5 按「推荐默认」推进，拍板后回写本文档

---

## 1. 目标与非目标

### 目标（按优先级）

1. **止血**：建立质量门，让 1246 个测试成为合并前置条件，而不是本机仪式。
2. **分层**：按 D1 双主线切出「平台能力层 / 薪酬场景层」，场景以场景包契约接入；控制台按平台工作台、薪酬工作台、平台治理三组组织。
3. **边界**：把「共享内核实为迷你平台」的漂移按住，形成可执行的依赖规则，并把平台/场景隔离纳入其中。
4. **解耦**：把 2991 行 `PayrollChatService` 中的路由决策抽成纯函数，隔离策略复杂度。
5. **收敛**：明确并强制「生产唯一形态」，消灭 2^N 降级组合。
6. **可观测**：从「有指标」升级为「有 SLI/SLO/告警」。
7. **规模化**：定义数据从 Nacos 文档迁到关系库（提前）；知识库只读检索下沉。

### 非目标（本期不做）

- 不拆微服务，不引入服务网格；
- 不迁移 Nacos v2 gRPC 或配置监听；
- 不重写前端对话页（仅做行数与拆分评估）；
- 不改变薪酬计算引擎与金额链路；
- 不做多租户。

---

## 2. 约束与红线

- JDK 17；离线构建命令：`JAVA_HOME=/d/tools/jdk/jdk-17.0.12 mvn -o ...`；CI 使用在线。
- 新表只写一份 Flyway 迁移（`db/migration/common`，H2 `MODE=MySQL` 与 MySQL 共用），不再维护 H2 内联 DDL。
- 新管理接口必须带权限码 + 测试；新写操作必须进审计。
- **现有权限码不重命名**（角色种子、前端判断、测试均依赖）；新能力带工作台前缀。
- 异常分支按类型判断，不按消息文本。
- 不提交 `data/`、不上传凭证；不执行破坏性 git 操作。
- 每个工作流合并前：全量测试绿 + 文档回写（`architecture-review.md` 状态列）。

---

## 3. 决策前置（D1–D5）

| 编号 | 决策 | 选项 | 结论 / 推荐 | 影响 |
| --- | --- | --- | --- | --- |
| D1 | 产品主线 | 薪酬运营 / Agent 平台 / 双主线 | **已拍板：双主线（Agent 平台 + 薪酬运营）** | M1 增加平台/场景规则；M5 提前；新增 M8 |
| D2 | `devteam` 去留 | A 补 REST+UI（5 人日）/ B 冻结（0.5）/ C 删除（1） | **B 冻结**；场景包契约稳定后按 A 变体重启 | 减少 45+ 文件维护面 |
| D3 | 生产唯一形态 | MySQL + Redis + ES + Nacos，其余仅测试档 | **采纳** | M3 的前提 |
| D4 | 定义落库时点 | 立即 / 技能破百前 / 不做 | **一个迭代内启动 5.1**（原「技能破百」门槛取消） | M5 排期前移 |
| D5 | CI 优先级 | 本周 / 随迭代 | **本周（M0 第一个合并）** | 其余工作流的回归保障 |

### 3.1 D1 拍板后的落地解释

| 层 | 范围 | 规则 |
| --- | --- | --- |
| 平台能力层 | `skill / model / usage / memory / profile / team / kb / health / support` + 轮次生命周期 | 不得依赖薪酬业务类型与 `PayrollEngine / PayrollTools / PayrollAgentFactory` |
| 薪酬场景层 | `domain` 业务类型、`PayrollEngine`、`PayrollTools`、薪酬技能/流程/智能体 | 作为首个场景包；只依赖平台 SPIs，不反向侵入平台实现 |
| 控制面 | `payroll-backend` Controller、`payroll-agent` 对话服务 | 按工作台归属组织；对话入口全局唯一，不按工作台拆分 |
| 信息架构 | 平台工作台 / 薪酬工作台 / 平台治理 | 复用现有侧栏分组能力，只做归属、命名、默认落地页与权限分组（M8） |
| `devteam` | 第二场景包雏形 | 22 个文件直接依赖薪酬域，暂不可移植；先冻结（D2-B），契约稳定后重启 |

基线事实（用于 M1 规则验收）：平台包当前对薪酬域仅导入 4 个异常类型（14 个文件），业务类型零依赖；三件套只被场景层引用。因此 R6 上线时只需处理异常类型中立化，不涉及业务解耦大改。

未拍板项（D2/D3/D5）：按推荐默认执行 **M0 → M1 → M2 → M3**；M5 按 §10.0 提前启动；M8 与 M1 同批。

---

## 4. 工作流总览

| 编号 | 工作流 | 产出 | 估算 | 依赖 | 阶段 |
| --- | --- | --- | --- | --- | --- |
| M0 | 质量门与文档对齐 | GitHub Actions、测试分层、README/architecture.md | 1.5 | — | 第 1 周 |
| M1 | 边界硬化 | ArchUnit 规则 + 冻结清单 + internal API 清单 | 3 | M0 | 第 2–3 周 |
| M2 | 路由解耦 | `RouteResolver` + `RoutePlan` + 快照测试 | 4 | M0 | 第 2–4 周 |
| M3 | 生产形态收敛 | `application-prod.yml` + `TopologyGuard` + 部署矩阵 | 2.5 | D3 | 第 3 周 |
| M4 | 观测与 SLO | 4 个新指标 + 5 条告警 + 看板 + 文档 | 2 | M0 | 第 3–4 周 |
| M5 | 定义落库 | 三个 Repository + V21/V22 DDL + 导入器 + 双读 | 6–8 | D4、M1 | 第 1–2 月（提前） |
| M6 | KB 检索下沉 | A：预算/熔断/权限缓存；B：ADR | 3–5 | M4 | 第 2 月 |
| M7 | 模型连接一等资源 | 健康状态机 + 半开探测 + 成本归因 | 4 | M4 | 第 2 月 |
| M8 | 双工作台信息架构 | 归属矩阵 + 路由/菜单重组 + 默认落地页 + 权限分组 | 2–3 | D1、M1 | 第 2–3 周 |
| D2 | `devteam` 决策门 | 决策记录 + 执行 | 0.5–5 | D2 | 第 1 周内拍板 |
| P2 | 前端拆分评估 | ChatView 拆分方案（不改码） | 1 | — | 第 3 月 |

总计约 29–36 人日（不含 D2 选项 A 的 5 人日）。

---

## 5. M0 质量门与文档对齐

### 5.1 问题

无 CI；1246 个测试仅本机执行；README 仍把三阶段流水线描述为唯一链路。

### 5.2 改动清单

| 文件 | 动作 |
| --- | --- |
| `.github/workflows/ci.yml` | 新建 |
| `payroll-tests/**/*WebTest.java`、`Dev*E2eTest.java` | 加 `@Tag("integration")` / `@Tag("e2e")` |
| `docs/architecture.md` | 新建（模块图、进程图、路由路径图、配置矩阵入口） |
| `README.md` | 重写为「定位 + 快速开始 + 文档索引」 |
| `docs/architecture-review.md` | 状态回写 |

### 5.3 CI 规格

```yaml
name: ci
on:
  pull_request:
  push:
    branches: [main]
  schedule:
    - cron: '0 18 * * *'   # 每日全量

jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: maven }
      - name: PR 快速档
        if: github.event_name == 'pull_request'
        run: mvn -B -ntp -DexcludedGroups=integration,e2e,eval test
      - name: 全量档
        if: github.event_name != 'pull_request'
        run: mvn -B -ntp test
  frontend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
      - run: npm run test
      - run: npm run build
  security:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: gitleaks/gitleaks-action@v2
```

### 5.4 实施步骤

1. 打标签：`*WebTest` → `integration`；`Dev*E2eTest` → `e2e`；`KbRoutingEvalTest` 已 `eval`；其余默认 unit。
2. 验证排除参数生效：`mvn -DexcludedGroups=integration,e2e,eval test` 秒级启动、用例数下降。
3. 提交 CI 工作流，首个 PR 观察三个 job。
4. 写 `docs/architecture.md`（≤200 行，图表用代码块），README 指向它。
5. 更新 `architecture-review.md` §0 表：P0-4 标记完成。

### 5.5 验收

- [ ] PR 上 `backend / frontend / security` 三个 check 均为绿；
      工作流已落盘，待首个 PR 实跑；本地已验证 backend 快速档与 frontend `npm run test` + `npm run build`；
- [x] PR 快速档 ≤ 8 分钟，夜间全量 ≤ 25 分钟；
      本机快速档 `mvn -DexcludedGroups=integration,e2e,eval test`：1216 例 / 0 失败 / 51.6s；
- [x] README 不再出现「固定三阶段」作为唯一链路描述；
      首屏改为双主线 + FLOW/AGENT/TEAM_ACTIVE/GRAPH/PIPELINE 多链路说明，并指向 `docs/architecture.md`；
- [x] `docs/architecture.md` 含模块依赖图与六种路由路径说明（117 行）。

### 5.6 回滚

删除 `.github/workflows/ci.yml` 即恢复；测试标签不影响本地全量运行。

---

## 6. M1 边界硬化

### 6.1 问题

`payroll-common` 48.8k 行、316 文件，web 与 runtime 共同依赖；包间依赖无规则约束，继续漂移将无法拆分。

### 6.2 改动清单

| 文件 | 动作 |
| --- | --- |
| `payroll-tests/pom.xml` | 加 `archunit-junit5`（test scope） |
| `payroll-tests/src/test/java/.../architecture/ArchitectureRulesTest.java` | 新建 |
| `payroll-common` 对外 API 清单 | 首版落在 `docs/scenario-packs.md` §5（包级清单），不新建 `api` 包；`@Internal` 为约定标注 |
| `archunit_store/` | 若启用冻结规则，纳入版本管理 |

### 6.3 规则集（第一批，刻意从少开始）

| 规则 | 内容 | 目的 |
| --- | --- | --- |
| R1 | `..domain..` 不得依赖 `..service..`、`..web..`、`..skill..`、`..db..` | 领域保持纯模型 |
| R2 | `..kb..` 不得依赖 `ChatSessionManager`、`PayrollChatService` | KB 不反向依赖对话 |
| R3 | `io.agentscope.payroll.runtime..` 不得依赖 `..web..` | runtime 不依赖控制面实现 |
| R4 | `*Controller` 仅允许位于 `..web..` 或 `..kb..` | 入口收敛 |
| R5 | `payroll-sandbox` 不得依赖 `payroll-common` | 沙箱隔离锁死 |
| R6 | 平台包（`..skill.. / ..model.. / ..usage.. / ..memory.. / ..profile.. / ..team.. / ..kb.. / ..health.. / ..support..`）不得依赖 `..domain..` 业务类型、`PayrollEngine`、`PayrollTools`、`PayrollAgentFactory` | 双主线：平台 / 场景分层 |
| R7 | `..devteam..` 不得被平台包与 `payroll-backend` 的 Controller 引用 | 场景包隔离（D2 冻结的前提） |

> 不启用「完整分层规则」：当前代码无法一次达标，噪声会让人忽略规则。违规用 `FreezingArchRule` 冻结，条目必须带 owner 与到期日，每两周清零一批。

R6 的当前基线（2026-11-22 实测）：平台包对薪酬业务类型零依赖；4 个异常类型（`PayrollException` / `Conflict` / `NotFound` / `Unavailable`）被 14 个文件引用，属中立化债务；三件套中 `PayrollAgentFactory` 被 `team.TeamLlmClient` 引用 1 处，已进冻结清单。R7 的当前基线：平台包 `model.ModelConnections` 引用 `devteam.DevModelSettings`（已冻结）；`payroll-backend` Controller 零引用；`devteam` 22 个文件依赖薪酬 `domain` 类型，冻结成本低。

### 6.4 实施步骤

1. 加依赖，写 7 条规则，跑 `ArchitectureRulesTest` 收集违规基线。
2. 对每条违规分类：立即修（≤10 分钟）/ 冻结（记录 owner + deadline）/ 规则放宽（写明原因）。
3. 冻结清单进入 `archunit_store/`，在 PR 模板加入「新增违规需说明」。
4. 产出 `payroll-common` 对外 API 清单：哪些包允许被 `payroll-agent`、`payroll-backend` 引用（首版只做文档 + `package-info.java` 标注 `@Internal` 约定）。
5. 落地 R6/R7：异常类型中立化排在 3 周内（`PayrollUnavailableException` 等改由平台异常承接，场景层保留兼容别名）；`devteam` 标注 EXPERIMENTAL 并按 R7 冻结。
6. 补一页 `docs/scenario-packs.md`：场景包接入契约（依赖的平台 SPI：`AgentDefinition`、`SkillRepository`、`FlowDefinition`、`KbSkillGateway`、`ModelConnection`；禁止触碰的平台实现），作为 D2-A 重启与 M8 的依据。
7. 每周清理一批冻结项，目标 4 周内冻结条目 ≤ 10。

### 6.5 验收

- [x] `mvn -pl payroll-tests -am -Dtest=ArchitectureRulesTest test` 绿；
- [x] 7 条规则全部生效，冻结条目均有 owner 与到期日（`docs/scenario-packs.md` §6）；
- [x] 平台包对薪酬业务类型依赖为 0（异常类型在冻结到期前清零）；
- [x] `docs/scenario-packs.md` 列出平台 SPI 与禁止面；
- [x] 新增类落入禁止依赖时，测试能给出可读失败信息（含规则名与建议）。

---

## 7. M2 路由解耦

### 7.1 问题

`PayrollChatService.run()` 在一个方法内叠加六层策略（KB 规则 → 语义分类 → 模型路由 → 流程能力过滤 → B0/B1 保留 → 缓存/预取），2991 行、70 import。任何策略改动都要读完整方法。

### 7.2 目标结构

```
payroll-agent/.../routing/
├── RouteInput.java      // 纯输入快照
├── RoutePlan.java       // 纯输出决策
├── RouteResolver.java   // 无状态、无 IO、可单测
└── package-info.java
```

`RouteInput`（从 `run()` 提取，均为当轮已有数据）：

```java
record RouteInput(
    String question,
    boolean payroll,
    AgentRouter.Prior prior,
    KbRoutePlanner.Plan kbPlan,      // need / kind / source
    ResolvedFlow flow,               // 可空
    boolean flowKbCapable,
    RouteDecision routeDecision,     // 可空
    boolean kbExecutorEnabled,
    boolean kbContextEnabled,
    AgentDefinition preferredAgent,  // 可空
    boolean graphOrchestration,
    String previousPersona)
```

`RoutePlan`：

```java
record RoutePlan(
    String path,              // FLOW / AGENT / TEAM_ACTIVE / PIPELINE / GRAPH
    String persona,           // flow:<id> / agent:<id> / pipeline
    ResolvedFlow flow,        // 可空
    PayrollAgentFactory.Team team,
    boolean kbContextRound,
    boolean kbRerouted,
    boolean cacheable,
    List<String> agentIds,
    String routerNote,
    String routerSignature,
    String reason)            // 结构化原因，直接进 ChatRouting.reason
```

### 7.3 实施步骤（行为保持优先）

1. **先冻结行为**：从现有 `KbRoutingEvalTest` 与 50 题基线中提取 30 条 fixture（`routing-fixtures.jsonl`），覆盖 8 类分支；写快照断言（path / persona / kbRerouted / agentId / cacheable）。
2. 新建 `RouteResolver`，把 731–838 行逻辑**原样搬运**（不改判断顺序），`run()` 改为调用 resolver。
3. 快照对比：30 条 fixture 输出零差异后，再删除 `run()` 内旧分支。
4. 补表驱动单测 ≥ 12 例：流程优先、流程被 KB 能力过滤、AGENT 命中、KB 改道、TEAM、pipeline、B0/B1 保留、缓存可命中、上一轮角色延续。
5. 收紧 `run()`：只允许「调用 resolver → 执行 → 组装事件/落库」，目标 ≤ 300 行；`PayrollChatService` ≤ 2200 行。
6. 用 `@Tag("eval")` 的 50 题基线回归一次，路径分布与改造前一致。

### 7.4 验收

- [ ] 30 条 fixture 零差异；
- [ ] `PayrollChatService.run()` ≤ 300 行，文件 ≤ 2200 行；
- [ ] `RouteResolverTest` ≥ 12 例全绿；
- [ ] 50 题基线路径分布 diff 为空（允许 reason 文案变化）。

### 7.5 回滚

`RouteResolver` 为纯新增；若出现行为差异，`run()` 内保留旧分支开关 `payroll.routing.resolver-enabled`（默认 true），一键回退，下个版本删除。

---

## 8. M3 生产形态收敛

### 8.1 问题

双库 × 双缓存 × 双检索 × 六路径，理论组合 2^N；生产可能启动成「MySQL + 无 Redis + 无 ES」的隐性降级态。

### 8.2 改动清单

| 文件 | 动作 |
| --- | --- |
| `payroll-common/src/main/resources/application-prod.yml` | 新建：MySQL、Redis、ES、Nacos 必选；`payroll.deployment.mode=cluster` |
| `payroll-common/.../config/TopologyGuard.java` | 新建：启动期拓扑校验 |
| `docs/deployment-matrix.md` | 新建：profile × 依赖 × 允许组合 |
| `docker/docker-compose.yml` | 加 prod profile 示例 |

### 8.3 校验项（prod profile 下启动失败即退出）

1. `payroll.deployment.mode=cluster`；
2. 数据源非 H2（URL 前缀检查）；
3. Redis 连接提供者存在；
4. Nacos 已启用（`payroll.nacos.enabled=true` 且地址非空）；
5. 知识库嵌入为远程服务（禁止 HASH 降级路径）；
6. `PAYROLL_SECRET_KEY` 已配置（凭证加密依赖）。

> 错误信息必须单条、可定位（指出是第几项、缺什么、参考哪份文档），不允许堆栈刷屏。多实例部署时由 readiness 摘流，而非进程内降级。

### 8.4 实施步骤

1. 写 `application-prod.yml`，与 `application-common.yml` 的默认值比对，避免重复定义。
2. 实现 `TopologyGuard`（`ApplicationRunner`，`@Profile("prod")`），逐项校验并聚合错误。
3. `TopologyGuardTest`：6 条校验各一例 + 全通过一例（用 `MockEnvironment`，不启容器）。
4. 部署矩阵文档：单机演示 / 单机生产 / 集群生产 三档，明确允许与禁止组合。
5. compose 增加 `prod` 覆盖示例（不改变默认演示模式）。

### 8.5 验收

- [x] prod profile 缺 Redis 时启动失败，退出码非 0，日志仅一条明确错误；
  `TopologyGuardTest` 8 例通过（缺项聚合 `（N 项）` 单条消息并抛 `IllegalStateException`），未在容器内实跑退出码；
- [x] prod profile 全配置时启动成功，`/actuator/health` 各组件 UP；
  2026-11-22 本机实跑（MySQL/Redis/Nacos/ES/Ollama 均在监听，端口 8091 避开在跑实例）：
  `prod` profile 6.2s 启动，`TopologyGuard` 单条通过日志；`/actuator/health`、`/readyz` 均 `UP`，
  `/api/health` 组件 `db/redis/es=UP`，`nacos=UNKNOWN`（启动后尚无技能列表请求，按最近一次成功
  列表时间判定；Nacos 读取已由启动日志「从 Nacos 加载 3 个模型连接」证实）。
- [x] `deployment-matrix.md` 列全允许组合，CI 不跑 prod 容器（本地验证）。

---

## 9. M4 观测与 SLO

### 9.1 现状

`TurnMetrics` 已有 12 个指标（`payroll.chat.turn.gap/degrade/lease.lost`、`stream.rejected`、`quota.rejected`、`submit.budget.exhausted`、`model.failover` 等），但缺「成功率 / 首字延迟 / 检索失败 / 模型调用结果」。

### 9.2 新增指标

| 指标 | 类型 | 埋点位置 | SLI |
| --- | --- | --- | --- |
| `payroll.chat.turn.completed{outcome=success\|failed}` | counter | `ChatSessionManager` 轮次终态 | 轮次成功率 ≥ 99%（1h） |
| `payroll.chat.turn.first.token` | timer | 首次 `onText`（listener 包装） | P95 ≤ 8s |
| `payroll.kb.search{outcome=ok\|timeout\|error}` | counter | `KbSkillGateway` | 超时率 ≤ 2% |
| `payroll.model.call{plane,outcome}` | counter | 模型调用中间件 / `FailoverModel` | 失败率 ≤ 1% |

### 9.3 交付物

- `ops/prometheus/alerts.yml`：5 条告警（成功率、首字 P95、429 率、KB 超时率、模型失败率），阈值与 SLO 表一致；
- `ops/grafana/payroll-slo.json`：单面板 + 四象限；
- `docs/observability.md`：SLI/SLO 定义、告警含义、处置手册链接（`restore-runbook.md`）。

### 9.4 实施步骤

1. `TurnMetrics` 增加计数器与 timer，沿用 `deploymentMode` tag；
2. 在轮次完成/失败、首字、网关、模型调用处埋点；
3. 写告警与看板文件（JSON 可直接导入）；
4. 本地 `/actuator/prometheus` 验证指标出现；
5. `promtool check rules ops/prometheus/alerts.yml` 通过。

### 9.5 验收

- [ ] 四个新指标在本地 scrape 可见；
- [ ] 告警规则通过 promtool 校验；
- [ ] `docs/observability.md` 列出 5 个 SLO 与对应告警名。

---

## 10. M5 定义落库

### 10.0 排期调整（v0.2）

D1 定为双主线后，定义落库从「技能破百前」提前为**双主线确认后一个迭代内启动**：平台主线对外承诺「定义可管理、可迁移、可审计」，场景包分发也依赖稳定的定义存储。第 1 月内完成端口设计与 DDL 评审，第 2 月落地 5.1；5.2 / 5.3 仍按观察两周后推进。

### 10.1 问题

agents / flows / skills / models / settings 全部以 Nacos JSON 整文档存储，自研 revision 与单键写替代了数据库事务。

### 10.2 目标

| 阶段 | 内容 | 回滚 |
| --- | --- | --- |
| 5.1 | `agent_definition` 表 + Repository + Nacos 导入器 | 关闭 `payroll.definitions.source=db` 回 Nacos 读 |
| 5.2 | `flow_definition` 表 | 同上 |
| 5.3 | `skill_definition` 元数据表（文件包仍走现有存储） | 同上 |

### 10.3 DDL 草案（V21 + H2 内联）

```sql
CREATE TABLE agent_definition (
  id            VARCHAR(128) PRIMARY KEY,
  payload_json  LONGTEXT NOT NULL,
  revision      BIGINT NOT NULL,
  updated_by    VARCHAR(128),
  updated_at    TIMESTAMP NOT NULL
);
```

H2 用 `CLOB`；`revision` 语义沿用现有内容指纹契约（写前重读 → 409），对前端零改动。

### 10.4 实施步骤

1. 抽 `AgentDefinitionRepository` 端口（list/get/save(expectedRevision)/delete），JDBC 实现；现有 `NacosAgentRegistry` 改为适配层。
2. 启动导入器：表为空且 `payroll.definitions.import-from-nacos=true` 时，从 Nacos 导入一次（幂等、打印条数）。
3. 写路径切换：先写 DB（事务），再 best-effort 发布 Nacos 变更通知；失败只告警不回滚（DB 为权威）。
4. 读路径：DB 优先；读失败按现有语义返回 503，**不允许**静默回退 Nacos 旧文档（避免双源打架）。
5. 测试：并发编辑 409、导入幂等、Nacos 不可用仍可读、revision 递增。
6. 5.1 上线观察两周后，再按同样模式做 5.2 / 5.3。

### 10.5 验收

- [x] Nacos 停机时管理台列表/详情仍可用；
  `NacosAgentRegistryDbSourceTest` 以 `http://127.0.0.1:1` 模拟停机，db 源仍可 save/list/delete；
- [x] 两个并发写只有一个成功，另一个 409 且前端提示刷新；
  `JdbcAgentDefinitionRepositoryTest.concurrentWritersOnlyOneWins`（同一行并发更新，恰好一胜、行 revision=2）；
- [x] 导入器重复执行不产生重复行；
  `importIfEmptyIsIdempotent`（2 → 0）、`importSkipsWhenTableNotEmpty`；多节点重复触发按主键冲突吞掉并返回 0；
- [x] 关闭开关后可完全回到 Nacos 读（演练一次）。
  `payroll.definitions.source` 默认即 `nacos`，db 代码分支不参与；存量 `NacosAgentRegistryTest` 18 例全绿，
  导入器在 nacos 源下直接跳过（`NacosAgentRegistryDbSourceTest` 覆盖）。未在真实数据集上做双向切换演练。

### 10.6 实施记录（2026-11-22，5.1 已完成）

- DDL：`db/migration/common/V21__agent_definition.sql`（H2 与 MySQL 共用同一份迁移）。
- 端口：`repository/AgentDefinitionRepository`；实现 `db/JdbcAgentDefinitionRepository`：
  写事务内 `SELECT ... FOR UPDATE` 锁行后重算目录指纹（排序后 `id:行revision` 的 SHA-256），
  与前端 revision 不一致即 `PayrollConflictException`（409，带 `currentRevision`）。
- 适配层：`NacosAgentRegistry` 新增 `source` 分支；db 模式下读/写/删除均走数据库，
  写成功后再 best-effort 发布 Nacos 变更通知（失败只 WARN，不回滚 DB）。
- 导入：`payroll.definitions.import-from-nacos=true` 时由 `ApplicationRunner` 调用
  `importFromNacosIfEmpty()`；`importIfEmpty` 在事务内确认空表才插入，重复执行/多节点并发返回 0。
- 测试：`JdbcAgentDefinitionRepositoryTest` 7 例、`NacosAgentRegistryDbSourceTest` 5 例，
  存量 `NacosAgentRegistryTest` 18 例保持全绿（共 30 例）。
- 配置文档：`application-common.yml` 的 `payroll.definitions.*`；README「Agent 管理」与
  `docs/agent-management.md` 已补开关说明，`docs/architecture.md` 定义中心一行同步。

---

## 11. M6 知识库检索下沉

### 11.1 分两阶段

**A（本月，低风险）**：保留 `/internal/kb` 通道，但加预算与熔断。

| 改动 | 内容 |
| --- | --- |
| `KbSkillGateway` | 连续 3 次失败熔断 30s；单次预算 3s（可配）；半开探测 |
| 权限缓存 | 库级权限结果缓存 60s，轮内复用 |
| 指标 | 接入 M4 的 `payroll.kb.search` |
| 降级文案 | 明确「知识库暂不可用，本轮未引用材料」，禁止假装检索过 |

配置：`payroll.kb.gateway.timeout-ms=3000`、`failure-threshold=3`、`open-ms=30000`、`permission-cache-ms=60000`。

**B（下季度，需 ADR）**：二选一并产出 ADR：

- B1 抽 `payroll-kb` 模块：读写都走模块，web 只做权限管理；
- B2 共享存储直读：runtime 只读 ES/JDBC，权限来自共享表 + 缓存。

推荐先做 B1 的评估（依赖面、迁移量、部署单元），不急于实施。

### 11.2 验收（A）

- [ ] 模拟 Web 不可达：轮次 ≤ 3s 内降级，回答含明确提示；
- [ ] 熔断期内不再发起调用，指标可见；
- [ ] 正常路径 P95 相比现状不退化（本地采样记录在 PR）。

---

## 12. M7 模型连接一等资源

### 12.1 现状

`FailoverModel` 只在首包 5s 内连接类错误切换一次，无状态记忆、无恢复回切。

### 12.2 目标

```
UNKNOWN → HEALTHY → DEGRADED → OPEN → HALF_OPEN → HEALTHY
```

- 连接失败：`HEALTHY → DEGRADED`，连续 N 次失败进 `OPEN`（跳过该连接，直接用备用）；
- `OPEN` 到期后下一轮以 1s 首包预算探测（`HALF_OPEN`），成功 2 次回 `HEALTHY`；
- 所有状态变更写日志 + gauge `payroll.model.connection.state{id,state}`；
- 用量记录补 `connection_id` 维度（若现状缺失），成本可按连接归因。

### 12.3 实施步骤

1. 在 `ModelConnectionRegistry` 增加内存健康状态（不改存储结构）；
2. `FailoverModel` 与状态机协作，保留「一轮最多切一次」语义；
3. 状态机单测 8 例：成功、首包超时、非连接错误、OPEN 跳过、半开成功、半开失败、回切、备用也失败；
4. 指标与日志接入 M4；
5. 文档补充到 `docs/agent-runtime-cache-spec.md` 或新建 `docs/model-connection-health.md`。

### 12.4 验收

- [ ] 主连接故障后下一轮自动回切（本地桩验证）；
- [ ] 状态 gauge 在 `/actuator/prometheus` 可见；
- [ ] 无备用连接时行为与现状一致（错误原样抛出）。

---

## 13. M8 信息架构（双工作台）

### 13.1 问题

D1 已定为双主线，但前端仍把管理页签平铺在三个手写分组里（`AppLayout.vue:18-51`）：平台能力（智能体 / 流程 / 技能 / 团队）与薪酬业务（员工 / 考勤 / 绩效 / 批次）并列，「系统」组又把模型配置、用量、知识库与用户权限混在一起。路由无工作区归属（`router/index.js:16-34`），无法按角色给出不同默认落地页。

### 13.2 目标结构

| 工作台 | 页面 | 主要角色 |
| --- | --- | --- |
| 平台工作台 | 对话、智能体、流程编排、技能、团队运行、知识库 | 平台管理员、业务线开发者 |
| 薪酬工作台 | 员工、考勤、绩效、政策、工资批次、我的待办 | HR、财务、薪酬专员 |
| 平台治理 | 模型配置、用量看板、审计日志、用户与角色、我的工作偏好 | 平台管理员、系统管理员 |

- 路由前缀 `/platform/*`、`/payroll/*`，旧路径保留前端重定向至少一个版本，避免书签、断点恢复、邮件链接失效。
- 工作区切换器置于侧栏顶部；对话页保持全局唯一入口，会话列表不按工作台拆分。
- 权限：**不重命名现有权限码**；新增能力带工作台前缀；权限页按工作台分组展示，判定逻辑不变。
- 角色默认落地页：平台角色 → `/platform/agents`；薪酬角色 → `/payroll/employees`；纯员工 → `/chat`。

### 13.3 实施步骤

1. 产出归属矩阵（页面 → 工作台 → 权限码 → 角色），评审一次，避免边做边改。
2. `router/index.js` 增加 `meta.workspace` 与旧路径重定向；页面组件不改。
3. `AppLayout.vue` 改为由路由 meta 生成菜单，删除手写数组；图标与折叠行为保留。
4. 登录后跳转按角色计算默认落地页。
5. 权限页与审计筛选增加工作台维度（只读展示，不改权限判定）。
6. 前端单测：旧路径重定向、按权限的菜单可见性、默认落地页。

### 13.4 验收

- [x] 三类角色登录后分别落到预期工作台与页面；
      本机实跑 ADMIN → `/platform/agents`；HR/VIEWER/纯员工的落地页由 `landingPath` 单测覆盖（未用真实账号实登）；
- [x] 所有旧路径可访问并重定向，无 404；
      单测遍历 `LEGACY_REDIRECTS` 16 条，实跑 `/employees` → `/payroll/employees`、`/kb/12` → `/platform/kb/12`、未知路径 → `/chat`；
- [x] 菜单可见性与权限码一致，普通员工看不到治理项；
      ADMIN 实跑三组切换器与治理菜单；HR/纯员工可见性由 `navigation.test.js` 覆盖，「用户与角色」已对齐 `user:manage`；
- [x] 对话页行为零变化（跑一次对话冒烟）。
      实跑 `/chat` 渲染 44 个历史会话，输入后发送按钮由禁用变为可用；未实发消息（本机模型连接此前报 Connection reset，避免制造失败轮次）；
- [x] 归属矩阵已落盘 `docs/frontend-ia.md`（页面 → 工作台 → 权限码 → 角色），待评审确认。

### 13.5 回滚

菜单与路由 meta 可回退到现有手写数组；旧路径始终保留，回滚不影响深链。

---

## 14. D2：`devteam` 决策门（第 1 周拍板）

现状：`payroll-common/.../devteam` 45 文件 + agent 侧运行器，backend 零 Controller、零 UI，仅 JUnit E2E；22 个文件直接依赖薪酬 `domain` 类型。

双主线拍板后，`devteam` 的定位是「第二个场景包雏形」，而非薪酬功能；但它当前不满足平台/场景边界（M1 R7 需冻结），也不具备可移植性。

| 选项 | 内容 | 成本 | 后果 |
| --- | --- | --- | --- |
| A | 补最小 REST + 任务页，作为「研发场景包」演示 | 5 人日 | 验证场景包契约，但需先偿还域耦合 |
| B（推荐，现在） | 冻结：保留代码与测试，标注 EXPERIMENTAL，不进菜单 | 0.5 | 平台契约未定前不投入 |
| C | 删除 | 1 | 丢弃已有实现，少一个场景包验证素材 |

推荐路径：**现在 B；场景包契约（M1 交付物 `docs/scenario-packs.md`）稳定后，下季度以 A 重启**，作为「非薪酬场景包」验收平台分层是否真的成立。拍板后 1 天内执行，并在 `architecture-review.md` §5 回写。

---

## 15. 排期与里程碑

| 周次 | 工作流 | 退出标准 |
| --- | --- | --- |
| W1 | M0 + D2 拍板 + M5 设计 | CI 三 check 绿；README/architecture.md 就绪；D2 冻结落地；M5 端口与 DDL 评审通过 |
| W2–W3 | M1 + M2 + M8 | 7 条规则绿、冻结 ≤ 10；fixture 零差异、run() ≤ 300 行；双工作台可切换 |
| W3 | M3 | prod 启动校验生效；部署矩阵文档就绪 |
| W4 | M4 | 四指标可见、五告警校验通过 |
| M2（第 2 月） | M5.1 + M6A + M7 | agents 落库、网关熔断、连接健康态 |
| M3（第 3 月） | M5.2/M5.3 + M6B ADR + P2 评估 + D2-A 评估 | 定义全部落库；ADR 定稿；场景包重启方案定稿 |

每个里程碑结束：更新 `architecture-review.md` 状态、在 PRD 记录偏差、跑一次全量 + 前端构建。

---

## 16. 度量（改造前后对比）

| 指标 | 现状 | 目标 |
| --- | --- | --- |
| CI 覆盖 | 0 | 100% PR |
| `PayrollChatService` | 2991 行 | ≤ 2200 行 |
| `run()` | ≈400 行 | ≤ 300 行 |
| ArchUnit 规则 | 0 | 7 条 + 冻结清单 |
| 平台包对薪酬业务类型依赖 | 0（仅 4 个异常类型 / 14 文件） | 0（异常中立化） |
| 信息架构 | 3 组手写菜单、无工作区归属 | 双工作台 + 治理组，旧路径重定向 |
| 生产组合 | 未约束 | 1 种，启动强校验 |
| 定义存储 | Nacos JSON | 关系库权威 + Nacos 通知 |
| SLI/SLO | 0 | 5 个 + 告警 |
| `devteam` 可达性 | 仅测试 | 冻结，标注 EXPERIMENTAL |

---

## 17. 风险登记

| 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- |
| M2 搬运引入行为差异 | 中 | 高 | 先冻结 fixture，再搬运；保留开关 |
| 双工作台重组引发角色误配 | 中 | 中 | 归属矩阵先评审；权限码不改名；旧路径保留一版；三类角色冒烟 |
| 双主线并行导致资源摊薄 | 中 | 中 | 本季度只做分层与质量门，不做场景包扩张；M8 限 3 人日 |
| M5 双源写导致状态不一致 | 中 | 高 | DB 权威 + 单次导入 + 只读回退开关；灰度两周 |
| CI 首次依赖下载慢/失败 | 中 | 低 | Maven/npm 缓存；失败不阻塞手工合并，但需记录 |
| ArchUnit 噪声导致规则被无视 | 高 | 中 | 只上 7 条规则 + 冻结到期机制 |
| 生产 profile 过严影响演示 | 低 | 中 | 保留默认 demo profile；prod 仅显式启用 |
| 单人节奏导致 M5 延期 | 中 | 中 | M5 分 5.1/5.2/5.3 三段，每段独立可回滚 |

---

## 18. 自审清单（每个里程碑合并前）

- [ ] 全量测试绿（夜间档），跳过项仅为条件跳过；
- [ ] 新增/修改行为有对应单测，异常分支按类型判断；
- [ ] 新表同时有 Flyway 与 H2 DDL；新接口有权限码与审计；
- [ ] 平台包未新增对薪酬业务类型与三件套的依赖（R6）；新代码已声明所属工作台；
- [ ] 文档已回写（review + 相关规格），无「待补」字样；
- [ ] 回滚步骤写清并能在一分钟内执行；
- [ ] 指标/日志能回答「这次改动如何被观测」。

---

## 19. 可直接建 issue 的任务清单

**M0**：① CI 工作流 ② 测试标签 ③ architecture.md ④ README 重写
**M1**：⑤ ArchUnit 依赖与 7 条规则（含 R6 平台/场景、R7 devteam 隔离） ⑤b 场景包契约文档与异常中立化 ⑥ 违规基线分类 ⑦ 冻结机制与 PR 模板 ⑧ internal API 清单
**M2**：⑨ fixture 30 条 ⑩ RouteResolver 搬运 ⑪ 快照对比清零 ⑫ 12 例单测 ⑬ run() 收紧
**M3**：⑭ application-prod.yml ⑮ TopologyGuard ⑯ 6 例校验测试 ⑰ 部署矩阵文档 ⑱ compose prod 示例
**M4**：⑲ 四个指标埋点 ⑳ 告警规则 ㉑ 看板 JSON ㉒ observability.md
**M5**：㉓ V21 + H2 DDL ㉔ Repository 端口与 JDBC 实现 ㉕ 导入器 ㉖ 写路径切换 ㉗ 409/Nacos 停机测试 ㉘ flows 表 ㉙ skills 元数据表
**M6**：㉚ 网关熔断与预算 ㉛ 权限缓存 ㉜ 降级文案 ㉝ M6B ADR
**M7**：㉞ 健康状态机 ㉟ 半开探测 ㊱ 8 例单测 ㊲ 成本归因
**M8**：㊶ 工作台归属矩阵 ㊷ 路由 meta 与旧路径重定向 ㊸ 菜单生成与角色默认落地页 ㊹ 权限页工作台分组与前端单测
**D2**：㊳ devteam 决策与执行
**P2**：㊴ ChatView 拆分方案
