# langgraph4j 图编排 POC 结论（spike 分支）

> 分支：`spike/langgraph4j-orchestrator`（未合并、未提交）
> 日期：2026-11-07

## 1. 目的与边界

验证一个假设：把手写三阶段流水线（计算 → 验证 → 核算）的路由层换成 langgraph4j，
是否能降低维护成本或提升排障能力。

明确不做的事：

- 不迁移 AgentScope 的 `ReActAgent`、Toolkit、消息模型和 SSE 事件；
- 不改金额计算链路，所有金额仍由 `PayrollEngine` 确定性产出；
- 不引入 checkpoint、人工中断、向量库等 POC 范围外的能力。

## 2. 实现方式

| 项 | 位置 |
| --- | --- |
| 图编排 | `GraphPayrollOrchestrator`（节点内复用 `PayrollChatService#runStage` / `#validate`） |
| 开关 | `payroll.orchestrator=legacy\|graph`，默认 `legacy` |
| 装配 | `PayrollConfiguration#payrollChatFactory` 读取属性并传入构造函数 |
| 兼容 | 原 3 个构造器保留，默认走手写编排；SSE 事件与前端无需改动 |

条件边只有一条：计算阶段是否产出 `CalcTarget`。无核算对象（员工清单/政策/考勤查询）
直接结束，与手写编排的跳过逻辑一致；跳过验证节点时仍补发 `agent_start/agent_output/agent_end`，
保证前端时间线不退化。

## 3. 实测对比（Ollama qwen2.5:3b-instruct，本地单机）

同一 App、同一数据库，仅切换 `PAYROLL_ORCHESTRATOR`，重启后各跑一轮：

| 用例 | 手写编排 | 图编排 | 结果 |
| --- | --- | --- | --- |
| “当前有哪些员工？” | 7.7s，2 个 agent 阶段（含验证跳过） | 16.2s，2 个 agent 阶段 | 都正确；两次答复长度不同，单样本不构成性能结论 |
| “算一下张伟2026年10月的工资” | 34.7s，3 个阶段 | 33.6s，3 个阶段 | 金额一致：应发 44,235.63 / 扣款 16,574.24 / 实发 27,661.39，个税 4,915.35 |
| 工具链路 | `calculate_payroll` → `validate_payroll` | 同左 | 一致 |
| 单测 | 49 个 | 52 个（新增 3 个路由用例） | 全绿 |

结论：**功能对齐，性能无可测收益**。计算类请求耗时差异在模型抖动范围内；
查询类样本受答复长度影响，不能作为依据。

## 4. 暴露出的真实成本

1. **状态必须可序列化。** langgraph4j 默认在每次节点输出时用 Java 序列化克隆状态
   （`StdStateSerializer`）。`StageResult` / `CalcTarget` 原本只是进程内值对象，
   接入后必须 `implements Serializable`，否则运行期抛
   `NotSerializableException`，且异常埋在序列化器内部，定位成本高。
   这也意味着每个节点都会多一次状态序列化/反序列化开销。
2. **异常被多层包装。** 节点内抛出的 `PayrollException` 到边界时变成
   `CompletionException → GraphRunnerException → ExecutionException`，
   必须手写 `unwrap` 才能复用原有 `chatFailure` 语义。手写编排是直抛。
3. **代码量上升。** 路由层从约 90 行（`PayrollChatService#legacyReply`）
   增加到约 260 行（`GraphPayrollOrchestrator`），其中大部分是状态读写与异常适配。
4. **可观测性没有增量。** 不启用 checkpoint 时，图的节点快照能力用不上，
   排障仍依赖原有 `[多智能体][stage]` 日志。
5. **每轮重新编译图。** POC 为简化实现按请求构建 `StateGraph`（毫秒级），
   生产化需要缓存，否则多一层可避免的对象分配。

## 5. 决策

**暂不合并。** 在“固定三阶段 + 单条件分支”的场景下，图编排没有消除任何实质复杂度，
反而引入了序列化约束、异常包装和额外依赖（`langgraph4j-core` + `async-generator` + `jspecify`）。

保留 spike 分支作为参考实现。重新评估的触发条件：

- 需要 checkpoint / 人工审批中断恢复；
- 需要模型动态决定下一个智能体（而非固定顺序）；
- 需要多团队并行分支与汇合；
- 需要可视化流程图作为交付物。

届时优先只替换路由层，保留 AgentScope 的 agent/tool/事件层，与本 POC 的边界一致。

## 6. 复现方式

```bash
# 图编排
PAYROLL_ORCHESTRATOR=graph mvn spring-boot:run

# 手写编排（默认）
mvn spring-boot:run
```

启动日志会打印当前编排模式：`[多智能体] 编排模式：...`。
