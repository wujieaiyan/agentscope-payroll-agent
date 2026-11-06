# 多智能体改造交接说明

> 状态：已实现并通过本地验证，改动尚未提交。适用于后续接手开发、评审或继续扩展的人/智能体。

## 1. 需求

把原先的单 `ReActAgent` 对话改为三个职责分离的智能体，并保留可审计、可排查的能力：

1. **计算智能体**：理解用户诉求，调用确定性核算工具，产出计算记录。
2. **验证智能体**：对计算记录中的核算对象独立调用校验工具，复核勾稽关系。
3. **核算智能体**：只汇总前两份记录，生成最终 HR 答复。
4. 控制台输出结构化日志，便于开发排查。
5. 金额计算仍然只由 `PayrollEngine` 完成，模型不参与算数。

## 2. 架构

```
用户问题
  │
  ▼
PayrollChatService（Java 固定编排，非 LLM 路由）
  ├─ ① PayrollCalculatorAgent  → 计算记录（核算对象 / 结果 / 风险 / 待验证点）
  ├─ ② PayrollValidatorAgent   → 验证记录（结论 / 明细 / 问题 / 建议）
  └─ ③ PayrollAccountantAgent  → 最终答复（无工具，只读前两份记录）
  │
  ▼
SSE: agent_start / agent_text / agent_end / text / done
```

选择确定性编排而非 `SubAgentTool`，原因是：工资核算必须保证“先算、再验、后答复”，
且超时、降级、审计行为不能依赖模型自行决定。

## 3. 关键文件

| 文件 | 职责 |
| --- | --- |
| `src/main/java/io/agentscope/examples/payroll/agent/PayrollAgentFactory.java` | 三个角色的系统提示词、工具集裁剪、共享 `Model`、`Team` 生命周期、Ollama 思考开关 |
| `src/main/java/io/agentscope/examples/payroll/service/PayrollChatService.java` | 三阶段编排、核算对象提取、150s 阶段超时、验证降级、阶段日志 |
| `src/main/java/io/agentscope/examples/payroll/service/ChatStreamListener.java` | 新增 `onAgentStart / onAgentOutput / onAgentEnd` 默认方法 |
| `src/main/java/io/agentscope/examples/payroll/web/PayrollWebController.java` | `agent_start/agent_text/agent_end` SSE 映射、600s SSE 超时、`[SSE]` 日志 |
| `src/main/resources/static/app.js` | 按智能体分块渲染阶段输出，最终答复仍写入回答气泡 |
| `src/main/resources/static/style.css` | `.chat-agent-block` 等样式 |
| `src/main/resources/logback.xml` | 日志写 stderr，抑制 `Toolkit` 注册噪声 |
| `src/test/java/io/agentscope/examples/payroll/PayrollChatImportWebTest.java` | SSE 多智能体事件断言与测试替身 |

## 4. 角色与工具集

| 阶段 | Agent 名 | maxIters | 工具 |
| --- | --- | --- | --- |
| 计算 | `PayrollCalculatorAgent` | 12 | `list_employees`、`get_employee_profile`、`get_attendance_summary`、`calculate_payroll`、`calculate_department_payroll`、`generate_payslip`、`get_payroll_policy` |
| 验证 | `PayrollValidatorAgent` | 8 | `validate_payroll`、`get_payroll_policy`、`get_employee_profile`、`list_employees`、`get_attendance_summary` |
| 核算 | `PayrollAccountantAgent` | 4 | 无 |

工具集通过 `Toolkit.removeTool` 按角色裁剪，从工具层面阻止“计算智能体自行验证”和
“验证智能体重新算一遍”。

## 5. 流水线契约

- 每阶段输入都是纯文本 `UserMessage`，阶段间不共享工具 JSON。
- **核算对象提取**：编排层从 `ToolCallDeltaEvent` 累积工具参数，在 `ToolResultEndEvent` 时解析：
  - `calculate_payroll` / `generate_payslip` → `employee_id + period`
  - `calculate_department_payroll` → 按部门展开，最多 3 名员工
  - 无 `period` 或解析失败则忽略
