# payroll-agent

## 职责

数据面（默认 8100）：模型执行、意图路由、流程/团队/图形编排、内部轮次接口。**不暴露任何面向浏览器的 API**，只接受 Web 的签名内部调用（`/internal/turns`）。

一轮对话在这里从「提交的文本」变成「模型调用 + 工具调用 + 事件流」，事件经事件总线回 Web 再转 SSE。

## 包地图

| 包 | 内容 | 说明 |
| --- | --- | --- |
| `io.agentscope.payroll.runtime` | `AgentRuntimeApplication`、`PayrollAgentConfiguration`、`InternalTurnController`、`ToolSafetyProperties`、`TeamProfileModelClient` | 入口与装配；`/internal/turns` 在此 |
| `examples.payroll.service` | `PayrollChatService`、`AgentFlowExecutor`、`GraphPayrollOrchestrator` | 控制面核心；`PayrollChatService` 已 3000+ 行 |
| `examples.payroll.agent` | `PayrollAgentFactory`、`AgentCache`、`PromptLibrary`、`UsageRecordingMiddleware`、`SkillPromptBudget`、`SkillToolGroupActivationMiddleware` | 智能体构建与中间件 |
| `examples.payroll.agent.model` | `OpenAiCompatibleChatModel`、`FailoverModel` | 模型客户端与首包切换 |
| `examples.payroll.router` | `AgentRouter`、`PayrollRouteAdvisor`、`KbRoutePlanner` | 规则 → 语义 → 轻量模型三级路由 |
| `examples.payroll.team` | `ActiveTeamService`、`TeamPlanPipeline`、`TeamPlanGenerator`、`PlanValidator`、`ShadowTeamService`、`MessageAssembler` | 动态团队（SHADOW/ACTIVE） |
| `examples.payroll.devteam` | `DevTaskRunner`、`DevTaskPlanner`、`DevTaskBoard` | 研发场景包（冻结，无入口） |
| `resources/prompts/*.md` | 系统提示词 | 由 `PromptLibrary` 按文件名加载 |

## 执行链路（改之前必须知道）

`InternalTurnController` → 租约/围栏 → `PayrollChatService.run()` → 路由决策 → 执行：

| 路径 | 终点 |
| --- | --- |
| `FLOW` | `AgentFlowExecutor`（DAG：START/AGENT/CONDITION/END，同层并行 ≤8） |
| `AGENT` | 单个 ReAct 智能体 |
| `TEAM_ACTIVE` | `ActiveTeamService` → 计划编译 → 固定拓扑执行 |
| `GRAPH` | `GraphPayrollOrchestrator`（POC，`payroll.orchestrator=graph`） |
| `ACTION` | 写操作确认后的回放 |
| `PIPELINE` | 计算 → 验证 → 核算 三段兜底 |

每条消息落 `ChatRouting` 快照（path/persona/reason/fallback）。

## 改这里的规则

1. **系统提示词只放 `resources/prompts/*.md`**，用 `PromptLibrary` 加载；禁止在 Java 里内联提示词常量（改提示词等同改代码，要评审）。
2. **工具定义不在本模块**：工具在 `payroll-common` 的 `agent` 包，这里只负责挂载与白名单。
3. 新增执行路径必须同时定义「失败回退到哪」，并写进 `docs/agent-orchestration.md`。
4. 写操作必须经过 `ActionGuard`；**`guard == null` 时必须 fail-closed**（`ShadowTeamService` 曾经漏过，见下）。
5. 每个阶段要有超时，且新阶段超时值不得大于单轮总预算。

## 常见改动路径

| 要做的事 | 动哪里 |
| --- | --- |
| 改某个角色的说话方式 | `resources/prompts/<name>.md` |
| 加一个阶段 | `PayrollChatService` + 阶段提示词 + 事件类型（Web 侧 `ChatView` 也要认） |
| 换模型/加连接 | `model/` + `payroll.model.*` 配置；切换逻辑在 `FailoverModel` |
| 调路由判定 | `router/` + `payroll.routing.*`；改行为前先补快照测试 |
| 加团队模板 | `team/TeamTemplate`（拓扑固定，LLM 只能选角，不能改 DAG） |

## 坑

- **单阶段 150s、无单轮总预算**：最坏 3×150s；团队 ACTIVE 没有 deadline，而 SHADOW 有 300s。
- **取消不中断执行**：`cancelInternal` 只改标记，模型仍跑满阶段并继续计费/续租约。
- `ShadowTeamService` 执行写工具时曾经拿不到 `roundGuard` 而放行（fail-open），修 shadow 相关逻辑前先读该类的 guard 传递。
- `AgentCache` 是进程级共享，30 分钟空闲 / 15 分钟压力淘汰；上下文键是 `sessionId:nodeId`，**不含 runId**。
- `FailoverModel` 只在首包 5s 内、且仅对 `IOException/TimeoutException` 切换；5xx/429 不触发。

## 验证

```bash
mvn -o -pl payroll-agent -am -DskipTests package
# 运行期必须用 Run 启动，不要用 Debug（jdwp 会让首次内部轮次挂起）
```

真实链路：发一轮对话 → `llm_usage` 有新行 → `chat_routing` 有快照 → SSE 事件按 `seq` 连续。
