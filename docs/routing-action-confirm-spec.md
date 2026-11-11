# 路由透明化与写操作确认规格

> 状态：已定稿（待实施）。§8 决策点 D1–D7 均已确认（D1 路由存列、D2 直接报错、D3 采纳
> `readOnlyHint` 未声明视为写、D4 终止写操作链并由服务端收尾、D5 会话内卡片确认、
> D6 只读工具不二次确认、D7 仅发起人确认且提案随会话清理）。
> 本文只做设计，不含代码变更；按 §11 的 P0/P1/P2 拆分实施。

## 1. 背景

协同智能体现在有两个可信性缺口。

**缺口一：看不见路由。** 一轮对话可能走五条链路中的任意一条——三段流水线、单个自定义
智能体、Nacos 流程 DAG、图编排、动态团队 ACTIVE 接管。结论只写在 stderr 日志里
（`[多智能体] 本轮生效角色：…`），界面上只有瞬时的 `phase` / `agent_start` 事件。
更糟的是存在一处宽泛的静默回退：`PayrollChatService.resolveActiveAgent()`
（[PayrollChatService.java:1400](file:///D:/workspace/agentscope-payroll-agent/src/main/java/io/agentscope/examples/payroll/service/PayrollChatService.java#L1400)）
捕获 `RuntimeException` 后只打 WARN 并返回 null，本轮就落回薪酬流水线。经核查，
Nacos 的 I/O 与解析失败都已在 `NacosConfigClient.fetch()` 与 `NacosAgentRegistry.load()`
内部被吞掉（沿用上次快照或内置定义），`effectiveAgent()` 只在「已配置但缺失/已下架」时抛
`PayrollException`——所以这个 catch 实际兜住的是程序缺陷与未知异常，而它的处理方式却是
**更换回答角色**。这与 `resolveActiveFlow()`（:433）「流程不可用即报错」的既定策略不一致，
也与产品此前的决定相悖：默认智能体解析失败必须报错，不能把会话交给薪酬角色。
历史消息重开后也无法复现当时的路由。

**缺口二：写操作没有刹车。** 当前 8 个内置工具全部
`readOnly = true`（[PayrollTools.java:56](file:///D:/workspace/agentscope-payroll-agent/src/main/java/io/agentscope/examples/payroll/agent/PayrollTools.java#L56)
起 8 处），所以今天不会误写。真正的危险面是 MCP：
`McpToolRegistrar.FilteringClient`
（[McpToolRegistrar.java:112](file:///D:/workspace/agentscope-payroll-agent/src/main/java/io/agentscope/examples/payroll/agent/McpToolRegistrar.java#L112)）
只做同名去重，不区分读写，也不读取服务端已有的 `readOnlyHint` 注解，注册进来即模型可调。
一旦接入 HR 写接口、OA 提交、邮件外发，「智能体替员工改数据、给全员发通知」将没有任何人工关口。

产品诉求对应两点：**可解释**（谁答复的、依据什么）与**可控**（改动必须我点头）。

## 2. 目标

1. 每条助手消息绑定一个不可变的路由快照；实时对话、历史回放、读取 API 与 SSE 都能看到。
2. 消除静默回退：智能体/流程解析失败一律报错，不回退流水线；动态团队接管失败时
   必须显式记录来源与原因，并在界面呈现。
3. 工具按「只读直通 / 写需确认」分类；被判为写的调用不直接落库，而是生成提案，
   用户确认后由领域服务执行。
4. 复用现有横切能力：`RequirePermission` + `Permissions`、`AuditService.record(...)`、
   `ToolTraceRecorder`、`SessionLockService`，不新建权限或审计体系。
5. H2 与 MySQL（dept profile）双库同步；无 Redis 时功能完整可用。

## 3. 非目标

- 不改提示词、技能协议、Nacos 流程/智能体定义格式。
- 不做多步 plan 审批、不做人工干预式工作流引擎。
- 不把现有只读查询工具改成「也要点确认」（查一次工资点一次确认不可接受）。
- 不引入分布式事务：提案执行仍是单库事务 + 幂等键。
- 不做路由自动优化、自动重跑、自动改选智能体。

## 4. 现状与接入点

### 4.1 路由

| 位置 | 现状 | 缺口 |
| --- | --- | --- |
| `PayrollChatService.run()` :218、:232–:247 | 先 `resolveActiveFlow()`，再算 `persona`（`flow:<id>` / `agent:<id>` / `pipeline`） | 路由结论只进日志与 `persona`，`Reply` 里没有 path |
| ACTIVE 接管 :250–:284 | `ActiveTeamService.Attempt`，成功即返回 | 成功/回退在界面上不可分辨 |
| 静态链分支 :286–:295 | `flowReply` / `activeAgentReply` / `graphReply` / `legacyReply` 四选一 | 选了哪条没有字段表达 |
| `resolveActiveFlow()` :433 | 流程不可用直接抛（禁止静默回退） | 行为正确但结果不可见 |
| `resolveActiveAgent()` :1400–:1413 | `RuntimeException` → WARN + null → 落回 pipeline | **真实静默回退点** |
| `applyPersona()` :513–:543 | 角色切换清空上下文并发 `onPhase` | 提示是瞬时事件，历史丢失 |
| `PayrollChat.Reply` :44–:49 | reply / tools / model / traces / team | 无路由字段 |
| `ChatSse.onDone()` web/ChatSse.java:110–:123 | `done` 带 message_id、trace_count | 前端不知链路 |
| `ChatSessionManager.complete()` :237 → `H2ChatStore.appendAssistant()` :282 | 消息与 trace 同事务落库 | 消息表无路由列 |
| `PayrollDatabase.java:88`（`chat_messages`）、MySQL `V1__payroll_core.sql:49` | 双库列一致 | 需同版本加列 |
| `ChatSessionController` :98 `/traces`、:107 `/team`、:115 `/feedback` | 已有按消息读取范式 | 缺 `/routing` |

### 4.2 写操作

| 位置 | 现状 |
| --- | --- |
| `PayrollTools` 8 个 `@Tool(readOnly = true)` :56 :99 :134 :167 :186 :236 :265 :293 | 全部只读，无一写库 |
| `McpToolRegistrar.FilteringClient` :112–:165 | 不区分读写；MCP 工具默认「模型可直接调」 |
| `PayrollRunService.sendAll(long runId, boolean confirm)` :191 | 已有 confirm 前置校验先例：非 true 直接拒绝 |
| `PayrollWebController` `/runs/{id}/send` :475 + `SendRequest(boolean confirm)` :570 | 写操作走 REST + 显式确认位 |
| `ApprovalService.submit/approve/reject` :46 :84 :139、`snapshotHash()` :179 | 批次状态机与审批链，与本规格正交但可被提案复用 |
| `AuditService.record(action, entity, entityId, before, after)`，用法见 `AdminController.java:94` | 复用的审计入口（含 `AuditSanitizer` 脱敏） |
| `RequirePermission` + `support/Permissions.java:12–:37` | 复用的权限码 |
| `SessionLockService` | 会话生成互斥；提案确认走普通 REST，不参与该锁 |

## 5. 设计 A：路由透明化

### 5.1 数据模型

`PayrollChat` 内新增嵌套 record，与 `Reply`、`TeamReply`、`StageRun` 并列：

```java
record RoutingDecision(
        String path,               // PIPELINE | AGENT | FLOW | GRAPH | TEAM_ACTIVE
        String persona,            // flow:<id> | agent:<id> | pipeline（沿用 applyPersona 的取值）
        String reason,             // 面向用户的一句中文
        String fromPath,           // 回退来源；未回退为 null
        String fallbackReason,     // 回退原因；未回退为 null
        String model,              // modelId()
        String definitionSignature,// PayrollAgentFactory.definitionSignature() :325
        List<String> agentIds,     // 本轮实际参与的智能体
        long teamRunId,            // 0 表示与动态团队无关
        long elapsedMs,
        List<String> stages)       // 实际执行阶段 key，取自现有 stageRuns（StageRun :90–:101）
```

规则：

- 每轮 `run()` 结束时构造一次，构造后不可变；`Reply` 多一个 `routing()` 分量。
- `reason` / `fallbackReason` 是给人看的一句话，技术细节留在日志——界面保留排查信息，
  但不堆砌（沿用现有「思考过程/工具调用折叠卡片」的取向）。
- `definitionSignature` 已包含技能正文指纹（`skillFingerprint()` :341），因此路由快照天然
  带「当时用的是哪一版技能/定义」，可在模型或技能升级后解释结论为何变化。

### 5.2 采集点（最小侵入，不改任何分支语义）

| 分支 | path | 附加信息 |
| --- | --- | --- |
| `flowReply` :287 | `FLOW` | `agentIds` 取 `ResolvedFlow` 节点智能体 |
| `activeAgentReply` :289 | `AGENT` | `definitionSignature` 取当前 `AgentDefinition` |
| `graphReply` :291 | `GRAPH` | 与 `PIPELINE` 区分，否则影子对比看不出实际执行链路 |
| `legacyReply` :293 | `PIPELINE` | `stages=[calculator, validator, accountant]`（跳过阶段仍按 `StageRun` 记录） |
| ACTIVE 成功 :258–:284 | `TEAM_ACTIVE` | `teamRunId`、`agentIds` 取自 `Attempt`（:405） |
| ACTIVE 失败并继续 | 静态 path | `fromPath=TEAM_ACTIVE`，`fallbackReason` 取 `Attempt.fallbackReason()`（:409） |
| `resolveActiveAgent()` 捕获异常 :1405 | **直接报错**（D2 已定） | 包装为 `PayrollException("读取核算智能体配置失败，已阻止回退薪酬流水线：<原因>")`；本轮不产生助手消息，SSE 走 `error` |
| `applyPersona()` 检测到角色切换 | 当前 path | `reason` 前缀「本轮刚切换角色：<旧> → <新>」 |

只有 `effectiveAgent()` 返回 null（管理员明确未配置）才走流水线，语义与
`resolveActiveFlow()`（:433）一致：**配置了但不可用 = 硬失败**。

> 已知缺口（不随 D2 关闭）：进程冷启动时若 Nacos 不可达，`configuredAgentId()` 读不到配置
> （`loadActiveAgentId` 无快照可沿用），`effectiveAgent()` 返回 null，本轮仍走流水线，
> 且与「确实未配置」不可区分。可选修复：本地持久化最后已知的 `calculationAgentId`，
> 仅在从未成功读取时才视为未配置。列入 P2，需单独决策。

### 5.3 持久化：列 vs 表（D1 已定：存列）

采用 `chat_messages.routing_json`（H2 `CLOB`，MySQL `TEXT`），与 `tools_json` 同构。
理由：

1. 读取模式是「按会话一次拉全部消息」——`H2ChatStore.messages(sessionId)`（:192）是单表
   `SELECT` 后直接映射 `ChatMessage`；独立表需要 JOIN 或 N+1，历史回放每条消息都要拼路由。
2. 路由与消息严格 1:1，且 `agentIds` / `stages` 是列表，本来就要 JSON 序列化；拆表会把
   `stages` 再引出一张子表，属于过度规范化。
3. 写入仍与 trace 同事务（`appendAssistant` :282），不会出现「有消息无路由」的半条记录。
4. 双库迁移成本最低：一列 `ALTER` vs 建表 + 索引 + 第二处写入。
5. 当前没有按 `path` 过滤/聚合的需求。若将来需要路由分布统计，再加一列普通
   `routing_path VARCHAR(16)`（双库同写、可索引）即可，无需把 JSON 迁到独立表。

迁移：H2 在 `PayrollDatabase.java:106`（`persona_key` 列）之后追加
`ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS routing_json CLOB`；MySQL 新增
`V3__chat_routing.sql`。升级前的旧消息 routing 为 NULL：前端显示「该消息未记录路由
（升级前产生）」，**绝不猜测、不回填补默认值**。

### 5.4 契约

- SSE：`done` 事件增加 `routing` 对象（`ChatSse.java:110–:123`），字段 snake_case，
  与现有 `message_id` / `trace_count` 一致。
- 新增 `GET /api/chat/sessions/{sessionId}/messages/{messageId}/routing`
  （紧挨 `ChatSessionController.java:98`），`@RequirePermission(Permissions.CHAT_USE)`，
  按 `userId + sessionId` 过滤，非本人会话返回 404 而非空对象。
- 历史回放：`GET /{sessionId}/messages`（:92）每条助手消息内联 `routing` 摘要
  （path / persona / fallbackReason），避免前端逐条请求。

### 5.5 界面

- 助手气泡底部元信息行加一枚 chip：`流水线` / `单智能体` / `流程` / `动态团队`；
  发生回退时 chip 转警示色并带 `↩`。
- 点击展开折叠卡片，样式复用 `计算依据`（`app.js:2297`），内容：本轮路径与人话原因、
  回退来源与原因、模型、参与智能体、定义签名摘要、阶段与实际执行顺序、耗时。
- 不新增页签，不改变微信风格气泡布局；提案卡片也复用同一折叠卡片外壳（§6.6）。

## 6. 设计 B：写操作确认

### 6.1 工具分类

新增 `agent/ToolSafety`，判定优先级从高到低：**本地覆盖 → 服务端声明 → 默认值**。

- 内置工具：以 `@Tool(readOnly = true)` 注解为唯一判定来源（读注解，不维护硬编码名单），
  `readOnly=true` → `READ_ONLY`。
- MCP 工具（D3 已定）：协议本身已暴露 `McpSchema.Tool.annotations().readOnlyHint()`
  （MCP SDK 0.17.0，`javap` 已确认）。判定规则：
  - `readOnlyHint = true` → `READ_ONLY`，直接执行；
  - `readOnlyHint = false` 或 `null`（服务端未声明）→ `MUTATING`，走确认；
  - `destructiveHint = true` 的写工具 → 提案 `risk_level = IRREVERSIBLE`，确认卡片加强提示。
  这与 MCP 规范自身的默认一致（未声明即按「可能修改」对待），也不会把所有外部检索工具
  都卡在确认上。
- 本地覆盖（最高优先级）：`payroll.tools.safety-overrides.<toolName>` 与
  `PAYROLL_MCP_SERVERS` 条目新增的 `readOnlyTools` 数组可强制改判（需在
  `McpServerConfig` 增加同名字段并扩展 `parseNode`）；**覆盖一律写审计**，用于
  「服务端谎报/漏报」的兜底。分类在 `McpToolRegistrar.listTools()` 阶段计算一次并缓存，
  服务端修改注解需重新注册（重启或重连）才生效。
- 只读工具直通（D6 已定）：`READ_ONLY` 不生成提案、不弹卡片，工具调用照常写
  `chat_tool_traces`。个别只读工具若确需确认（例如按次计费的外部检索），用
  `payroll.tools.safety-overrides.<toolName>=MUTATING` 提升，不新增独立开关。
  注意：二次确认不是权限控制——敏感只读数据（薪资、身份证）的可见性靠 RBAC 入口
  权限约束，不能指望「点一下确认」代替授权。
- 信任边界：`readOnlyHint` 是被管理员配置的外部服务的自述，不是保证。受控部署可设
  `payroll.mcp.trust-read-only-hints=false`，把所有 MCP 工具一律视为写，逐个人工放行。

### 6.2 两阶段：提案 → 确认 → 执行

1. 模型调用被判定为 `MUTATING` 的工具时，注册层拦截实际执行，返回受控结果
   `{"status":"PENDING_CONFIRM","proposal_id":"…","summary":"…","params":{…}}`。
2. **本轮终止规则（D4 已定：终止写操作链，由服务端收尾）**：
   - 一次 `run()` 内出现第一个提案后立即进入收尾：后续任何工具调用一律拒绝并返回同一
     `PENDING_CONFIRM` 结果，不再生成第二个提案；
   - 终稿不由模型续写：`Reply` 正文 = 提案前已流式输出的正文 + 服务端模板
     「已生成待确认操作：<summary>（影响：<对象/金额>）。请在下方卡片确认后执行，
     未确认前不会做任何改动。」；
   - 多阶段编排（流程/图/团队）中，提案之后的阶段不得再执行工具，收尾文案统一由
     `PayrollChatService` 组装；
   - 实现不依赖框架中止 API：core 2.0.1 的 `Toolkit` / `ToolExecutor` 只有
     `registerAgentTool` / `callTool` 等公开面、无 pre-call 拦截器（已 `javap` 核对），
     因此采用「工具包装器 + round flag + 终稿替换」，不依赖异常中断的框架语义；
   - 确认执行成功后，由服务端追加一条「操作结果」消息并更新卡片状态，**不触发新一轮
     模型调用**，避免模型二次解释或幻觉；
   - `Reply` 新增 `proposals` 分量（缺省 `List.of()`），与 `routing` 同层；
     `ChatSessionManager.complete`、`done` 事件与 `/messages` 回放都携带提案摘要。
3. `ActionProposalService` 落一条提案：`proposal_id`（UUID）、发起人 `user_id`、
   `session_id` / `message_id` / `agent_path`（关联 §5，使提案本身可追溯）、`tool_name`、
   `params_json`、`summary`、`risk_level`（`AMOUNT` / `IRREVERSIBLE` / `NORMAL`）、
   `safety_source`（`HINT_READ_ONLY` / `DEFAULT_MUTATING` / `OVERRIDE`，供审计核对分类依据）、
   `status`、`idempotency_key`、`snapshot_hash`、`expires_at`。
4. SSE 新事件 `action_proposal`，payload 为提案摘要 + 参数明细 + 风险提示。
5. `POST /api/chat/actions/{proposalId}/confirm`，body `{decision: "CONFIRM" | "CANCEL",
   idempotency_key}`，走 `@RequirePermission`（映射见 §6.4）。
6. 服务端事务内做条件更新 `WHERE proposal_id = ? AND status = 'PENDING'` →
   `CONFIRMED`；只有真正发生更新（affected rows = 1）的那一次才继续执行，
   因此双标签页 / 重复点击天然幂等，无需引入新锁。已终态的提案返回当前状态，不报错。
7. 归属与权限校验在条件更新**之前**：**确认人必须是发起人本人**（D7 已定），且当前仍持有
   该提案的权限码；不满足则 403，提案保持 `PENDING`、不进入 `CONFIRMED`。校验通过后
   `ActionExecutor` 执行并写 `EXECUTED` / `FAILED` + 回执摘要；他人查询/确认按 `/messages`
   的归属语义返回 404。
8. 过期：`expires_at` 默认 15 分钟（`PAYROLL_ACTION_PROPOSAL_TTL_MINUTES`）。过期在读取与
   确认时判定并落 `EXPIRED`，不加定时器；过期后确认返回 410 + 中文提示。
9. 生命周期护栏：会话重置（`PayrollAgentFactory.clearSession` :203）或角色切换
   （`applyPersona` :513）时，该会话下 `PENDING` 提案一律置 `CANCELED`，避免「上一角色的
   提案被下一角色执行」；会话删除时提案与消息、`chat_tool_traces`、`agent_run_records`、
   `chat_feedback` 一并物理删除（沿用 `H2ChatStore` 现有清理路径 :165–:178、:559–:604），
   不设独立的 TTL 删除任务——提案的寿命就是消息的寿命。

### 6.3 执行不由工具写库

`ActionExecutor` 是按工具名派发的接口，实现只调用既有领域服务：

- 薪酬批次 → `PayrollRunService.preview/finalize/sendAll`；提案确认正好满足
  `sendAll(runId, true)`（:191）已有的确认语义，不再新增确认位。
- 审批 → `ApprovalService.submit/approve/reject`。
- 外部写（MCP）→ 调用对应 `McpClientWrapper`，参数原样展示、结果摘要入库。

**禁止**：新增一个可拼接 SQL 或任意调用后端方法的通用「写工具」。

### 6.4 权限、审计与追溯

- 提案记录所需权限码（由 `ActionExecutor` 映射，落在 `Permissions.java:12–:37` 内），
  确认时校验**发起人本人**的当前权限（D7 已定：确认人只能是发起人，不能转交他人）。
  权限不足 → 403，提案保持 `PENDING`，状态不变；`confirmed_by` 因此恒等于 `user_id`，
  保留该列仅用于审计核对。
- 四步审计：`chat.action.propose` / `confirm` / `execute` / `cancel`，
  经 `AuditService.record(...)`，参数过 `AuditSanitizer`（身份证、薪资明细脱敏）。
- 保留期分两层：提案随消息走，不设独立过期删除任务，`EXPIRED` 只是状态；会话删除时提案
  与消息、trace 一起清理。审计记录独立保留，默认 180 天
  （`payroll.audit.retention-days`，[application.yml:63](file:///D:/workspace/agentscope-payroll-agent/src/main/resources/application.yml#L63)，
  由 `AuditService.purgeExpired()` :121 执行），因此「会话删了，审计仍可查」。
- 跨人确认（转交、代理审批）需要一套跨会话的「提案收件箱」，与 D5「会话内卡片为唯一入口」
  冲突，列为后续独立议题，本规格不实现。
- 金额类写操作执行成功后补一条 `chat_tool_traces`（复用 `ToolTraceRecorder.Entry` :21–:30），
  让 `计算依据` 面板能看到这笔变更及其前后金额。

### 6.5 表与双库

`chat_action_proposals`：`proposal_id VARCHAR(36) PRIMARY KEY, user_id, session_id,
message_id, agent_path, tool_name, params_json, summary, risk_level, safety_source, status,
idempotency_key, snapshot_hash, created_at, expires_at, confirmed_by, confirmed_at,
executed_at, result_json, error_message`；唯一索引 `(user_id, idempotency_key)`，
查询索引 `(session_id, status)`。H2 走内联 DDL，MySQL 走 Flyway V3；全部使用
条件 `UPDATE`，不写 upsert，规避 H2/MySQL 方言差异。

### 6.6 界面：提案卡片与结果消息（D5 已定：会话内卡片为唯一入口）

- 助手气泡下追加一张卡片（复用 `计算依据` 的折叠外壳）：操作名称、影响对象与金额、参数
  明细（可展开）、风险级别、过期倒计时、`确认执行` / `取消` 两个按钮。
- 卡片状态与提案状态一一对应：`PENDING` 可操作；`CONFIRMED` / `EXECUTED` 显示结果摘要；
  `EXPIRED` / `CANCELED` / `FAILED` 灰置并显示原因。
- **不跳转审批页，也不把提案写入 `/api/approval/tasks`**。确认是「批准这次助手动作」，
  不是组织审批：现有审批链依赖 `ApprovalService.requireDifferentActor`（不能审自己提交的）
  与 `snapshotHash`（:179）语义，混入会让待办出现自己不能点、或把两类流程混为一谈。
- 领域状态机不被绕过：确认后执行仍走 `PayrollRunService`，例如未审批的批次
  `finalize` 仍会被拒、`sendAll` 仍要求 `FINALIZED` 且 `confirm=true`（:191）。
  卡片只是执行许可，不是审批结论。
- 重新打开会话仍可见：提案已持久化，`/messages` 按 `message_id IN (…)` **批量**带出提案
  状态（一次查询，不新增 N+1），卡片据状态渲染为可操作或终态；不依赖 WebSocket 广播。
- 卡片附「查看审计」链接，跳转审计页按 `proposalId` 过滤（只读留档），审批页不承担入口。
- 多标签页：任一标签页确认后，其余标签页在下次加载时看到终态。
- 执行成功后追加的「操作结果」消息与普通助手消息同构（带 `routing` 摘要），
  可被反馈与追溯覆盖。

## 7. 配置与开关

| 配置 | 默认 | 作用 |
| --- | --- | --- |
| `payroll.routing.enabled` | `true` | 关闭后不写 `routing_json`、`done` 不带 `routing` |
| `payroll.chat.action-confirm.enabled` | `false`（灰度） | 关闭时 `MUTATING` 工具按今日行为直接执行，仅 WARN 标记 |
| `payroll.chat.action-confirm.ttl-minutes` | `15` | 提案有效期 |
| `PAYROLL_MCP_SERVERS[*].readOnlyTools` | 空 | 强制把指定 MCP 工具判为只读（覆盖服务端 hint，写审计） |
| `payroll.mcp.trust-read-only-hints` | `true` | 是否采纳服务端 `readOnlyHint`；`false` 时所有 MCP 工具一律视为写 |
| `payroll.tools.safety-overrides.*` | 空 | 单工具分类覆盖（`READ_ONLY` / `MUTATING`），写审计 |
| 环境变量 | `PAYROLL_ROUTING_ENABLED`、`PAYROLL_ACTION_CONFIRM_ENABLED`、`PAYROLL_ACTION_PROPOSAL_TTL_MINUTES` | 与现有命名风格一致 |

## 8. 决策点（D1–D7 均已确认）

| # | 议题 | 选项 | 推荐 |
| --- | --- | --- | --- |
| D1 | 路由存列还是存表 | ~~`chat_messages.routing_json` / 独立表~~ **已确认（2026-11-11）：存列** | 历史回放单表查询、1:1 绑定、双库迁移最小；统计需求出现时再加 `routing_path` 普通列 |
| D2 | `resolveActiveAgent()` 的静默回退 | ~~保留回退但可见 / 改硬报错~~ **已确认（2026-11-11）：直接报错** | 与 `resolveActiveFlow()` 统一：仅「明确未配置」返回 null 走流水线；其余异常一律 `PayrollException`，不回退流水线 |
| D3 | MCP 工具默认分类 | ~~默认「写，需确认」 / 默认「只读」~~ **已确认（2026-11-11）：采纳 `readOnlyHint`，未声明即视为写** | 本地覆盖优先；`trust-read-only-hints=false` 可退回全量确认；覆盖写审计 |
| D4 | 提案后本轮行为 | ~~继续答复并附「待确认」卡片 / 立即终止本轮~~ **已确认（2026-11-11）：终止写操作链，服务端收尾** | 提案后不再执行任何工具，终稿由服务端模板生成（保留提案前正文）；确认执行后追加「操作结果」消息，不再调模型 |
| D5 | 确认入口 | ~~会话内卡片 / 跳转审批页 / 两者~~ **已确认（2026-11-11）：会话内卡片为唯一入口** | 提案不写入审批待办；审批链语义（不同人审批、快照哈希）不变；卡片确认不绕过领域状态机；重开会话按消息批量恢复卡片 |
| D6 | 只读工具是否二次确认 | ~~否 / 是~~ **已确认（2026-11-11）：否** | 直通并写 trace；个别工具用 `safety-overrides` 提为写；权限控制走 RBAC，不靠确认 |
| D7 | 确认人是否可与发起人不同；提案保留多久 | ~~同人 + 30 天 / 允许他人确认（需权限）+ 保留~~ **已确认（2026-11-11）：仅发起人确认；提案随会话清理，审计保留 180 天** | 他人确认需要跨会话提案收件箱，与 D5 冲突，列为后续议题；提案与消息同生命周期，不设独立 TTL 删除；审计由 `payroll.audit.retention-days` 独立保留 |

## 9. 验收标准

1. 流水线问答后 `done` 含 `routing.path=PIPELINE`；刷新页面历史消息显示同一 chip 与卡片。
2. 选择自定义智能体后显示 `AGENT`；停用该智能体（或删除其 Nacos 配置）后本轮直接报错
   「已阻止回退薪酬流水线」，前端显示错误气泡、不产生助手消息，也不把会话交给流水线；
   供应商抛出未知 `RuntimeException` 时同样报错，不再更换角色。
3. ACTIVE 命中显示 `TEAM_ACTIVE` + 成员 + `teamRunId`，与 `chat_sessions` 的团队运行记录一致；
   回退时 `fromPath=TEAM_ACTIVE` 且原因可读。
4. 升级前的旧消息显示「未记录路由（升级前产生）」，不报错、不伪造；历史加载仍是一次
   单表查询（无 N+1），新消息的 routing 与正文一起返回。
5. 触发 MCP 写工具后本轮无数据变更；会话内出现确认卡片；确认后提案 `EXECUTED`，
   审计有 `propose/confirm/execute` 三条，金额类另有 trace。
6. MCP 工具分类符合 D3：`readOnlyHint=true` 直通执行；未声明或 `false` 生成提案；
   `readOnlyTools` / `safety-overrides` 强制改判后行为随之变化，审计有覆盖记录；
   `trust-read-only-hints=false` 时所有 MCP 工具都需确认。只读工具（内置与 hint 为 true）
   均不生成提案、不弹卡片（D6）；用 override 把只读工具提为写后，其调用生成提案。
7. 同一轮（一次提问）内：提案生成后不再执行任何工具、不产生第二个提案；答复正文为
   服务端模板且保留提案前的流式正文，模型无法在卡片后继续声称「已完成」；确认执行后
   追加一条「操作结果」消息，未触发新的模型调用。
8. 刷新或重开会话后，卡片按提案当前状态恢复（历史加载一次批量查询）；提案不出现在
   `/api/approval/tasks`；即使确认，未审批批次的 `finalize`、未出账批次的 `sendAll`
   仍被领域状态机拒绝——卡片不绕过审批。
9. 同一 `proposalId` 连续两次确认：第二次返回既有终态且不重复执行；两个标签页并发
   点击只生效一次。
10. 过期、取消、权限不足三条路径的状态与提示正确；非发起人查询或确认他人提案返回
    403/404 且提案状态不变；发起人确认前失去所需权限同样 403、提案保持 `PENDING`。
11. 会话角色切换后旧提案为 `CANCELED`，不可确认。
12. 会话删除后 `chat_action_proposals` 中对应行与消息、`chat_tool_traces` 一并消失，
    不残留孤儿提案；提案没有独立的 TTL 删除任务（`EXPIRED` 仅表示状态），审计记录
    按 180 天保留、删除会话后仍可查。
13. `mvn test` 全绿（基线 318），H2 与 `SPRING_PROFILES_ACTIVE=dept`（MySQL+Redis）均通过；
    本机 8099 实例完成一次端到端确认。

## 10. 测试计划

- 单测：`RoutingDecisionTest`（五条链路 + ACTIVE 回退的 path/reason 组合）、
  `ActiveAgentFailureTest`（已下架/缺失 → `PayrollException`；供应商抛未知异常 → 同样报错，
  且本轮不落库、不走流水线）、
  `ToolSafetyTest`（`readOnly` 注解解析；MCP `readOnlyHint` 三态：true / false / null；
  `destructiveHint` → `IRREVERSIBLE`；覆盖优先级：本地 override > hint > 默认写；
  `trust-read-only-hints=false` 全量视为写；只读工具经 override 提为写后生成提案）、
  `ActionProposalServiceTest`（PENDING→CONFIRMED→EXECUTED、重复确认幂等、过期、取消、
  角色切换取消、非发起人确认 403/404 且状态不变、发起人确认前失去权限 403 且保持
  PENDING、会话删除级联清理提案而审计保留）、`ActionExecutorDispatchTest`（`sendAll`
  确认语义未被绕过）、
  `ActionRoundTerminationTest`（提案后同类/异类工具均被拒；只产生一个提案；
  终稿为服务端模板且保留提案前正文；确认后追加结果消息而不调模型）。
- Web 层：`ChatSessionRoutingTest`（`/routing` 归属校验、`done` payload）、
  `ChatActionConfirmTest`（`RequirePermission` 403、非发起人确认 403/404、审计写入、
  410 过期、重开会话后卡片状态恢复、提案不出现在 `/api/approval/tasks`、确认后仍触发
  领域状态机校验）。
- 双库：H2 内联 DDL 与 MySQL Flyway V3 列集合一致性检查（比对 `information_schema`）。
- 存储层：`ChatHistoryRoutingTest`（`messages()` 一次查询带出 routing，无 N+1；旧消息为
  NULL 不抛异常）、`routing_json` 往返序列化（列表为空/非空两种）。
- 前端：浏览器实测流水线、单智能体、动态团队、MCP 写四类消息的 chip / 卡片 / 确认流。
- 回归：现有 318 个测试不改语义即通过；`payroll.routing.enabled=false` 时行为回退今日。

## 11. 实施拆分

- **P0 路由透明化**（1 次交付）：`RoutingDecision` + `run()` 采集 + `routing_json` 双库迁移
  + `done` 扩展 + `/routing` + 前端 chip/折叠卡片 + D2 硬失败统一
  （`resolveActiveAgent()` 与 `resolveActiveFlow()` 对齐，删除宽泛的 warn+null 分支）。
- **P1 写操作确认（内部工具）**：`ToolSafety` + `ActionProposal` 表与服务 + `ActionExecutor`
  （只接 `PayrollRunService` / `ApprovalService`）+ D4 的本轮收尾
  （工具包装器、round flag、终稿替换、执行结果消息）+ D5 的会话内卡片与历史批量恢复
  + D7 的归属/权限校验与会话删除级联清理 + 审计与 trace。
- **P2 MCP 写工具纳管**：按 D3 在 `McpToolRegistrar.listTools()` 接入 `ToolSafety`——
  采纳 `readOnlyHint`、未声明视为写、`destructiveHint` 提升风险级别；
  `readOnlyTools` / `safety-overrides` 强制改判并审计；`trust-read-only-hints` 严格模式。

## 12. 风险与回滚

| 风险 | 处置 |
| --- | --- |
| `PayrollChat.Reply` 增分量 | 保留现有便捷构造器，`routing` / `proposals` 缺省为空；调用点集中在 `run()` 与 CLI |
| `ChatMessage` / `appendAssistant` 增参 | `ChatMessage` 保留旧构造器重载（`routing` 缺省 null）；`appendAssistant` 新增重载，旧调用与测试不改 |
| 提案确认与在途对话竞态 | 确认不依赖会话生成锁；终态只由条件 `UPDATE` 决定；过期后卡片失效 |
| 提案混入审批待办，审批人看到「自己不能审」的条目 | 设计上不写入 `/api/approval/tasks`；确认与审批是两条语义不同的路径，领域状态机负责验收 |
| 用户离开会话错过确认窗口 | 提案持久化 + `/messages` 批量恢复卡片；默认 15 分钟 TTL，过期与取消都留审计记录 |
| 仅发起人可确认，发起人不在场时操作卡住 | 过期即作废、可重新发起；跨人确认需要独立「提案收件箱」，与 D5 冲突，列为后续议题，不靠临时放宽归属校验绕过 |
| 模型误判写工具导致大量提案 | `summary` 与 `risk_level` 分级展示；同会话同参数提案合并；配额走 `ChatQuotaService` 口径 |
| D4 终稿替换丢掉模型已生成内容 | 只替换提案之后的部分，提案前已流式输出的正文原样保留；收尾文案为固定模板，内容可预期 |
| 模型同轮连续调用多个写工具 | 提案后统一拒绝后续工具调用，一轮最多一个提案；多步写操作需用户分轮确认 |
| MCP 服务未声明 `readOnlyHint`，检索类工具也被要求确认 | 灰度开关默认关闭；服务端补注解，或本地 `readOnlyTools` 放行；卡片文案说明放行方式 |
| 只读工具直通放大敏感数据暴露面 | 确认不是权限控制：入口仍由 `chat:use` 等 RBAC 权限约束，全部调用写 `chat_tool_traces` 可追溯；按人/部门的数据权限属于独立的安全基线议题，本规格不改变 |
| 服务端把写工具谎报为 `readOnlyHint=true` | 提案记录分类来源（hint / override / default），审计可见；受控部署设 `trust-read-only-hints=false`；`safety-overrides` 可强制改判 |
| 旧客户端不认识 `routing` | 前端对未知 SSE 字段忽略即可（`app.js:3308–:3342` 的 switch 已是默认跳过）；字段只增不改 |
| D2 硬失败让配置异常时对话不可用 | 真实 I/O 故障已被 `NacosConfigClient.fetch()` / `NacosAgentRegistry.load()` 的快照兜住，只有「配置缺失/已下架/程序缺陷」才触发；错误消息直接指向「重新选择或取消默认智能体」，管理员修复后下一轮自动恢复 |
| 冷启动 Nacos 不可达 → `calculationAgentId` 读不到，视为未配置走流水线 | 已知缺口（§5.2 注）；P2 可选：本地持久化最后已知 id，区分「未配置」与「配置未知」 |
| 回滚 | 关闭两个开关即回到今日行为；`routing_json` 与 `chat_action_proposals` 均为增量，不删数据 |

## 13. 交接与可观测

- 日志前缀沿用 `[多智能体]`，新增 `[路由]`（path / from / reason）与
  `[提案]`（proposalId / status / 权限码），INFO 级只打印摘要，参数全文 DEBUG。
- 实现记录写入 `docs/multi-agent-handoff.md` 新章节；`README.md` 增加「路由透明化」
  与「写操作确认」两段及开关说明；`docs/mysql-redis-deploy.md` 补 V3 迁移步骤。