- 验证输入包含用户问题、计算记录、核算对象列表；验证提示词要求逐个调用 `validate_payroll`。
- 核算输入包含用户问题、计算记录、验证记录；明确禁止改动金额。
- **降级**：
  - 验证阶段抛异常 → 返回“验证智能体执行失败：… 本轮未完成独立验证”，不中断整轮对话。
  - 验证输出为空 → 同样标记未完成独立验证。
  - 没有核算对象（政策/考勤咨询）→ 跳过验证阶段，但仍有验证区块。
  - 计算或核算阶段失败 → 整轮失败，转成可操作的模型服务提示。

## 6. 日志

日志写 stderr，前缀统一为 `[多智能体]`：

- `初始化完成`、`计算/验证智能体工具集`
- `[阶段] 阶段开始`
- `[阶段] 调用工具：<name>`
- `[阶段] 工具结束：<name>，state=…，参数=…`
- `[阶段] 阶段结束：耗时=…ms，工具=[…]`
- `① 计算完成 / ② 验证完成 / 完成：总耗时=…`

阶段完整输入/输出为 DEBUG，INFO 只保留 400 字符预览。
`io.agentscope.core.tool.Toolkit` 已降到 WARN，避免每次初始化刷 16 行注册日志。
Web 层另有 `[SSE] 对话开始 / 完成 / 失败` 日志。

## 7. SSE 协议

新增事件：

| 事件 | data | 说明 |
| --- | --- | --- |
| `agent_start` | `{"agent","label"}` | 新建智能体区块 |
| `agent_text` | `{"agent","text"}` | 计算/验证阶段的流式文本 |
| `agent_end` | `{"agent"}` | 区块完成 |

原有 `start / phase / thinking / tool_start / tool_args / tool_result / text / done / error` 保持不变。
`text` 只承载核算智能体的最终答复，避免中间记录混入回答气泡。

## 8. 配置与运行

- 默认模型：`ollama:qwen2.5:3b-instruct`，`OLLAMA_BASE_URL` 默认 `http://localhost:11434`。
- `PAYROLL_MODEL`、`PAYROLL_OLLAMA_THINK`、`PAYROLL_OLLAMA_NUM_CTX`、
  `PAYROLL_OLLAMA_TEMPERATURE` 语义不变。
- 阶段超时：150s；SSE 超时：600s。
- 管理台：`java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --web --port 18080`。

## 9. 测试与验证

- `mvn test`：43 个测试全部通过。
- 真实 Ollama 验证（`qwen2.5:3b-instruct`）：
  - 计算阶段调用 `calculate_payroll`，耗时约 10s；
  - 验证阶段调用 `validate_payroll`，耗时约 7s；
  - 核算阶段约 5s；
  - 最终答复：应发 44,235.63、扣款 16,574.24、实发 27,661.39、企业成本 58,973.13、
    个税 4,915.35、验证结论“通过”。
- 浏览器实测：三个智能体区块依次出现并标记完成，工具状态“成功”，最终答复进入回答气泡。

## 10. 已知限制与后续建议

1. 单个 `PayrollChatService` 只维护一个 `Team`，并用 `synchronized` 串行处理请求；
   多用户场景需要按会话隔离智能体实例。
2. 三个智能体共享同一个 `Model` 实例，但各持有一份裁剪后的独立 `Toolkit`；
   模型服务异常时三阶段会一起失败。
3. 验证对象来自工具参数解析，若模型用非标准参数名调用工具，可能提取不到对象；
   此时会跳过验证并在日志中体现。
4. 批量场景只抽检前 3 名员工，其余依赖 `validate_payroll` 的汇总结果。
5. 前端暂不支持手动折叠单个智能体区块；验证记录默认展示在思考面板内。
6. 改动尚未提交；提交前建议复核 `git diff` 并确认是否需要更新版本号。

## 11. 快速排障

| 现象 | 排查 |
| --- | --- |
| 一直停在计算阶段 | 查看 `[多智能体][calculator]` 日志与 Ollama 服务 |
| 验证区块显示“本轮无需校验” | 计算阶段没有提取到 `period` 或核算对象，检查工具参数 |
| 答复提示“本轮未完成独立验证” | 验证阶段异常或输出为空，查看 `[多智能体][validator]` 与 error 堆栈 |
| 前端没有智能体区块 | 检查 SSE 是否收到 `agent_start`，以及 `app.js` 是否被缓存 |
| 日志过多 | 调整 `logback.xml` 根级别；`Toolkit` 已静音 |
