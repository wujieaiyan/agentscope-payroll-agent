# 整体迁移到 agentscope-harness 规格

> 状态：已实现并验证（2026-11-09）。所有运行期智能体已统一由 `HarnessAgent` 承载，
> 业务能力、金额安全、会话隔离与运行期缓存保持不变；全量 241 个测试通过，本机 E2E
> 通过。实施记录见 [`multi-agent-handoff.md`](multi-agent-handoff.md) §27。

## 1. 目标

1. 三段流水线（计算/验证/核算）、上下文压缩器、意图抽取、流程节点、自定义技能智能体
   全部改为 `HarnessAgent`，主代码不再直接构建 `ReActAgent`。
2. 保留现有行为：角色技能必须先 `load_skill_through_path` 才启用业务工具；
   技能目录按 `RuntimeContext` 隔离；会话历史按 `(userId, sessionId)` 隔离。
3. 保留运行期缓存：同一智能体定义只构建一次，共享技能仓库与模型。
4. 关闭 harness 中与本应用无关的默认能力（记忆、压缩、转录、工作区上下文、
   文件/Shell/子智能体/工具配置、默认工作区技能），避免额外 LLM 调用与越权文件访问。
5. 管理台对话、SSE、工具追溯、流程并行、CLI 全部无感切换。

## 2. 非目标

- 不采用 harness 的 memory flush、compaction、transcript、subagent、plan mode、sandbox。
- 不修改管理台前端、Nacos 技能/流程/智能体协议。
- 不引入跨进程状态：`InMemoryAgentStateStore` + 显式 `RuntimeContext` 仍是唯一会话来源。
- 不升级依赖版本：仍使用 `io.agentscope:agentscope-harness:2.0.1`（与 core 2.0.1 对齐）。

## 3. 现状与接入点

| 位置 | 现状 |
| --- | --- |
| `PayrollAgentFactory.createAgent` | 直接 `ReActAgent.builder()` + `DynamicSkillMiddleware` |
| `PayrollAgentFactory.Team` / `NodeAgent` | 字段类型 `ReActAgent` |
| `PayrollChatService.runStage` / `clearHistory` / `summarize` | 参数类型 `ReActAgent` |
| `GraphPayrollOrchestrator` | 阶段执行器与验证器参数 `ReActAgent` |
| `PayrollApplication.runExtraction` | `ReActAgent` + `call(Msg, Class)` |
| `PayrollAgentFactory.toolkit` | 业务工具挂到未激活工具组，依赖 core `SkillBox` 在加载技能后激活 |

`agentscope-harness` 当前只是 test scope，仅 `RuntimeContextIsolationTest` 使用。

## 4. 关键差异与结论

### 4.1 技能加载工具不激活工具组

harness 的 `SkillLoadTool` 只返回 `SKILL.md` / 资源内容，**不会**调用
`Toolkit.updateToolGroups`；core 的 `SkillBox` 才会按 `findSkillToolGroupsByActivateOnSkill`
激活工具组。若原样迁移，`role-tools` 永远处于未激活状态，业务工具不可见。

**方案**：新增 `SkillToolGroupActivationMiddleware`，在 `onActing` 中观察
`ToolResultEndEvent`；当 `load_skill_through_path` 返回 `SUCCESS` 时，用工具调用参数里的
`skillId` 解析 `AgentSkill.getName()`（裸名称），再对该智能体的实时 `Toolkit` 执行
`updateToolGroups(..., true)`。激活发生在本轮 acting 结束、下一轮 reasoning 之前，与 core
`SkillBox` 的时序一致。

激活状态是智能体级、进程级的；每个缓存智能体的 `Toolkit` 只注册本角色工具，
因此多个会话共享同一实例时不存在跨角色越权，和迁移前 core 的行为一致。

### 4.2 技能过滤按裸名称，不再使用完整 skillId

harness 的 `SkillFilter` 用 `AgentSkill.getName()` 匹配（如 `payroll-calculator`），
现有 `SkillRepositories.filterFor` 传入的是 `<name>_<source>`（如 `payroll-calculator_builtin`），
直接迁移会导致技能清单为空。

**方案**：继续使用 `SkillRepositories.scoped(repository, skillIds)` 限定仓库视图，
harness 只枚举该视图内的技能；不再设置 `SkillFilter`。这样既避免完整 ID / 裸名称不匹配，
也保持“自定义智能体不能加载未绑定技能”的边界。

### 4.3 harness 默认能力会改变行为

默认会注册文件系统、Shell、记忆、会话检索、异步等待等工具，并在每次 call 后追加一次
记忆 flush LLM 调用，还会把状态写入 `~/.agentscope/state`、把转录写入工作区。
这些都必须关闭，否则会改变提示词、增加耗时，并引入本应用不需要的文件访问面。

## 5. 设计

### 5.1 依赖

`agentscope-harness` 由 `test` 改为默认 compile scope；版本沿用
`${agentscope.version}=2.0.1`。

### 5.2 最小化构建配置（`HarnessAgentSupport`）

