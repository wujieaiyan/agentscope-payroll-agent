# 智能体运行期缓存规格

> 状态：已实现（共享技能仓库/模型 + 按定义缓存智能体），全量 239 个测试通过。

## 1. 目标

1. 每轮对话不再重复创建技能仓库、HTTP 客户端、模型和智能体。
2. 同一智能体定义在进程内只构建一次；管理员修改定义后下一轮自动切到新实例。
3. 会话历史与系统提示中的技能清单继续按 `RuntimeContext` 隔离，不因共享实例串话。
4. 流程并行节点互不影响：工具追溯、会话状态、SSE 事件都按节点隔离。

## 2. 非目标

- 不迁移到 `agentscope-harness`（已由 `RuntimeContextIsolationTest` 记录可行性，当前不替换中间件）。
- 不改技能管理、流程编排的管理台交互。
- 不做跨进程缓存；多实例部署各自缓存，Nacos 仍是共享配置源。
- 不引入 TTL 过期重建：技能内容由 core 的 `DynamicSkillMiddleware` 每次调用按内容签名刷新。

## 3. 现状瓶颈（证据）

| 现象 | 位置 |
| --- | --- |
| 每次建 Team / 流程节点都新建 Nacos 仓库与 2 个 `HttpClient` | `PayrollAgentFactory.createTeam` / `createFlowNodeAgent` |
| 每次建 Team 都探测 Ollama `/api/tags` 与 `/api/show` | `PayrollAgentFactory.buildOllamaModel` |
| 流程模式每个节点每轮新建智能体 | `PayrollChatService.executeFlowNode` |
| 仓库 TTL 缓存在流程模式下几乎不生效 | 每轮每节点 new `NacosSkillRepository` |

## 4. 设计

### 4.1 共享实例（进程级）

- `SkillRepositories.shared()`：懒加载单例；`NacosSkillRepository` 已是 `ConcurrentHashMap` + `volatile` 快照，TTL 与 last-known-good 行为不变。管理台列表仍用 `create()` 读取最新配置。
- `PayrollAgentFactory.sharedModel()`：按 `PAYROLL_MODEL` 懒加载模型单例；模型只持有不可变配置与无状态 formatter/transport。
- 两者都提供测试用重置入口。

### 4.2 智能体按定义缓存

- 缓存实现：`AgentCache`（`ConcurrentHashMap` + 首次构建）。
- Team 键：`team|engine@<identityHashCode>|<pipeline|定义签名>|<modelId>`。
- 流程节点键：`node|engine@<identityHashCode>|<定义签名>|<modelId>`。
- 定义签名：`id|name|description|skillIds`；任一变化即生成新实例。
- 引擎身份只用于测试/嵌入场景隔离：生产环境只有一个 `PayrollEngine`，缓存条目强引用引擎，
  identity hash 在其生命周期内不会复用。
- 缓存条目不在会话关闭时销毁；只由测试/应用关闭统一 `clear()`，避免正在进行的其它会话持有已关闭实例。

### 4.3 会话隔离

- `PayrollChatService` 构造一个会话级 `RuntimeContext`：`userId`、`sessionId`。
- `runStage`/`summarize`/`clearContext` 全部显式传该上下文，不再依赖智能体默认上下文。
- 流程节点上下文：`sessionId + ":" + node.id()`，并行节点即使复用同一智能体实例也不会互相串状态；节点结束清理该上下文。

### 4.4 工具状态上下文化

- `PayrollTools` 的 `@Tool` 方法增加 `RuntimeContext` 参数（AgentScope 反射注入，不进入工具 schema）。
- 追溯记录器从 `ctx.get(ToolTraceRecorder.class)` 读取；没有上下文时回退构造器注入（兼容 CLI/直调测试）。
- 每个阶段/流程节点把自己的 `ToolTraceRecorder` 放进上下文，避免共享智能体后金额追溯串线。

## 5. 失效与降级

- 智能体定义变化：签名变化 → 缓存未命中 → 重建。
- 技能内容变化：每次 `onSystemPrompt` 由 core 比较内容签名后重载 SkillBox，无需重建智能体。
- Nacos 抖动：沿用仓库 last-known-good；缓存命中不受影响。
- 构建失败：异常照旧上抛（自定义技能缺失禁止回退薪酬链路）。

## 6. 测试计划

1. `AgentCacheTest`（5）：同 key 只构建一次（含并发首次访问）；不同 key 各自构建；
   `clear()` 关闭 `AutoCloseable` 并可重新构建；定义签名随名称/描述/技能变化。
2. `PayrollToolsContextTest`（4）：上下文携带 recorder 时记录到该 recorder；不同会话互不可见；
   无上下文或上下文未带 recorder 时回退构造器注入。
3. `RuntimeContextIsolationTest`（3，已存在）：共享实例的提示词/历史隔离、跨角色技能加载阻断。
4. 全量 `mvn test`：239 个测试通过，0 失败。

## 7. 分期

1. 共享技能仓库与模型。
2. 工具追溯上下文化 + `PayrollChatService` 会话上下文。
3. 按定义缓存 Team / 流程节点，调整 reset/close 生命周期。
4. 测试、README、交接文档。
