# 薪酬核算智能体（AgentScope Java）

基于 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) 的薪酬核算智能体，
包含 CLI 对话、本地 Web 管理台与 Excel 批量导入三条入口。

核心原则：**大模型负责“听懂”和“解释”，确定性引擎负责“算对”。** 模型不参与任何金额计算，
所有数字都来自 `PayrollEngine`，保证结果可复算、可审计。

## 架构

```
自然语言对话（CLI / 管理台“智能问答”）   本地 Web 管理台（Spring Boot 3 / 127.0.0.1）
      │                                     │
      ▼                                     ▼
PayrollChatService（固定三阶段编排）   PayrollWebController（REST）
  ├─ ① 计算智能体：理解诉求 / 调用工具        ├─ 员工 / 考核 / 城市政策 / 考勤维护
  ├─ ② 验证智能体：对核算对象独立校验        │  （含 Excel 导入与模板下载）
  └─ ③ 核算智能体：汇总记录生成最终答复      ├─ 工资批次预览、微调、出账
      │                                     ├─ 对话式问答（SSE 流式）
      ▼ @Tool（8 个，返回结构化 JSON）        └─ 确认后 dry-run 全员发信
PayrollTools
      │
      └──────────────┬───────────────────────┘
                     ▼
PayrollEngine（BigDecimal 全链路）
  ├─ 个税累计预扣法（跨月递推）
  ├─ 城市社保 / 公积金（基数上下限）
  ├─ 绩效奖金系数 + 出账前人工微调
  ├─ 考勤扣款 / 三类加班 / 入职首月折算
  └─ 发薪前勾稽校验
                     │
      ┌──────────────┴───────────────┐
      ▼                              ▼
H2 文件库（主数据 + 批次 + 明细 + 邮件审计）   Excel（POI）
```

## 功能

| 能力 | 说明 |
| --- | --- |
| 单人核算 | 应发、考勤扣款、社保公积金、累计预扣个税、实发、企业成本 |
| 工资条 | 应发/扣款/实发三段式 + 个税计算过程 + 风险提示 |
| 批量核算 | 按部门或全员，汇总应发/实发/企业成本；单个员工失败不影响其他员工 |
| 发薪前校验 | 勾稽关系、社保基数上下限、实发是否为负等 6 项检查 |
| 考勤核对 | 事假、病假、旷工、工作日/休息日/法定节假日加班 |
| 主数据维护 | 管理台中新增/修改员工、停用员工、维护月度考核与绩效系数 |
| 用户与权限 | 登录后按角色控制接口与页面：内置管理员 / 人事 / 只读访客，支持自定义角色与 23 项权限码；用户、部门、角色均可在管理台维护 |
| 管理台导航 | 侧边栏为树状分组菜单：分组可折叠，宽屏可整体收起为图标栏，折叠状态保存在浏览器本地 |
| Agent 管理 | 专家智能体定义（名称、头像、标签、绑定技能、上下架、公开/私有）存储于 Nacos，支持搜索筛选与增删改；头像可选内置 Ant Design 图标或 Emoji；未启用 Nacos 时只读回退三个内置智能体 |
| 技能管理 | 以 `<技能ID>.md` 形式维护 Nacos 中的技能提示词，支持新增、编辑、删除；内置技能可被 Nacos 版本覆盖，删除覆盖后回退内置；被智能体引用的自定义技能禁止删除 |
| 流程编排 | 拖拽画布把已注册智能体与条件判断组成 DAG：分支、汇合、同层并行；可保存多条流程并指定全局默认，下一轮对话生效；未设置时沿用原流水线 |
| 多用户会话 | 每个登录用户拥有独立会话列表；同一用户可打开多个会话窗口，消息按会话持久化并按用户隔离 |
| Excel 导入 | 员工、考核、考勤支持 `.xlsx/.xls` 批量导入：表头别名匹配、逐行校验、部分成功并返回行号与失败原因 |
| 导入模板 | 管理台一键下载标准表头与填写说明，避免列名不一致 |
| 对话界面 | “智能问答”页签默认由三个智能体串行完成：计算 → 验证 → 核算；激活全局默认流程后按 DAG 执行。SSE 流式输出各智能体阶段、工具时间线与模型思考过程 |
| 城市政策 | 每个城市独立维护个税参数、社保公积金比例与基数上下限 |
| 工资批次 | 预览 → 人工微调 → 提交审批 → 审批通过 → 出账（服务端重算）→ 确认发送 |
| 审批工作流 | 全局/部门审批工作流可配置优先级与金额区间；节点按顺序多级审批，审批人可按角色或指定用户；「我的待办」只显示当前节点由自己处理、且不是自己提交的批次；提交人不能自批；金额指纹固定审批快照，审批后数据变化必须重新提交 |
| Excel 导出 | 工资明细、核算失败、出账说明三个工作表 |
| 明细入库 | 批次、逐人明细、微调快照、失败原因、邮件投递审计全部落 H2 |
| 邮件发送 | 仅 dry-run：逐人返回成功/失败状态与失败原因，不真实投递 |
| 政策问答 | 税率表、社保比例、基数上下限、指定城市政策、考勤就绪月份 |
| 结构化意图抽取 | 将自然语言解析为 `action / employeeId / period / department`（建议 DashScope 模型） |

## 快速开始