| Builder 配置 | 值 | 原因 |
| --- | --- | --- |
| `workspace(Path)` | `${payroll.harness.workspace:-<tmp>/payroll-harness}/<agentId>` | 隔离工作区，避免污染用户目录 |
| `stateStore` | `new InMemoryAgentStateStore()` | 会话状态只在本进程，配合显式 `RuntimeContext` |
| `agentId` | 智能体名（稳定） | 日志与内部命名空间 |
| `disableFilesystemTools()` | 开 | 薪酬智能体不需要读写文件 |
| `disableShellTool()` | 开 | 无 Shell 执行面 |
| `disableMemoryTools()` / `disableMemoryHooks()` | 开 | 不引入额外记忆与 flush LLM 调用 |
| `disableSessionPersistence()` | 开 | 不写 harness 会话/转录文件 |
| `disableWorkspaceContext()` / `disableAtPathExpansion()` | 开 | 不注入 `AGENTS.md` 等上下文 |
| `disableSubagents()` / `disableDynamicSubagents()` | 开 | 无子智能体 |
| `disableToolsConfig()` | 开 | 不读取 `tools.json`，MCP 仍由现有注册器管理 |
| `disableDefaultWorkspaceSkills()` | 开 | 只使用传入的技能仓库 |
| `disableCompaction()` / `disableToolResultEviction()` | 开 | 压缩由 `ConversationContext` 负责 |
| `middleware(激活中间件)` | 仅在有技能时 | 见 4.1 |

构建后执行 `sanitizeToolkit`：保留构建前工具集（业务 + MCP）与
`load_skill_through_path`，移除 harness 追加的其它工具（如异步等待工具）。
这样即使 harness 版本默认增加了新工具，模型也看不到。

### 5.3 技能与提示词

- 有技能：`skillRepositories(List.of(scoped))`，不设 `SkillFilter`。
- 无技能（summarizer / intent）：不传仓库，并关闭默认工作区技能。
- 系统提示词仍由 `PayrollAgentFactory` 生成；harness 仅在末尾追加
  `<available_skills>` 与技能加载约束。

### 5.4 类型与调用链

- `Team.calculator/validator/accountant/summarizer`、`NodeAgent.agent`、
  `createIntentAgent` 返回类型改为 `HarnessAgent`。
- `PayrollChatService` / `GraphPayrollOrchestrator` 的阶段参数改为 `HarnessAgent`。
- 意图抽取使用 `call(List.of(msg), PayrollIntent.class, ctx)`（harness 无
  `call(Msg, Class)` 重载）。
- `clearContext`、`streamEvents`、`call(String, RuntimeContext)`、`close` 在
  `HarnessAgent` 上签名一致，业务代码无需改写逻辑。

### 5.5 生命周期

- 共享智能体仍由 `AgentCache` 持有，会话结束只 `clearContext`，不 `close`。
- `Team.close()` / `NodeAgent.close()` 改为关闭 `HarnessAgent` 与 MCP 客户端；
  共享实例只由 `resetRuntimeCache()` / 应用关闭统一释放。
- `HarnessAgent.close()` 会清理 delegate 与内部任务；CLI `--extract` 继续 try-with-resources。

## 6. 风险与权衡

| 风险 | 处理 |
| --- | --- |
| 工具组激活是智能体级状态，多个会话共享 | 每个缓存智能体只注册本角色工具，激活不扩大权限；与迁移前一致 |
| harness 2.0.1 与本地源码 2.0.3-SNAPSHOT API 漂移 | 只使用 2.0.1 jar 中已确认存在的 builder 方法；本地 m2 固定 2.0.1 |
| 默认工具/中间件随版本增加 | 构建后按白名单清理工具；中间件通过显式 disable 开关关闭 |
| 工作区创建失败（只读文件系统） | 默认目录可被 `payroll.harness.workspace` 覆盖；构建时创建并给出明确错误 |
| 加载技能失败仍激活工具组 | 仅在 `ToolResultState.SUCCESS` 时激活；失败保持隐藏 |

## 7. 测试与验收

1. ~~新增 harness 端到端测试：脚本模型先调用 `load_skill_through_path`，断言首轮工具集
   不含业务工具、加载成功后包含业务工具。~~ 已由 `HarnessAgentWiringTest` 覆盖。
2. ~~新增 harness 会话隔离测试：同一共享智能体，两个 `RuntimeContext` 的上下文互不可见。~~
   由 `HarnessAgentWiringTest` 的 scoped 仓库用例与 `RuntimeContextIsolationTest` 共同覆盖。
3. ~~现有 `RuntimeContextIsolationTest` 的 core/harness 对照测试保留。~~ 保留。
4. ~~全量 `mvn test` 通过（迁移前 239 个）。~~ 结果：**241 通过，0 失败**。
5. ~~本机 E2E：启动管理台，发送中文核算问题，确认三段阶段、技能加载、业务工具调用、
   金额追溯与 SSE 时间线正常；第二次对话命中智能体缓存。~~ 已通过；张伟 2026-10 的
   gross/net/employer cost 与引擎一致。已知 `qwen2.5:3b` 偶发空补全，见交接文档 §27。

## 8. 分阶段实施

1. 依赖与 `HarnessAgentSupport`、激活中间件。
2. 工厂迁移（Team / NodeAgent / intent）。
3. 对话、图编排、CLI 类型迁移。
4. 测试补齐、全量回归、E2E 与文档。