环境：JDK 17、Maven 3.6+、[Ollama](https://ollama.com/)（本地模型对话时需要）。

```bash
mvn clean package
cd frontend && npm ci && npm run build && cd ..

# 1) 智能体运行期（模型执行与动态团队，只暴露 /internal，默认 8100）
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-runtime.jar

# 2) Web 后端（鉴权、会话、管理台 API，默认 8080）
PAYROLL_AGENT_RUNTIME_URLS=http://127.0.0.1:8100 \
PAYROLL_AGENT_RUNTIME_TOKEN=local-demo-token-change-me \
java -Dfile.encoding=UTF-8 -jar payroll-backend/target/payroll-backend-exec.jar

# 3) 前端（开发模式 http://localhost:5173 ，/api 代理到 8080；构建产物在 frontend/dist）
cd frontend && npm run dev

# 离线演示：不调用大模型，直接验证核算引擎（CLI 入口）
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-cli.jar --demo

# 单人工资条 + 发薪前校验
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-cli.jar --payroll EMP001 --period 2026-10

# 部门批量
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-cli.jar --department 研发部 --period 2026-10
```

`mvn package` 产出三个可执行包：Web 为 `payroll-backend/target/payroll-backend-exec.jar`，
运行期为 `payroll-agent/target/payroll-agent-runtime.jar`，CLI 为 `payroll-agent/target/payroll-agent-cli.jar`；
模块普通 jar（`payroll-backend.jar`、`payroll-agent.jar`）留给模块间依赖，不要直接运行。

Web 与运行期在同一台机器上请从仓库根目录启动，H2 默认落在 `./data/payroll`。
Windows 下 8001–8100 属于系统保留区间，运行期改用 `PAYROLL_AGENT_PORT=18100`、Web 改用
`PAYROLL_WEB_PORT=28080` 即可；两个进程必须使用相同的 `PAYROLL_AGENT_RUNTIME_TOKEN`。

首次启动 Web 后端会创建 `data/payroll.mv.db` 并从 classpath 导入演示数据；
之后所有修改都写入 H2，不再被演示数据覆盖（切换 MySQL 见下文）。

首次建库会创建默认管理员 `admin / admin123`（仅当 `app_users` 为空时执行），
请登录后立即使用右上角“修改密码”。生产环境应通过 `PAYROLL_ADMIN_PASSWORD` 指定初始密码；
`PAYROLL_AUTH_ENABLED=false` 会跳过登录并授予全部权限，仅用于本地测试与离线演示。

三个模块职责：`payroll-common` 是领域模型、H2/MySQL 存储、会话轮次与共享配置；
`payroll-backend` 只做鉴权、会话、管理台 API 与 SSE 转发（`PayrollWebApplication`）；
`payroll-agent` 负责模型编排、工具、流程、动态团队与智能体路由（`AgentRuntimeApplication`）。
Web 不加载模型，未配置 `PAYROLL_AGENT_RUNTIME_URLS` 时对话接口直接返回明确错误；
CLI 的 `--demo`、`--payroll` 等模式仍在 `payroll-agent-cli.jar` 中运行，不启动 Spring 容器。

本地模型对话（默认，无需 API Key）：

```bash
ollama serve
ollama pull qwen2.5:3b-instruct
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-runtime.jar
```

管理台“智能问答”通过 `POST /api/chat/sessions/{id}/stream` 以 SSE 流式返回。若想看到类似 Qoder 的
“思考过程”，可换用带 thinking 能力的模型（启动时自动检测，无需额外开关）：

```bash
ollama pull deepseek-r1:1.5b
export PAYROLL_MODEL=ollama:deepseek-r1:1.5b  # Windows: set PAYROLL_MODEL=ollama:deepseek-r1:1.5b
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-runtime.jar
```

思考内容来自模型原生 reasoning 增量，不是前端模拟；工具调用、阶段提示与回答文本仍按事件分开推送。
注意：1.5B 级思考模型的工具调用稳定性弱于 `qwen2.5:3b-instruct`，若发现它直接作答而不调用工具，
请换用更大的思考模型，或关闭思考后继续用默认模型。`PAYROLL_OLLAMA_THINK=false` 可强制关闭思考。

使用 DashScope 在线模型：

```bash
export DASHSCOPE_API_KEY=sk-xxx          # Windows: set DASHSCOPE_API_KEY=sk-xxx
export PAYROLL_MODEL=dashscope:qwen-plus # Windows: set PAYROLL_MODEL=dashscope:qwen-plus
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-runtime.jar
```

示例提问：

- `算一下张伟 2026-10 的工资`
- `研发部 10 月工资表`
- `王强这个月为什么实发这么低？`
- `上海社保基数上下限是多少？`
- `替我检查 EMP005 10 月工资能不能发`

## 管理台界面

前端为 `frontend/` 下的 Vue 3 + Vite 单页应用，与后端只通过 `/api` 交互。外壳由
`frontend/src/components/AppLayout.vue` 提供：左侧导航按“协同 / 薪酬 / 系统”分组，可折叠为
64px 图标栏；顶栏展示面包屑与当前账号。路由与页面标题集中在
`frontend/src/router/index.js` 的 `meta.title`，新增页面时同时注册路由与侧边栏条目即可。
智能问答保留微信式左右气泡。构建产物由 Nginx 托管，`/api` 反向代理到 Web 节点。

## 管理台使用流程

1. **员工**：新增或修改工号、姓名、部门、城市、邮箱、银行卡、固定工资、津贴、公积金比例、入职日期。
   `停用` 只置为 inactive，历史批次与考核仍可追溯。工具栏支持 **导入 Excel** 与 **下载导入模板**；
   导入按工号整行覆盖，已停用员工会随导入重新启用。
2. **考核**：按 `员工 + 期间` 录入分数、绩效系数（0–2）与评语。系数乘当月绩效奖金，
   再进入应发、个税、实发。也可用模板按 `工号 + 期间 + 考核分数` 批量导入，
   评分为空的行会逐行报错，不影响其他行。
3. **城市政策**：维护基数上下限、个人/企业社保公积金比例、个税起征点等。
   未维护的城市回落到 `默认` 政策；若 `默认` 也未维护则核算报错。
4. **考勤**：按 `员工 + 期间` 维护事假、病假、旷工与三类加班小时数。
   目标月份考勤缺失时核算失败关闭，不把缺勤当全勤。支持 Excel 批量导入并覆盖同期间记录。
5. **工资批次**：选择期间与范围（全员或某部门）预览；有失败员工时不能提交审批。
   可对个别员工微调绩效奖金或其他补贴（微调值是最终值，不再叠加系数），
   页面会提示未应用的修改并禁用提交审批。
6. **提交审批**：批次进入 `PENDING_APPROVAL`，按“部门优先、金额区间、优先级”匹配审批工作流，
   并把期间、范围与逐人结果序列化为 SHA-256 金额指纹；同时冻结工作流节点快照，
   之后修改流程配置不会影响在途批次。
7. **审批**：具备 `payroll:approve` 的用户可通过或驳回（必须填写原因）；提交人不能审批/驳回
   自己提交的批次。工作流节点按顺序执行，审批人按角色或指定用户匹配，只有当前节点审批人
   能在「我的待办」中处理；中间节点通过记录“节点通过”事件，最后节点通过才进入 `APPROVED`。
   审批时重新计算指纹，提交后任何明细变化都会返回 409 并要求重新提交。
8. **出账**：审批通过后服务端按微调快照重新核算并与审批指纹比对，一致才写入 `FINALIZED`，
   生成 `output/payroll-<期间>-run<id>.xlsx`；不一致同样返回 409。
9. **发送**：必须显式确认；发送后展示逐人结果（成功/失败及原因），批次进入 `SENT`，重复发送被拒绝。
10. **审批工作流配置**：`approval:manage` 用户可在“审批工作流”页签维护全局/部门流程。同一范围只能有一条
    生效流程；部门流程优先于全局流程，再按优先级与创建顺序匹配。节点可设置金额区间，
    为空表示不限制；不配置节点时沿用单级审批（任何拥有审批权限的用户）。
11. **智能问答**：在管理台直接提问，回答只做查询、核算与解释；数据维护仍走对应页签，
    金额始终来自确定性引擎。左侧“我的会话”可新建多个窗口，每个窗口独立上下文；
    消息持久化并按登录用户隔离。对话依赖 `PAYROLL_MODEL`；模型不可用时不影响其他管理功能。

批次状态机：

```
DRAFT --提交审批--> PENDING_APPROVAL --审批通过--> APPROVED --出账--> FINALIZED --确认发送--> SENT
                        |      ^
                    驳回|      |重新提交
                        v      |
                     REJECTED --
```

## 登录、用户与权限

- 页面入口：未登录访问 `/` 会跳转 `/login.html`；登录成功后写入 `HttpSession`，退出登录使会话立即失效。
  密码使用 BCrypt 存储，接口不返回密码哈希。
- 接口校验：所有 `/api/**` 由 `AuthInterceptor` 统一拦截；未登录返回 401，缺少权限返回 403。
  控制器方法或类上的 `@RequirePermission` 声明所需权限码，`/api/auth/login` 与 `/api/health` 公开。
- 页面控制：登录后 `/api/auth/me` 返回当前用户、角色与权限集合；前端用 `data-permission`
  隐藏无权操作（新增/导入/出账/管理页签等），服务端仍是最终防线。
- 内置角色：

| 角色 | 说明 | 主要权限 |
| --- | --- | --- |
| `ADMIN` | 系统管理员 | 全部 23 项权限，含用户、部门、角色、审计、审批链、Agent 管理、技能管理与流程编排 |
| `HR` | 人事专员 | 员工/考核/考勤/工资批次读写、审批工资批次、Excel 导入、智能问答；政策只读 |
| `VIEWER` | 只读访客 | 员工/考核/政策/考勤/工资批次/部门/角色只读 + 智能问答 |

权限码按“查看/维护”成对划分：`employee:*`、`review:*`、`policy:*`、`attendance:*`、`payroll:*`，
外加 `payroll:approve`、`approval:manage`、`import:use`、`chat:use`、`department:read|manage`、
`role:read|manage`、`user:manage`、`audit:read`、`agent:manage`、`skill:manage`、`flow:manage`。
`agent:manage`、`skill:manage` 与 `flow:manage` 仅内置管理员拥有，分别用于 Agent 管理、
技能管理与流程编排页签。
自定义角色可自由组合权限码；内置角色不可修改或删除。

安全规则：不能停用自己或修改自己的角色；系统始终保留至少一名启用状态的管理员；
角色与部门必须存在（用户只能归属启用部门）；删除被员工或用户占用的部门会被拒绝；
部门改名会在同一事务内同步 `employees.department`。

## Agent 管理（Nacos）

管理台“Agent 管理”页签用于维护专家智能体定义：名称、头像、描述、标签、绑定技能、
可见范围（公开/私有）与上下架状态。头像可从内置的 26 个 Ant Design 图标中选择，
也可直接填写 Emoji；图标以 `ant:<name>`（如 `ant:robot`）随定义持久化，前端本地渲染，
不依赖外部 CDN。整个列表作为一个 JSON 配置保存在 Nacos
（dataId `payroll-agents.json`，group 默认 `payroll-agent`），读路径带 TTL 缓存，
写路径直接调用 Nacos v1 OpenAPI。

- 启用条件：默认连接本机 `http://127.0.0.1:8848`，可用 `PAYROLL_NACOS_SERVER` 指向其他实例
  （可带 `/nacos` 上下文路径，客户端会自动去除），`PAYROLL_NACOS_ENABLED=false` 显式关闭。
  未启用或配置缺失时，列表回退到由三个角色技能生成的内置智能体（计算/复核/入账专家），页面只读并禁用保存。
- 首次保存会把当前完整列表（含内置默认项）写入 Nacos；之后按 `id` 做新建或更新。
- 更新保留原 `createdBy`/`createdAt`，只刷新内容和 `updatedAt`；删除直接重写列表。
- 校验：名称 ≤30 字、描述 ≤200 字、头像 ≤40 字符（容纳 `ant:<name>`）、标签 ≤6 个且单个 ≤12 字、
  技能必须存在于当前技能仓库、状态/可见范围必须为枚举值。
- 角色隔离：流程节点和自定义技能智能体只注册其绑定的技能（AgentScope 默认会把仓库内全部技能
  注册进加载工具，仅靠 SkillFilter 不足以阻止模型加载未绑定技能）；自定义技能也不会继承薪酬
  口径提示词与薪酬工具白名单，确保“java工程师 + ai-coding”这类角色不会被拉回薪酬核算。
  每个会话持久化当前角色（`chat_sessions.persona_key`），切换默认流程/智能体时清空上一角色的
  会话上下文与向量记忆，事件流输出“检测到智能体切换，已隔离上一角色的会话上下文”。
- 审计：新增/修改/删除分别记录 `agent.create`、`agent.update`、`agent.delete`，
  修改与删除附带变更前快照。
- 智能体定义可直接被“流程编排”的 `AGENT` 节点引用；未设置全局默认流程时，对话仍固定使用
  计算 → 验证 → 核算三阶段流水线。已设置但无法解析（Nacos 抖动、流程不存在、引用智能体下架）
  时直接报错，不会静默回退薪酬链路；Nacos 读取抖动期间沿用上次成功加载的流程与智能体列表。
- 流程本身也存储在 Nacos：`payroll-flows.json` 保存流程数组，`payroll-flow-settings.json`
  保存 `activeFlowId`。画布与执行语义详见 [docs/agent-orchestration.md](docs/agent-orchestration.md)，
  配置模板见 `docs/nacos-skills/payroll-flows.json`。

配置结构（字段为 camelCase，时间为 ISO-8601）：

```json
[
  {
    "id": "payroll-calculator",
    "name": "薪酬计算专家",
    "avatar": "🧮",
    "description": "理解诉求并调用确定性工具完成核算",
    "tags": ["薪酬", "内置"],
    "skillIds": ["payroll-calculator"],
    "visibility": "PUBLIC",
    "status": "ONLINE",
    "createdBy": "system",
    "createdAt": "2026-11-08T09:00:00",
    "updatedAt": "2026-11-08T09:00:00"
  }
]
```

Nacos 鉴权沿用 `PAYROLL_NACOS_USERNAME`/`PAYROLL_NACOS_PASSWORD`；写接口需要该账号拥有配置发布权限。
多个实例同时编辑时按“最后一次写入”覆盖，暂未做版本号或乐观锁。

## 技能管理（Nacos）

管理台“技能管理”页签维护技能提示词，存储为 Nacos 配置 `<技能ID>.md`
（group 默认 `payroll-agent`，类型 `markdown`），列表通过 Nacos 搜索接口发现。
这与 Agent 管理共用 `PAYROLL_NACOS_SERVER` 与账号配置。

- 内容格式：正文为技能提示词，保存时自动写入 YAML frontmatter 的 `name` 与 `description`；
  详情接口返回去掉 frontmatter 的正文，便于表单直接编辑。
- 内置覆盖：三个内置角色技能（`payroll-calculator`、`payroll-validator`、`payroll-accountant`）
  在没有 Nacos 配置时使用代码内置版本；在页面编辑内置技能并保存即形成覆盖，列表标记“覆盖内置”。
  删除覆盖配置后自动回退内置版本。
- 自定义技能：ID 规则 `^[a-z][a-z0-9-]{1,63}$`；创建后立即出现在 Agent 管理的可绑定技能清单中，
  技能仓库按 TTL 缓存刷新发现结果。
- 删除保护：自定义技能仍被任一智能体引用时拒绝删除，并返回引用者名称；需先在 Agent 管理解绑。
  内置技能若没有 Nacos 覆盖版本，同样拒绝删除。
- 校验：描述 ≤200 字、正文非空且 ≤200000 字符、ID 不能与已有技能（含内置技能）重名；
  覆盖内置技能需使用编辑而非新建。
- 审计：新增/修改/删除分别记录 `skill.create`、`skill.update`、`skill.delete`；
  审计元数据只含 ID、描述、来源、是否内置与正文长度，不保存提示词正文。
- 只读回退：`PAYROLL_NACOS_ENABLED=false` 时页面只读，仍可查看内置技能内容；
  已启用但 Nacos 连接失败时列表回退内置技能，保存会提示写入失败。

## 流式对话（SSE）

管理台“智能问答”使用 `POST /api/chat/sessions/{sessionId}/stream` 消费 SSE；
旧版 `POST /api/chat/stream` 保留兼容，两者共用同一套事件协议。
浏览器 `EventSource` 不支持 POST，前端改用 `fetch` + `ReadableStream` 解析 `text/event-stream`。事件协议：

| 事件 | data | 前端行为 |
| --- | --- | --- |
| `start` | `{"model":"..."}` | 记录实际模型 |
| `agent_start` | `{"agent":"calculator","label":"① 计算智能体：…"}` | 新建一个智能体区块 |
| `phase` | `{"label":"模型思考中…"}` | 在当前智能体区块追加阶段提示（相同阶段去重） |
| `thinking` | `{"text":"增量"}` | 追加模型原生思考内容 |
| `tool_start` | `{"tool_call_id","name"}` | 新增工具步骤 |
| `tool_args` | `{"tool_call_id","text"}` | 追加工具参数 JSON |
| `tool_result` | `{"tool_call_id","name","state","duration_ms","amounts","employees","period","department","truncated"}` | 标记工具状态；`amounts` 为从结果中提取的关键金额 |
| `agent_text` | `{"agent":"validator","text":"增量"}` | 追加中间智能体的阶段输出（计算记录 / 验证记录） |
| `agent_end` | `{"agent":"calculator"}` | 标记智能体区块完成 |
| `text` | `{"text":"增量"}` | 追加回答正文（仅核算智能体） |
| `done` | `{"reply","tools","model","message_id","trace_count"}` | 收尾：移除光标、显示工具汇总、加载“计算依据”面板 |
| `error` | `{"message":"..."}` | 在当前回答位置显示错误 |

思考面板默认展开、结束后自动折叠并显示耗时，可手动重新展开；工具调用以步骤形式混排在同一时间线中。
前端按 `agent_start/agent_end` 将阶段提示、思考与工具调用归入对应智能体区块，最终答复只取 `text` 事件。
流式接口的校验错误（空消息、超长）同样以 `event:error` 返回，避免 `Accept: text/event-stream`
下无法序列化 JSON 错误体。

### 计算依据（工具调用级追溯）

每次工具调用都会在工具方法内部记录参数、完整结果（超过 8KB 截断并标记）、耗时，
并由编排层配对 `tool_call_id` 与所在智能体阶段，写入 `chat_tool_traces`。
助手消息保存成功后，管理台在答复下方显示可折叠的“计算依据”面板：

- 工具名、结果状态、耗时，以及从结果中提取的金额项数；
- 金额卡片（应发、实发、社保、公积金、税额、合计等）、关联员工、期间与部门；
- 参数与结果的格式化 JSON；结果为批量核算时只提取顶层合计，避免逐人明细刷屏。

面板数据实时来自 `done.message_id`，历史消息则在加载时按需查询；接口如下：

```text
GET /api/chat/sessions/{sessionId}/messages/{messageId}/traces
```

追溯数据只随会话保存，不写入审计日志；查询前校验会话归属，跨用户返回 400。

## 多智能体编排

对话不是单 Agent ReAct 循环，而是由 `PayrollChatService` 固定编排的三段流水线：

| 阶段 | 智能体 | 可用工具 | 职责与产出 |
| --- | --- | --- | --- |
| ① 计算 | `PayrollCalculatorAgent` | `list_employees`、`get_employee_profile`、`get_attendance_summary`、`calculate_payroll`、`calculate_department_payroll`、`generate_payslip`、`get_payroll_policy` | 理解诉求、解析员工与期间、调用核算工具，输出“核算对象 / 计算结果 / 风险提示 / 待验证点”的计算记录 |
| ② 验证 | `PayrollValidatorAgent` | `validate_payroll`、`get_payroll_policy`、`get_employee_profile`、`list_employees`、`get_attendance_summary` | 对计算记录中的每个核算对象调用 `validate_payroll`，复核勾稽关系，输出“结论 / 校验明细 / 发现问题 / 复核建议” |
| ③ 核算 | `PayrollAccountantAgent` | 无（只读前两阶段记录） | 汇总计算与验证记录，生成最终 HR 答复；验证不通过时明确“暂不可发薪” |

- **核算对象提取**：编排层从 `calculate_payroll` / `generate_payslip` 的工具参数中提取 `employee_id + period`；
  部门批量核算则按部门展开最多 3 名员工作为抽检对象，保证验证阶段有明确输入。
- **固定顺序 + 超时**：每个阶段 150 秒超时，顺序与降级行为不依赖模型判断。
- **验证降级**：验证智能体异常时不中断整轮对话，核算智能体收到“验证失败：… 本轮未完成独立验证”，
  并被要求不得声称已通过校验。
- **无计算对象时跳过验证**：纯政策 / 考勤咨询不触发 `validate_payroll`，验证区块显示“本轮无需校验”。
- **跨轮上下文压缩**：每个阶段开始前仍清空智能体内部历史；编排层额外维护“摘要 + 最近 4 轮用户问题”，
  超过 6 轮或 3000 字时调用同一模型压缩较早轮次。摘要只保留员工/部门/期间与指代关系，
  金额、比例、银行卡号、身份证号在代码层按分句强制剔除；注入时明确要求所有数据重新调用工具。
  摘要与已压缩轮数持久化在 `chat_sessions`，重启后自动恢复。
  管理台新增/修改员工后，下一轮对话仍会重新调用工具读取最新数据。
- **查询类问题快速返回**：计算记录中没有工资核算对象时（员工清单、档案、政策、考勤），直接返回计算记录，
  不再交给核算智能体改写，避免小模型编造工资合计或“验证不通过”。
- **向量记忆（可选）**：开启后把用户问题与压缩摘要写入 Elasticsearch `payroll-chat-memory`，
  默认只召回**当前会话**的历史片段，避免不同角色/话题的消息串线；
  `PAYROLL_MEMORY_CROSS_SESSION=true` 才按 `user_id` 跨会话召回，帮助解析“他”“上次那个部门”等指代。
  助手答复不入索引；金额与比例在写入前替换为占位符，注入前再清洗一次，所有数值仍必须重新调用工具获取。
  默认关闭，开启步骤：

  ```bash
  ollama pull nomic-embed-text
  PAYROLL_MEMORY_ENABLED=true java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-runtime.jar
  ```

  管理员可调用 `POST /api/admin/vector/rebuild` 清空索引并按 H2 历史重建。
  设计、配置与实测记录见 [`docs/vector-memory-spec.md`](docs/vector-memory-spec.md)。
- **MCP 工具（可选）**：通过 `PAYROLL_MCP_SERVERS` 注册外部 MCP 服务，只挂载到计算智能体。
  支持 `stdio`、`sse`、`http` 三种传输；`allowedTools` 限定暴露给模型的工具，
  与本地工具同名时保留本地实现，单个服务连接失败只跳过该服务，不影响本地核算。
  示例：

  ```bash
  PAYROLL_MCP_SERVERS='[
    {"name":"hr","transport":"sse","url":"http://localhost:9000/sse",
     "allowedTools":["get_employee_profile","get_attendance_summary"],
     "readOnlyTools":["get_employee_profile"],"timeoutSeconds":15}
  ]' java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-runtime.jar
  ```

- **路由透明化与写操作确认**：每条助手消息都记录实际回答链路（`PIPELINE`/`AGENT`/`FLOW`/
  `GRAPH`/`TEAM_ACTIVE`/`ACTION`），前端显示 chip，展开可看回退原因、模型与定义签名；
  历史消息刷新后仍可查看，升级前的旧消息显示“未记录路由”。
  开启 `PAYROLL_ACTION_CONFIRM_ENABLED=true` 后，模型调用**写工具**时本轮立即停止后续工具执行，
  只生成一张会话内确认卡片；提案与发起人、消息绑定，仅本人可在有效期内确认或取消，
  确认后由服务端执行并追加一条“操作结果”消息，不触发新的模型调用。

  | 接口 | 说明 |
  | --- | --- |
  | `GET /api/chat/sessions/{id}/messages/{messageId}/routing` | 查询该条助手消息的路由快照，越权返回 404 |
  | `GET /api/chat/actions/{proposalId}` | 查看提案当前状态，过期时原子落 `EXPIRED` 并返回 410 |
  | `POST /api/chat/actions/{proposalId}/confirm` | body `{"decision":"CONFIRM"\|"CANCEL","idempotencyKey":"可选"}`；重复确认只返回既有终态 |

  提案状态：`PENDING → CONFIRMED → EXECUTED/FAILED`，或 `PENDING → CANCELED/EXPIRED`。
  提案不进入 `/api/approval/tasks`，确认也不会绕过批次审批、出账与发送的领域状态机；
  会话角色切换会作废未确认提案，删除会话时提案与工具追溯一并清理，审计保留 180 天。
  写工具分类优先级为：本地 `safety-overrides` > 服务端 `readOnlyHint` > 默认“写”。
  内置只读工具直通并写 trace；MCP 工具未声明 `readOnlyHint` 时按写处理，
  可在服务端补注解，或用 `readOnlyTools` 放行、用 `safety-overrides` 强制改判：

  ```yaml
  payroll:
    mcp:
      trust-read-only-hints: true   # false 时所有 MCP 工具一律确认
    tools:
      safety-overrides:
        search_doc: MUTATING        # READ_ONLY / MUTATING，优先级最高
  ```

  提案生成、确认、执行、取消、过期以及分类覆盖都会写入 `audit_log`
  （`chat.action.propose/confirm/execute/cancel/expire`、`chat.action.safety.override`）。
  设计细节与决策记录见 [`docs/routing-action-confirm-spec.md`](docs/routing-action-confirm-spec.md)。

- **角色 Skill 外置（可选）**：三个角色的提示词已拆成 AgentScope 动态技能，系统提示只保留
  金额安全规则与“第一步加载技能”的指令，模型通过 `load_skill_through_path` 按需读取角色说明。
  默认从本机 Nacos 读取；可用 `PAYROLL_NACOS_ENABLED=false` 回到纯代码内置版本，
  或用 `PAYROLL_NACOS_SERVER` 指向其他实例。设置后按 `payroll-calculator.md`、
  `payroll-validator.md`、`payroll-accountant.md`（group 默认 `payroll-agent`）从 Nacos 读取，
  缺失或连接失败时逐个技能回退内置版本。自定义技能同样以 `<技能ID>.md` 存放在该 group，
  由管理台“技能管理”维护并自动纳入技能仓库发现。发布模板与命令见
  [`docs/nacos-skills/README.md`](docs/nacos-skills/README.md)。
  业务工具挂在未激活的技能工具组中，技能加载成功后才会出现在模型工具列表，
  因此“先加载技能再核算”由工具可见性强制，而不是只靠提示词约束。
  运行期所有智能体（三角色、上下文压缩器、意图提取、流程节点、自定义技能智能体）
  统一由 `HarnessAgent` 承载；harness 默认的文件系统、Shell、记忆、会话持久化、
  子智能体、压缩与工具结果驱逐等能力在构建时全部关闭，只保留技能加载与中间件链路。
  技能加载后由 `SkillToolGroupActivationMiddleware` 激活对应业务工具组。

  ```bash
  PAYROLL_NACOS_SERVER=http://127.0.0.1:8848 \
  PAYROLL_NACOS_GROUP=payroll-agent \
  java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-runtime.jar
  ```

- **运行期实例复用**：技能仓库（含 Nacos HTTP 客户端）与模型按进程级单例复用；智能体按
  “编排模式 + 定义签名（`id/name/description/skillIds`）+ 模型”缓存，同一模式只构建一次。
  管理员修改智能体定义后签名变化，下一轮自动构建新实例，旧实例继续服务其它在途会话。
  会话历史与系统提示中的技能清单由 `RuntimeContext`（`userId + sessionId`）隔离；
  工具追溯记录器放入调用上下文，流程并行节点各持一份，避免共享实例后金额串线。
  技能内容修改无需重建智能体：harness 每次调用按 `RuntimeContext` 解析技能目录，
  内容签名变化后自动刷新。重置会话只清理本会话上下文，不关闭进程级共享实例。
  缓存与迁移设计见 [`docs/agent-runtime-cache-spec.md`](docs/agent-runtime-cache-spec.md)
  与 [`docs/harness-migration-spec.md`](docs/harness-migration-spec.md)。

对长会话，进程内缓存的 `PayrollChat` 也设有上限（默认 200）与空闲淘汰（默认 60 分钟）：
超出后按最久未使用关闭，正在生成回答的会话不会被淘汰；未命中时从数据库消息与摘要重建。
会话互斥由 Redis 锁 `payroll:chat:lock:<sessionId>` 保证，多实例部署下同一会话只会在一处执行。

### 控制台日志

开发排查以 `[多智能体]` 为前缀，输出到 stderr（`logback.xml` 配置），与 CLI 结果分开：

```
[多智能体] 初始化完成：计算 / 验证 / 核算三个智能体 + 上下文压缩器，模型=ollama:qwen2.5:3b-instruct
[Skill] 生效技能：[payroll-calculator(nacos), payroll-validator(nacos), payroll-accountant(nacos)]
[多智能体] 计算智能体工具集：[...]
[多智能体] 智能体缓存就绪：薪酬计算（pipeline），模式=三段流水线，缓存条目=1，耗时=1ms
[多智能体][calculator] 阶段开始：① 计算智能体：理解诉求并调用核算工具
[多智能体][calculator] 调用工具：calculate_payroll
[多智能体][calculator] 工具结束：calculate_payroll，state=SUCCESS，耗时=42ms，参数={"employee_id":"张伟","period":"2026-10"}，金额={net_pay=21000.50,...}，员工=[EMP001]，期间=2026-10
[多智能体][calculator] 阶段结束：耗时=20631ms，工具=[calculate_payroll]
[多智能体] ① 计算完成：耗时=20631ms，核算对象=1，输出=...
[多智能体][validator] 阶段开始：② 验证智能体：独立复核勾稽关系
[多智能体] ② 验证完成：输出=...
[多智能体][accountant] 阶段开始：③ 核算智能体：汇总计算与验证结论
[上下文] 压缩完成：折叠 3 轮，保留 4 轮，摘要 86 字，耗时=4932ms
[多智能体] 工具追溯汇总：3 次调用，金额类 2 次
[多智能体] 完成：总耗时=36771ms，工具=[calculate_payroll, validate_payroll]，答复长度=242，上下文=摘要6轮+最近4轮
[会话] 回答已保存：sessionId=1，messageId=8，工具追溯=3条
```

- 第二轮起日志出现 `[多智能体] 复用已缓存智能体`（DEBUG）；定义或模型变化时才重新打印
  `智能体缓存就绪`。`缓存条目` 是进程内当前缓存的智能体定义数，用于排查是否按预期复用。
- 阶段输入 / 输出全文为 DEBUG；INFO 只打印截断预览，避免日志过长。
- `[多智能体][阶段] 工具结束` 是追溯核对入口：耗时、金额、员工、期间都来自落库前的同一份快照。
- `[多智能体] 工具追溯汇总` 与 `[会话] 回答已保存` 可确认采集数量与持久化结果。
- 前端历史追溯加载失败只输出 `[计算依据] 加载失败：...`，不影响正文。
- `io.agentscope.core.tool.Toolkit` 的注册日志已降到 WARN，工具集改为每角色一行 INFO。
- Web 侧另有 `[SSE] 对话开始 / 完成 / 失败` 与耗时日志。

完整交接说明（契约、排障、限制）见 [`docs/multi-agent-handoff.md`](docs/multi-agent-handoff.md)。

## 运行追溯、反馈与评分（P0）

动态团队的数据地基已先行落地，当前仍由静态三阶段链路提供服务，默认不改变对话与金额。

- 开关：`PAYROLL_TEAM_MODE=OFF`（默认）保持旧链路；`STATIC` 在一轮对话旁路写入
  1 条团队运行（run）与计算/验证/核算各 1 条节点记录（node），成功、失败、中断都会落库；
  记录失败只写日志，不影响回答。`SHADOW` 额外启用影子组队（见下节），`ACTIVE` 按白名单
  直接执行动态团队（见 P2 小节）。
- 影子组队（P1，`PAYROLL_TEAM_MODE=SHADOW`）：主链路回答落库后按
  `PAYROLL_TEAM_SHADOW_SAMPLE_RATE`（默认 0.15）异步采样。每轮最多两次轻量模型调用
  （意图抽取 + 计划选角）和一次动态 DAG 执行；计划只能从 Top-3 短名单内选人，
  使用固定模板（核算→复核、核算→复核→入账、查询、知识、查询+核算→复核），最多 5 个
  智能体节点。`PlanValidator` 校验 DAG、强制复核、智能体可用性与角色能力等 8 类约束，
  失败先做一次 Top-1 确定性修复，仍失败则记录 `L2` 回退。影子运行只写
  `mode=SHADOW`、`is_primary=false` 的 run 与节点/trace，`baseline_run_id` 指向本轮
  静态运行，不向用户展示、不改变回答或 SSE 事件。
- ACTIVE 灰度（P2，`PAYROLL_TEAM_MODE=ACTIVE`）：仅在未配置全局默认流程、且当前为
  静态三段流水线时尝试动态组队，白名单由 `PAYROLL_TEAM_ACTIVE_USER_IDS` /
  `PAYROLL_TEAM_ACTIVE_SESSION_IDS` 控制（逗号分隔，留空表示全部放行）。计划成功则
  动态团队直接接管本轮回答：ACTIVE run 记为主运行（`is_primary=true`）并绑定消息，
  静态 run 改为 `CANCELLED`；计划或执行失败则记录带 `L0`/`L2`/`L3` 前缀的
  `FALLBACK` run（`fallback_of_run_id` 指向静态 run），用户继续得到标准流程结果。
  审计动作：`team.active.executed` / `team.active.fallback`。写操作与不可归类任务在
  `L0` 直接回退，不进入动态执行。
- 影子报告：`GET /api/admin/team/shadow-report?days=7`（需 `agent:manage`，`days=0`
  取统计窗口）。返回计划率、成功率、`L0`–`L4` 回退分布、模板/任务/选角分布，
  以及相对静态基线（`baseline_run_id`）的成功率与耗时差异。
- 管理台「团队运行」页签（需 `agent:manage`）：展示当前模式与白名单、按天/模式/状态
  筛选近期运行；点击运行查看计划、模板、会话/消息、节点级状态/工具/耗时与回退原因，
  回退运行标记「已回退到标准流程」并给出关联的静态运行。
- 追溯链：`message_id → run_id → node_id → trace_id`。工具追溯新增 `run_id`/`node_id`，
  `GET /api/chat/sessions/{id}/messages/{messageId}/team` 返回主运行、全部运行、节点摘要、
  trace 引用与当前反馈；跨用户访问返回 403。
- 团队面板：助手消息下可展开「团队运行」，显示 `STATIC · 固定链路` / `SHADOW · 影子组队` /
  `ACTIVE · 动态组队` 徽标、节点与智能体、状态、工具/复核/trace 数、耗时，以及组队评分快照；
  `SHADOW`/`ACTIVE` 新回答会自动展开，`OFF` 记录显示“未记录团队运行”。
- 反馈：助手消息下方 👍/👎；差评可选 `WRONG_AMOUNT`/`INCOMPLETE`/`SLOW`/`OFF_TOPIC`/`OTHER`。
  同一用户重复提交覆盖原记录，最多 5 个标签、说明不超过 500 字；仅可对助手消息提交。
- 评分：`GET /api/admin/agent-stats?days=30`（需 `agent:manage`）按窗口聚合。综合分由客观分 O
  （Wilson 成功率下界、工具/复核下界、时延分、稳定性）与反馈分 F 加权，再按样本量向角色先验
  收缩；样本少于 5 标记冷启动，零样本显示先验分而不除零，结果缓存 5 分钟。
- 保留：运行记录默认保留 180 天，启动时软归档超期数据（`deleted_at`）；节点与 trace 保留，
  统计与追溯默认忽略已归档运行。

完整设计、公式与后续阶段拆分见 [`docs/dynamic-agent-team-spec.md`](docs/dynamic-agent-team-spec.md)。

## Excel 导入

员工、考核、考勤三个页签均提供“导入 Excel”和“下载导入模板”。接口接收原始二进制
（≤10MB），支持 `.xlsx/.xls`，单次最多 5000 行，完全空白的行会自动跳过。

| 类型 | 必填列 | 可选列（默认值） |
| --- | --- | --- |
| 员工 | 工号、姓名、部门、基本工资、入职日期 | 岗位（空）、城市（`默认`）、邮箱/银行卡（空）、岗位津贴（0）、公积金比例（0.12）、社保基数（空=按工资）、专项附加扣除（0） |
| 考核 | 工号、期间、考核分数 | 绩效系数（1）、评语（空） |
| 考勤 | 工号、期间 | 事假/病假/旷工/三类加班（0，负数拒绝） |

- 表头允许出现在前 10 行，支持中英文别名与“(元)”等单位后缀；缺少必填列时整份文件拒绝并列出列名。
- 逐行校验、部分成功：报告返回总行数、成功数、失败数，以及每个失败行的 Excel 行号、工号与原因。
- 员工按工号 upsert 并重新启用；考核、考勤按 `工号 + 期间` upsert，考勤导入会覆盖同期间记录。
- 考核与考勤要求员工存在且在职，否则该行报“员工不存在或已停用”。
- 期间支持 `2026-10`、`2026/10`、`202610` 或 Excel 日期单元格；日期支持 Excel 日期与
  `yyyy-MM-dd`/`yyyy/M/d`/`yyyyMMdd`；公积金比例支持 `0.12` 或 `12%`。

## 邮件语义（dry-run）

当前实现使用 `DryRunEmailGateway`：只校验收件人与格式并记录结果，**不会连接 SMTP、不会真实投递**。
每个收件人都会得到一条状态：

| 状态 | 含义 |
| --- | --- |
| `SIMULATED_SUCCESS` | 邮箱合法且唯一，模拟发送成功 |
| `FAILED` | 发送失败，`reason` 说明原因 |

演示数据中 `EMP006` 故意没有邮箱，用于展示失败上报；失败原因包括：

- `未维护邮箱地址，无法发送`
- `邮箱格式不合法：...`
- `邮箱与 EMPxxx 重复，请核对后再发送`

发送结果同时写入 `payroll_details.email_status/email_reason` 与 `email_deliveries` 审计表，
并回写到 Excel 的“邮箱状态”“发送失败原因”两列。

> 接入真实邮件服务时，实现 `EmailGateway` 并替换 `PayrollServices` 中的装配即可，
> 批次状态机、审计表和返回格式无需改动。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PAYROLL_DB_URL` | `jdbc:h2:file:./data/payroll;DB_CLOSE_ON_EXIT=FALSE` | H2 文件库 |
| `PAYROLL_DB_USER` | `sa` | 数据库用户 |
| `PAYROLL_DB_PASSWORD` | 空 | 数据库密码 |
| `PAYROLL_OUTPUT_DIR` | `output` | Excel 输出目录 |
| `PAYROLL_WEB_PORT` | `8080` | 后端（Web 节点）监听端口 |
| `PAYROLL_MODEL` | `ollama:qwen2.5:3b-instruct` | 模型标识；Ollama 用 `ollama:<模型名>`，DashScope 用 `dashscope:<模型名>` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 服务地址 |
| `PAYROLL_OLLAMA_NUM_CTX` | `8192` | Ollama 上下文窗口 |
| `PAYROLL_OLLAMA_NUM_PREDICT` | `1024` | 单轮最大生成 token，防止小模型重复输出导致阶段超时 |
| `PAYROLL_OLLAMA_TEMPERATURE` | `0.1` | 采样温度，核算场景建议保持低温度 |
| `PAYROLL_OLLAMA_THINK` | 自动检测 | `true`/`false`/`low`/`medium`/`high`；未设置时按模型的 thinking 能力自动启用 |
| `PAYROLL_AUTH_ENABLED` | `true` | 是否启用登录与权限；`false` 时所有请求视为内置管理员（仅测试/离线演示） |
| `PAYROLL_ADMIN_PASSWORD` | `admin123` | 首次建库时创建 `admin` 的初始密码，仅在 `app_users` 为空时生效 |
| `PAYROLL_AUDIT_RETENTION_DAYS` | `180` | 审计日志保留天数，启动时清理更早记录 |
| `PAYROLL_TEAM_MODE` | `OFF` | 动态团队开关：`OFF` 保持旧链路；`STATIC` 记录静态运行；`SHADOW` 采样旁路动态组队；`ACTIVE` 白名单内直接执行动态团队 |
| `PAYROLL_TEAM_RETENTION_DAYS` | `180` | 团队运行保留天数，启动时软归档更早记录 |
| `PAYROLL_TEAM_STATS_WINDOW_DAYS` | `30` | 评分榜默认统计窗口（天） |
| `PAYROLL_TEAM_SHADOW_SAMPLE_RATE` | `0.15` | `SHADOW` 模式采样率；设 0 关闭影子执行 |
| `PAYROLL_TEAM_ACTIVE_USER_IDS` | 空 | `ACTIVE` 用户白名单（逗号分隔用户 id）；留空表示全部用户 |
| `PAYROLL_TEAM_ACTIVE_SESSION_IDS` | 空 | `ACTIVE` 会话白名单（逗号分隔会话 id）；留空表示全部会话 |
| `PAYROLL_MCP_SERVERS` | 空 | MCP 服务 JSON 数组；为空不启用。支持 `stdio`/`sse`/`http`、`allowedTools`、`readOnlyTools`、`timeoutSeconds` |
| `PAYROLL_NODE_ROLE` | `all` | `all` 单进程；`web` 只转发与续流；`runtime` 只执行内部轮次 |
| `PAYROLL_NODE_ID` | `local` | 节点标识，写入 `chat_turns.owner_node` |
| `PAYROLL_AGENT_RUNTIME_URLS` | 空 | Web 调用的 runtime 地址（逗号分隔）；为空则对话接口返回“智能体运行期未配置”，不做本机回退 |
| `PAYROLL_AGENT_RUNTIME_TOKEN` | 空 | runtime 内部接口 HMAC 密钥；为空时内部提交不可用 |
| `PAYROLL_SERVER_ADDRESS` | `127.0.0.1` | 监听地址；容器/跨机部署需设为 `0.0.0.0` |
| `PAYROLL_TURN_LEASE_TTL_SECONDS` | `30` | 轮次租约 TTL |
| `PAYROLL_TURN_EVENT_TTL_HOURS` | `24` | 轮次事件流保留时长（续流窗口） |
| `PAYROLL_SEMANTIC_CACHE_ENABLED` | `false` | 语义缓存开关（仅 AGENT 路径、无工具调用） |
| `PAYROLL_ROUTING_ENABLED` | `true` | 是否把本轮路由快照写入 `chat_messages.routing_json`；关闭后 SSE `done` 不带 `routing` |
| `PAYROLL_ACTION_CONFIRM_ENABLED` | `false` | 写操作会话内确认开关；关闭时写工具按旧行为直接执行，仅输出 WARN 便于灰度 |
| `PAYROLL_ACTION_PROPOSAL_TTL_MINUTES` | `15` | 提案有效期（分钟）；过期后卡片失效，确认返回 410 |
| `PAYROLL_MCP_TRUST_READ_ONLY_HINTS` | `true` | 是否采纳 MCP 服务端 `readOnlyHint`；`false` 时所有 MCP 工具都需确认 |
| `PAYROLL_NACOS_SERVER` | `http://127.0.0.1:8848` | Nacos 地址；可带 `/nacos` 上下文路径（自动去除），设为空则关闭外置；支持角色 Skill、Agent、流程与技能管理 |
| `PAYROLL_NACOS_ENABLED` | 有地址则 `true` | 显式关闭/开启 Nacos 技能与 Agent 读取 |
| `PAYROLL_NACOS_GROUP` | `payroll-agent` | 技能与 Agent 配置所在分组 |
| `PAYROLL_NACOS_NAMESPACE` | 空 | 命名空间（Nacos 的 tenant） |
| `PAYROLL_NACOS_USERNAME` / `PAYROLL_NACOS_PASSWORD` | 空 | 开启鉴权的 Nacos 登录账号；Agent 写操作需要配置发布权限 |
| `PAYROLL_NACOS_TIMEOUT_MS` | `2000` | 单次配置读写/登录超时 |
| `PAYROLL_NACOS_SKILL_TTL_SECONDS` | `60` | 技能内容与 Agent 列表的缓存时间；过期后下一次请求重新读取 |
| `DASHSCOPE_API_KEY` | 无 | 仅 `dashscope:` 模型必填 |

启动时会预检 Ollama 服务与模型是否就绪，未启动或未拉取模型会直接给出中文提示。
本地小模型（1B–3B）在长系统提示下可能漏调工具，建议使用 7B 以上模型或在线模型；
核算金额始终来自确定性引擎，模型漏调工具时不会产生错误金额，但会影响回答完整性。
`--extract` 依赖结构化输出能力，Ollama 扩展暂不提供该元数据，建议改用 DashScope 模型执行。

> Windows 控制台如出现中文乱码，请加 `-Dfile.encoding=UTF-8`，或先执行 `chcp 65001`。

### MySQL 与 Redis（部门部署）

默认仍是 H2 文件库；多人共用时用 `SPRING_PROFILES_ACTIVE=dept`（等价于 `mysql,redis`）切换：

- MySQL：Flyway 版本化建表（`db/migration/mysql`）+ HikariCP 连接池，URL 需以 `jdbc:mysql:` 开头；
  目标库必须是空库或已由 Flyway 管理，非空且无 `flyway_schema_history` 的库会直接启动失败，避免误接管业务表。
- Redis：Spring Session 会话共享、登录失败锁定、单用户对话并发配额（超出返回 429）、
  同一会话跨实例互斥（`payroll:chat:lock:<sessionId>`，锁随请求结束释放，实例崩溃后按 TTL 自动过期）。
- 存量数据：`java -jar payroll-agent/target/payroll-agent-cli.jar --migrate-to-mysql [--force]`，执行前必须停止 Web 实例。

常用环境变量：`PAYROLL_DB_URL`、`PAYROLL_DB_USER`、`PAYROLL_DB_PASSWORD`、`PAYROLL_DB_POOL_SIZE`、
`PAYROLL_REDIS_HOST`、`PAYROLL_REDIS_PORT`、`PAYROLL_REDIS_PASSWORD`、
`PAYROLL_LOGIN_MAX_FAILURES`、`PAYROLL_LOGIN_LOCK_MINUTES`、`PAYROLL_CHAT_MAX_CONCURRENT`、
`PAYROLL_CHAT_SESSION_LOCK_MINUTES`、`PAYROLL_CHAT_CACHE_MAX_SESSIONS`、`PAYROLL_CHAT_CACHE_IDLE_MINUTES`、
`PAYROLL_AGENT_CACHE_MAX`、`PAYROLL_AGENT_CACHE_IDLE_MINUTES`。
完整步骤、迁移校验与回退见 [docs/mysql-redis-deploy.md](docs/mysql-redis-deploy.md)。

> 智能体与会话缓存仍是进程内对象：`HarnessAgent`、`PayrollChat` 持有流式回调与运行期状态，
> 不能序列化进 Redis。Redis 只负责跨实例协调，缓存按上限与空闲时间淘汰，未命中时从数据库与
> Nacos 定义重建；因此多实例部署无需共享缓存，单实例内存也不会无限增长。

管理与监控端点：`/actuator/health`、`/actuator/metrics`、`/actuator/prometheus`。

### 多节点（Web / agent-runtime 拆分）

`PAYROLL_NODE_ROLE` 控制节点职责：`all`（单进程调试）、`web`（鉴权、会话 API、SSE 转发与续流，
拒绝 `/internal/*`）、`runtime`（只执行内部轮次）。Web 通过 `PAYROLL_AGENT_RUNTIME_URLS` 把轮次
按 HMAC 签名提交给 runtime，事件写入 Redis Stream，任意 Web 节点都能按 `turn_id + after_seq` 续流；
会话与消息仍在 MySQL，登录态与租约在 Redis。拆分部署下 Web 没有本机回退：runtime 全部不可达时
对话接口返回“智能体运行期不可达”，不会静默改用本机模型。

Docker 三镜像编排见 [docker/docker-compose.yml](docker/docker-compose.yml)：

```bash
mvn -DskipTests package
docker compose -f docker/docker-compose.yml up -d --build
```

镜像与职责：`payroll-frontend:local`（Nginx 托管 Vue3 产物并代理 `/api`）、
`payroll-backend:local`（2 个 Web 节点）、`payroll-runtime:local`（2 个运行期节点）；
另有 MySQL、Redis、Ollama 容器，入口 `http://127.0.0.1:18088`。
Web/runtime 共享 `kb-data` 与 `output-data` 卷，避免多实例各写本地文件。
轮次状态、续流、取消、语义缓存与容器化 Ollama 的细节见
[docs/multi-node-deploy.md](docs/multi-node-deploy.md)。

## 管理台 API

所有接口返回 `{"status":"OK","data":...}` 或 `{"status":"ERROR","message":"..."}`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查、考勤就绪月份、数据库/邮件模式与当前对话模型（公开） |
| POST | `/api/auth/login` | 登录，body `{"username","password"}`，成功后写入会话 Cookie（公开） |
| POST | `/api/auth/logout` | 退出登录并使会话立即失效 |
| GET | `/api/auth/me` | 当前用户、角色、部门与权限集合 |
| POST | `/api/auth/password` | 修改本人密码，body `{"old_password","new_password"}` |
| GET | `/api/employees?include_inactive=true` | 员工列表 |
| POST | `/api/employees` | 新增或更新员工 |
| POST | `/api/employees/{id}/deactivate` | 停用员工 |
| GET | `/api/import/templates/{type}` | 下载导入模板，`type` 为 `employees`/`reviews`/`attendance` |
| POST | `/api/import/{type}` | 上传 Excel 原始字节，返回逐行导入报告 |
| GET | `/api/reviews?period=2026-10` | 考核列表 |
| POST | `/api/reviews` | 新增或更新考核 |
| DELETE | `/api/reviews/{employeeId}/{period}` | 删除考核 |
| GET | `/api/policies` | 城市政策列表 |
| PUT | `/api/policies/{city}` | 保存城市政策 |
| GET | `/api/attendance?period=2026-10` | 月度考勤 |
| PUT | `/api/attendance/{employeeId}/{period}` | 保存考勤 |
| GET | `/api/runs` | 批次历史 |
| POST | `/api/runs/preview` | 创建 DRAFT 批次（期间、范围、微调） |
| GET | `/api/runs/{id}` | 批次详情（逐人结果、失败、邮箱状态、审批轨迹） |
| POST | `/api/runs/{id}/submit-approval` | 提交审批，body 可选 `{"comment":"..."}`（需 `payroll:write`） |
| POST | `/api/runs/{id}/approve` | 审批通过，body 可选 `{"comment":"..."}`（需 `payroll:approve`，不能自批） |
| POST | `/api/runs/{id}/reject` | 驳回，body `{"reason":"..."}`（需 `payroll:approve`，不能自驳） |
| GET | `/api/runs/{id}/approvals` | 审批轨迹（需 `payroll:read`） |
| POST | `/api/runs/{id}/finalize` | 校验审批快照后重算并出账，生成 Excel |
| GET | `/api/approval/flows` | 审批工作流列表（需 `approval:manage`） |
| POST | `/api/approval/flows` | 新建审批工作流，按 `scope_type` 区分 `GLOBAL`/`DEPARTMENT`，`steps` 为有序节点数组 |
| PUT/DELETE | `/api/approval/flows/{id}` | 修改 / 删除审批工作流 |
| GET | `/api/approval/tasks` | 我的待办：当前节点由当前用户角色或账号处理的批次（需 `payroll:approve`） |
| GET | `/api/approval/approvers` | 节点审批人候选项：全部角色与启用用户（需 `approval:manage`） |
| POST | `/api/runs/{id}/send` | 全员 dry-run 发送，需 `{"confirm":true}` |
| GET | `/api/runs/{id}/excel` | 下载批次 Excel |
| POST | `/api/chat` | 管理台对话，body `{"message":"..."}`（≤2000 字符），返回回答与调用工具 |
| POST | `/api/chat/stream` | SSE 流式对话：思考、工具、回答增量与完成事件（见“流式对话”一节） |
| POST | `/api/chat/reset` | 清空旧版单会话上下文 |
| GET | `/api/chat/sessions` | 当前用户的会话列表（按更新时间倒序） |
| POST | `/api/chat/sessions` | 新建会话，body 可选 `{"title":"..."}` |
| PUT | `/api/chat/sessions/{id}` | 重命名会话 |
| DELETE | `/api/chat/sessions/{id}` | 删除会话及其全部消息 |
| GET | `/api/chat/sessions/{id}/messages` | 会话历史消息（含角色、内容、工具与模型） |
| GET | `/api/chat/sessions/{id}/messages/{messageId}/traces` | 单条助手消息的工具调用依据（参数、结果、金额、员工） |
| GET | `/api/chat/sessions/{id}/messages/{messageId}/team` | 团队运行、节点摘要、trace 引用与当前反馈 |
| GET | `/api/chat/sessions/{id}/messages/{messageId}/feedback` | 当前用户对该助手消息的反馈 |
| POST | `/api/chat/sessions/{id}/messages/{messageId}/feedback` | 提交或覆盖反馈，body `{"rating":"UP"\|"DOWN","tags":[...],"comment":"..."}` |
| POST | `/api/chat/sessions/{id}/messages` | 非流式对话，body `{"message":"..."}` |
| POST | `/api/chat/sessions/{id}/reset` | 清空会话消息并重置智能体上下文 |
| POST | `/api/chat/sessions/{id}/stream` | 会话 SSE 流式对话（事件协议见上一节） |
| GET | `/api/admin/permissions` | 权限码与中文名称（需 `role:read`） |
| GET/POST | `/api/admin/users` | 用户列表 / 新建用户（需 `user:manage`） |
| PUT | `/api/admin/users/{id}` | 更新姓名、角色、部门与启用状态 |
| POST | `/api/admin/users/{id}/password` | 重置用户密码 |
| GET/POST | `/api/admin/roles` | 角色列表 / 新建角色，GET 需 `role:read`，写需 `role:manage` |
| PUT/DELETE | `/api/admin/roles/{code}` | 修改 / 删除自定义角色（内置角色拒绝） |
| GET/POST | `/api/admin/departments` | 部门列表 / 新建部门 |
| PUT/DELETE | `/api/admin/departments/{id}` | 修改 / 删除部门（被占用时拒绝删除） |
| GET/POST | `/api/admin/agents` | 智能体列表（支持 `keyword`/`tag`/`skillId`/`status`/`visibility`）/ 新建定义（需 `agent:manage`） |
| GET | `/api/admin/agents/meta` | 存储可写性、Nacos 位置与已有标签集合 |
| GET | `/api/admin/agents/skills` | 可绑定技能清单（名称、描述、来源） |
| PUT/DELETE | `/api/admin/agents/{id}` | 修改 / 删除智能体定义（需 `agent:manage`，Nacos 未启用时拒绝写） |
| GET/POST | `/api/admin/skills` | 技能列表（支持 `keyword`，返回可写性与 Nacos 位置）/ 新建技能（需 `skill:manage`） |
| GET/PUT/DELETE | `/api/admin/skills/{id}` | 查看正文 / 修改 / 删除技能（删除被智能体引用的自定义技能会拒绝） |
| GET/POST | `/api/admin/flows` | 流程列表与激活状态 / 新建流程（需 `flow:manage`） |
| GET | `/api/admin/flows/meta` | 存储可写性、Nacos 位置与可选智能体 |
| PUT/DELETE | `/api/admin/flows/{id}` | 更新 / 删除流程（默认流程不可删） |
| PUT | `/api/admin/flows/{id}/active` | 设为全局默认流程，下一轮对话生效 |
| GET | `/api/admin/agent-stats?days=30` | 智能体评分榜：窗口内样本、综合分、客观/反馈分、时延与稳定性（需 `agent:manage`） |
| GET | `/api/admin/team/config` | 动态团队模式、白名单、保留/统计窗口与影子采样率（需 `agent:manage`） |
| GET | `/api/admin/team/runs?days=7&mode=&status=&limit=100` | 近期团队运行列表与模式/状态计数（需 `agent:manage`） |
| GET | `/api/admin/team/runs/{runId}` | 单次运行的摘要、计划/评分快照与节点记录（需 `agent:manage`） |
| GET | `/api/admin/team/shadow-report?days=7` | 影子对比报告：计划率、回退分布、模板/选角分布与基线差异（需 `agent:manage`） |
| GET | `/api/audit` | 审计日志分页查询，支持 `from`/`to`/`actor`/`action`/`resource_type`/`resource_id`/`result`/`keyword`/`page`/`size`（需 `audit:read`） |
| GET | `/api/audit/export` | 按相同条件导出 CSV（含 BOM，最多 1 万条，需 `audit:read`） |

导入接口直接读取请求体，不依赖 multipart/form-data；Excel 上传上限 10MB，对话消息上限 2000 字符。
错误统一返回 `{"status":"ERROR","message":"..."}`：业务校验与请求体格式错误为 400，
状态冲突（审批后数据变化、并发状态变更）为 409，未知路径为 404，未预期异常为 500 并写入服务端日志。
`/api/chat/stream` 的校验与运行错误以 SSE `event:error` 返回（HTTP 200），便于前端统一处理。

管理台默认绑定 `127.0.0.1` 并启用登录鉴权：每个 `/api/**` 请求都会校验会话与权限码，
会话数据按 `user_id` 过滤，会话与消息的读写都会校验归属。所有写请求（POST/PUT/DELETE）
与登录/退出/改密都会写入 `audit_log`，业务写操作附带变更前后快照；密码、银行卡、证件号、
令牌等字段统一脱敏为 `***`。若需多人远程访问，仍建议放在 HTTPS 反向代理之后，
并补充行级数据权限。

### 审计日志

- 覆盖范围：`/api/**` 的 POST/PUT/DELETE，以及登录成功/失败、退出、修改密码。
- 显式事件：员工、考核、考勤、城市政策、批次审批（提交/通过/驳回）、批次出账/发送、
  审批链增删改、智能体定义增删改、技能定义增删改、Excel 导入、用户/角色/部门、向量重建，
  记录 `before_json`、`after_json`、`actor`、`ip`、`user_agent`、`trace_id`。
- 兜底事件：控制器未显式记录时，过滤器补记 `post /api/...` 事件并保存脱敏后的 JSON 请求体；
  401/403 等被拦截的请求同样留痕。
- 查询与导出：需 `audit:read` 权限；CSV 含 UTF-8 BOM，可直接用 Excel 打开。
- 保留策略：默认 180 天，启动时清理过期记录，可用 `PAYROLL_AUDIT_RETENTION_DAYS` 调整。

## 工具清单

| 工具 | 用途 | 只读 |
| --- | --- | --- |
| `list_employees` | 按部门查询员工，定位工号 | 是 |
| `get_employee_profile` | 查询薪酬档案（银行卡脱敏，含城市） | 是 |
| `get_attendance_summary` | 查询月度考勤汇总 | 是 |
| `calculate_payroll` | 单人完整核算（含个税过程与风险提示） | 是 |
| `calculate_department_payroll` | 部门/全员批量核算 | 是 |
| `validate_payroll` | 发薪前勾稽校验 | 是 |
| `generate_payslip` | 生成工资条文本 | 是 |
| `get_payroll_policy` | 查询指定城市或默认城市的税率、社保比例、基数上下限 | 是 |

所有工具只读，计算是纯函数，便于并发调用与回放。工具失败时返回
`{"status":"ERROR","message":"..."}`，让模型继续追问而不是崩溃。

## 核算口径（演示）

城市政策由管理台维护，种子值**仅用于演示，不代表任何地区现行政策**：

| 城市 | 社保基数下限 | 社保基数上限 |
| --- | --- | --- |
| 北京 | 7,500 | 37,500 |
| 上海 | 7,384 | 36,921 |
| 深圳 | 4,492 | 27,501 |
| 杭州 | 4,812 | 24,930 |
| 默认 | 7,500 | 37,500 |

- 个税：累计预扣法，月减除费用 5,000 元，七级超额累进税率（3%–45%）
- 社保基数：`max(申报基数或基本工资+岗位津贴, 城市下限)`，再按城市上限封顶
- 个人：养老 8%、医疗 2% + 3 元、失业 0.5%；公积金按员工档案比例
- 企业：养老 16%、医疗 9.8%、失业 0.5%、工伤 0.2%、生育 0.8%；公积金与个人同比例
- 加班：基本工资 / 21.75 / 8 为时薪，工作日 1.5 倍、休息日 2 倍、法定节假日 3 倍
- 请假：事假、旷工按日薪全额扣减；病假按 60% 计薪（扣减 40%）
- 入职首月：固定工资与津贴按自然日在职天数折算
- 绩效：当月绩效奖金 × 考核系数（未维护考核时按 1.0）
- 微调：出账前可覆盖当月绩效奖金与其他补贴，覆盖值为最终值
- 精度：所有金额 `BigDecimal` + `HALF_UP` 保留两位小数，入口统一在 `Money`

个税公式：

```
累计应纳税所得额 = 累计计税收入 - 累计减除费用 - 累计个人社保公积金 - 累计专项附加扣除
本月预扣税额     = 累计应纳税额（按档位） - 已预扣税额
```

## 演示数据

首次建库时从 `src/main/resources/data/` 导入：

- `employees.json`：6 名员工，覆盖研发/销售/生产/财务/HR 与北京/上海/深圳/杭州；
  `EMP006` 邮箱为空，用于演示发送失败上报
- `attendance-2026-10.json`：当月考勤（事假、病假、旷工、三类加班）
- `variable-pay.json`：绩效奖金与其他补贴
- 考核：为 2026-10 生成 6 条考核记录，初始系数均为 1.0000

历史月份由导入过程按全勤补齐，用于演示累计预扣的跨月递进。
**这是数据构造，不是引擎行为**：生产环境任一月考勤缺失，引擎都会拒绝核算。

2026-10 全员核定结果（与仅 JSON 仓库时一致）：

| 指标 | 金额 |
| --- | --- |
| 应发合计 | 175,280.46 |
| 扣款合计 | 46,533.24 |
| 实发合计 | 128,747.22 |
| 企业成本合计 | 227,447.06 |
| EMP001 张伟实发 | 27,661.39 |

## 关键设计决策

1. **金额与语言解耦**：系统提示词明确禁止模型心算；工具结果是唯一事实来源。
2. **出账即快照**：出账时服务端按微调快照重算并固化明细，Excel 与后续发送都基于同一份结果。
3. **缺数据失败关闭**：考勤缺失、期间超过数据就绪月份、入职前月份、城市政策缺失且无 `默认` 兜底都直接拒绝，不猜数据。
4. **累计预扣可复算**：每次核算从当年 1 月（或入职月）顺序递推税态，同一输入必得同一结果。
5. **批量故障隔离**：单个员工核算失败进入 `failures`，不阻断整批预览；但存在失败时拒绝出账。
6. **精度集中治理**：日薪/时薪保留 10 位中间精度，展示与入账统一两位小数。
7. **校验独立于计算**：`validate_payroll` 检查勾稽关系，计算正确不等于可以发薪。
8. **发送需显式确认 + 幂等**：`confirm=true` 才能发送，`SENT` 后再次发送被拒绝。
9. **敏感数据最小暴露**：工具只返回脱敏银行卡；生产环境应叠加数据权限与审计。
10. **多智能体确定性编排**：三个阶段由 `PayrollChatService` 固定顺序执行，不做 LLM 路由，
    保证“先算、再验、后答复”的顺序、超时与可审计性；验证失败时降级并明确告知未完成独立验证。
11. **按角色裁剪工具集**：计算智能体不能调用 `validate_payroll`，验证智能体不能调用核算工具，
    从工具层面约束职责，降低越权与自证风险。
12. **审批绑定金额快照**：提交审批时对期间、范围与逐人明细取 SHA-256；审批与出账都重新计算并比对，
    任何考勤、考核、政策或微调变化都会触发 409，避免“审批的是 A、发放的是 B”。

## 测试

```bash
mvn test
```

共 294 个测试，覆盖：

- 个税档位边界连续性（36,000 / 144,000 / 300,000 / 960,000）
- 社保基数上下限封顶，以及不同城市政策差异
- 事假 + 加班 + 社保 + 累计个税的全链路金额
- 入职首月自然日折算、跨月累计税额单调递增
- 批量合计等于逐人之和，考勤未就绪、入职前月份拒绝核算
- H2 仓库：种子数据、员工保存/停用、考核系数进入奖金、城市政策封顶
- 批次服务：预览合计、部门范围、微调、出账生成 Excel、发送需确认、
  逐人成功/失败原因、重复发送拒绝、失败员工阻断出账、未审批批次拒绝出账
- 审批状态机：提交/通过/驳回/重新提交、提交人自批与自驳被拒绝、审批轨迹与金额指纹、
  审批前明细变化检测、审批后考勤变化导致出账冲突、部门审批链优先、无匹配流程报错
- 审批链配置：默认全局流程、同范围唯一生效、金额区间过滤、名称/范围/部门/区间校验
- 多级审批工作流：节点按顺序推进、中间节点记录“节点通过”、末节点通过才转为 `APPROVED`、
  待办按角色/用户过滤、非当前节点审批人被拒绝、提交时冻结节点快照（后续修改配置不影响在途批次）、
  节点金额区间过滤与无匹配节点报错、节点数量/审批人/金额区间校验
- 审批 HTTP 权限：HR 提交、管理员审批与出账、HR 不能管理审批链、只读用户不能提交、
  驳回原因必填、审批轨迹查询、待办与节点审批人接口
- Excel 导入：员工部分成功与失败行号、考核系数/非法员工、考勤覆盖与负数拒绝、
  缺必填列拒绝、模板表头与填写说明
- 管理台对话与导入：回答与工具列表、空消息 400、重置、HTTP 上传 Excel、模板下载、未知类型 400
- SSE 流式对话：多智能体 `agent_start/agent_text/agent_end`、思考/阶段/工具/文本/完成事件、
  阶段顺序、SSE 错误事件、空消息与超长消息处理
- Ollama 思考适配器：request 携带 think 与工具、thinking 增量转为思考块、工具调用参数解析
- Spring Boot 管理台（随机端口真实 HTTP）：静态页、员工、预览、出账、dry-run 发送、Excel 下载、
  批次历史、统一错误结构与 404 处理
- 登录与权限：未登录 401、首页跳转登录页、管理员建用户、只读角色可查看但写入返回 403
- 多用户会话：不同用户的会话列表与消息互不可见、同一用户多窗口历史隔离、越权访问会话被拒绝
- 工具追溯：金额/员工/期间提取、批量只取顶层合计、同名工具按调用顺序配对、
  超 8KB 结果截断但金额仍从完整结果提取、消息与追溯同事务落库、跨会话与跨用户隔离、
  SSE `tool_result`/`done` 字段与依据查询接口
- Agent 管理：Nacos 缺失/损坏时回退内置智能体、保存/更新/删除写入假 Nacos、
  TTL 缓存、未启用只读与写失败提示；控制器过滤、字段归一化、枚举/长度/技能校验、
  审计快照；HTTP 权限（未登录 401、HR 403）、camelCase 序列化与错误响应
- 动态技能与角色隔离：技能仓库视图只暴露绑定技能、自定义技能提示词不含薪酬口径、
  自定义技能不继承薪酬工具白名单、动态加载技能后业务工具按组激活；绑定技能缺失或为空时
  直接报错，不回退薪酬计算技能
- 角色串线防护：默认流程已配置但不可解析时抛出错误而非回退薪酬链路；Nacos 读取抖动时
  沿用上次成功加载的智能体与流程；会话绑定 `persona_key`，切换角色清空上一角色上下文；
  向量召回默认按会话隔离，显式开启后才按用户跨会话召回
- 技能管理：内置技能列表与 Nacos 覆盖标记、创建自定义技能写入 frontmatter 并按列表发现、
  内置技能详情去除 frontmatter、覆盖后删除回退内置、ID/重复/正文/长度校验、TTL 缓存与未启用只读；
  控制器关键字过滤、创建/修改/删除审计（不含正文）、被智能体引用时拒绝删除、内置覆盖可删除；
  Nacos 搜索列表解析与技能仓库发现，列表失败回退内置；服务地址带 `/nacos` 时归一化避免重复前缀
- 技能仓库容错：列表接口抖动时沿用上次成功快照、单个技能拉取异常时沿用上次成功内容，
  Nacos 瞬断不再让自定义技能消失 60 秒；首次失败仍回退纯内置清单
- 动态团队 P1：意图规则分类与写操作拦截、内置/推断能力画像、候选 Top-3 与零样本角色先验、
  模板计划校验（复核顺序、环、重复智能体、私有/下架）、Top-1 去重选角与回退、计划编译为流程、
  上游消息渲染与金额只取工具追溯、SHADOW L0/L2/L3 回退落库、影子报告聚合与基线差异、
  影子 trace 按 `run_id` 读取且不污染消息依据
- 动态团队 P2：ACTIVE 白名单（用户/会话）与默认流程优先、同步动态执行与静态主运行切换、
  `L0`/`L2`/`L3` 回退记录与 `fallback_of_run_id` 关联、`team.active.executed`/`team.active.fallback`
  审计、管理台「团队运行」只读页签与 `/api/admin/team/*` 查询接口
- 工具结构化成功/错误返回、工资条输出、Toolkit 反射注册与 schema 生成

既有管理台测试通过 `payroll.auth.enabled=false` 跳过登录，专注业务回归；
`PayrollAuthWebTest` 使用独立内存库与真实 BCrypt 登录验证认证、权限与会话隔离。

## 生产化清单

- 用 HR 主数据、考勤、绩效服务替换 H2 仓库，并做行级数据权限
- 政策参数（税率、基数、比例、生效日期）迁移到配置中心，按社保年度版本化
- 以 `employee_id + period` 做幂等键，核算结果落库并保留计算快照（已具备雏形）
- 管理台已具备登录、角色、接口级权限与写操作审计；生产化还需补充会话超时策略、行级数据权限与审计日志异地留存
- 接入真实 SMTP / 邮件服务商，增加重试、退信处理与发送配额
- 审批流已支持可配置多级工作流、按角色/用户指定节点审批人、我的待办、自批拦截与金额快照校验；
  生产化还需支持会签/或签、审批人按组织架构解析，并将审计日志对接日志平台、加密敏感字段
- 批量核算分页与缓存，避免大部门一次拉全量
- 如需限制敏感操作，可结合 AgentScope 的 Permission / HITL 能力增加人工确认
- 若要升级到仓库内的 `2.0.3-SNAPSHOT`，修改 `pom.xml` 的 `<agentscope.version>` 即可

## 目录

```
src/main/java/io/agentscope/examples/payroll
├── PayrollApplication.java       # CLI 入口（演示/单人/批量/意图抽取/对话/--web）
├── PayrollWebApplication.java    # Spring Boot 管理台启动类
├── config
│   └── PayrollConfiguration.java # 管理台 Bean 装配与启动信息
├── agent
│   ├── PayrollAgentFactory.java  # 计算/验证/核算三个 HarnessAgent、角色提示词与 Ollama 思考开关
│   ├── HarnessAgentSupport.java  # HarnessAgent 最小构建器与工具集裁剪
│   ├── SkillToolGroupActivationMiddleware.java  # 技能加载后激活业务工具组
│   ├── PayrollTools.java         # 8 个 @Tool
│   └── PayrollIntent.java        # 结构化意图
├── model
│   └── OllamaThinkingModel.java  # Ollama 流式适配：透传 thinking 与工具调用
├── domain                        # 员工、考勤、政策、批次、邮件、核算结果、AgentDefinition、审批节点/待办
├── db
│   ├── PayrollDatabase.java      # H2 连接、建表与首次种子数据
│   ├── H2PayrollRepository.java  # 主数据/考核/政策/考勤仓库
│   ├── H2PayrollRunStore.java    # 批次、明细、失败与邮件审计
│   ├── H2ApprovalStore.java      # 审批工作流、节点、待办、审批状态与轨迹
│   └── H2AuditStore.java         # 审计日志
├── repository                    # 数据访问边界与 JSON 演示实现
├── service
│   ├── PayrollEngine.java        # 唯一金额计算入口
│   ├── PayrollRunService.java    # 预览/微调/出账/发送状态机
│   ├── ApprovalService.java      # 提交/审批/驳回与金额快照校验
│   ├── ExcelExporter.java        # POI 导出
│   ├── ExcelImportService.java   # 员工/考核/考勤导入与模板
│   ├── PayrollChat.java          # 对话接口（便于测试替身）
│   ├── ChatStreamListener.java   # 流式事件回调：智能体/思考/阶段/工具/文本/完成
│   ├── PayrollChatService.java   # 三阶段编排、核算对象提取、超时降级与阶段日志
│   ├── DryRunEmailGateway.java   # dry-run 邮件网关
│   ├── PayrollServices.java      # 组装数据库、引擎、对话与批次服务
│   └── PayslipFormatter.java     # 工资条渲染
├── skill
│   ├── RoleSkills.java           # 三个角色技能的内置提示词
│   ├── SkillRepositories.java    # Nacos + 内置技能仓库组合与逐技能回退
│   ├── NacosSkillRegistry.java   # 技能增删改、内置覆盖与引用校验
│   ├── NacosAgentRegistry.java   # Agent 定义读写、TTL 缓存与内置回退
│   └── NacosConfigClient.java    # Nacos v1 OpenAPI 读取/搜索/发布/删除配置
├── web
│   ├── PayrollWebController.java # REST API（契约与原管理台一致）
│   ├── ApprovalController.java   # 审批链配置接口
│   ├── AgentAdminController.java # Agent 管理接口与校验/审计
│   ├── SkillAdminController.java # 技能管理接口、引用保护与审计
│   ├── ApiExceptionHandler.java  # 统一 400/409/404/500 错误响应
│   └── ApiResponse.java          # {status,data,message} 响应封装
└── support/JsonSupport.java      # snake_case JSON（CLI 与测试）

src/main/resources
├── application.yml               # 端口、Jackson、H2、输出目录
├── static/                       # index.html / app.js / agents.js / skills.js / flows.js / avatars.js / style.css / vendor（Vue 3）
└── data/                         # 演示员工、考勤、变动工资
```
