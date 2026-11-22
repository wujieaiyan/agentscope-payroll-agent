# 对话内点击确认（Qoder 式人工确认）规格

2026-11-21。在既有「提案 → 确认 → 执行」（`docs/routing-action-confirm-spec.md`）之上，把
确认做成对话里可直接点击的卡片，并补一个让模型主动请求确认的工具。

## 1. 背景

点击式卡片与确认接口已经存在，但默认不可见：

- `payroll.chat.action-confirm.enabled` 默认 `false`，写工具不生成提案；
- 只有白名单工具（知识库写、`skill_run`）在开关关闭时仍强制确认；
- 管理台没有开关，只能在配置文件/环境变量里改并重启；
- 模型用自然语言问「要我执行吗？」不会产生卡片。

## 2. 已确认决策

| 编号 | 决策 |
| --- | --- |
| C1 | 确认范围为**全部写操作**：全局开关打开后，任何非只读工具（本地/MCP）都先生成提案，不在白名单里也确认。 |
| C2 | 开关存 Nacos `payroll-agent-settings.json` 的 `actionConfirm` 键，管理台可改，运行期按 TTL（默认 60s）生效，无需重启；Nacos 未启用时回退 `payroll.chat.action-confirm.enabled`。 |
| C3 | 新增工具 `request_confirmation`：模型需要用户决策/许可时调用它，生成确认卡片；**无论全局开关如何都强制确认**（与 `skill_run` 同级）。 |
| C4 | `request_confirmation` 确认后不需要服务端执行副作用：执行器返回 `{status: CONFIRMED, followup}`，前端把 `followup` 作为新一轮用户输入自动发送，实现“确认后继续”。 |
| C5 | 卡片展示 `summary` + `detail`，不展示 `followup` 与原始参数 JSON；确认按钮文案对 `request_confirmation` 显示「确认」，其余写工具仍为「确认执行」。 |

不在本次范围：把提案接入 `/api/approval/tasks`（D5 保持：会话内卡片是唯一入口）、跨会话推送。

## 3. 状态机与数据流

```
模型调用 request_confirmation / 写工具
        │
        ▼
ActionGuard.intercept → ActionProposal(PENDING) → SSE action_proposal → 前端卡片
        │
   用户点「确认」
        ▼
POST /api/chat/actions/{id}/confirm
        │
        ├─ 普通写工具：ActionExecutor 执行 → 结果消息（不调模型）
        └─ request_confirmation：LocalAction 返回 followup
                 → 结果消息「已确认，正在继续处理…」
                 → 前端解析 params_json.followup → send() 新一轮
```

提案状态、幂等、过期、归属与审计不变（D7）。

## 4. 后端改动

1. `ActionGuard`
   - `request_confirmation` 加入默认强制确认清单与 `MANDATORY_ALWAYS_CONFIRM_TOOLS`。
   - 摘要对 `request_confirmation` 取 `summary` 参数，不拼参数列表。
2. `ConfirmationTools`（payroll-common `agent` 包）
   - `@Tool(name = "request_confirmation")`，非只读；参数 `summary`（必填）、`detail`、`followup`。
   - 调用 `ActionGuard.intercept(..., false, false, false)`；返回 PENDING 或（开关关闭且未强制时）直接返回 `CONFIRMED`。
   - 注册到所有面向用户的工具集（`PayrollAgentFactory.toolkit(...)` 与知识库智能体）。
3. `PayrollCoreConfiguration.actionExecutor`
   - 注册 `request_confirmation` 的 LocalAction：透传 `followup`，返回 `status=CONFIRMED`。
4. `NacosAgentRegistry`
   - 新增 `Optional<Boolean> actionConfirmOverride()`（Nacos 未启用/无键时为空）。
   - `setActiveAgent` / `clearActiveAgent` 改为**读-改-写**，保留 `actionConfirm` 键。
   - 新增 `setActionConfirm(boolean)` 读-改-写发布并刷新缓存。
5. `PayrollChatService`
   - `actionConfirmEnabled` 改为 `BooleanSupplier`，每轮生成 `ActionGuard` 时求值。
6. `AgentAdminController`
   - `GET /api/admin/agents/action-confirm` → `{enabled, source}`。
   - `PUT /api/admin/agents/action-confirm` body `{enabled}`，权限 `agent:manage`，写审计 `agent.action-confirm`。
7. `ActionProposalService.resultText`
   - `request_confirmation` 结果不展示 JSON：`操作结果：<summary>\n状态：EXECUTED\n已确认，正在继续处理…`。

## 5. 前端改动

- `AgentsView.vue`：全局核算智能体卡片旁新增「写操作确认」开关，调用上述接口；不可写时禁用。
- `ChatView.vue`
  - `decide()`：`request_confirmation` 确认后从 `params_json.followup` 取续问文本，写入 `draft` 并调用 `send()`；不先 `loadMessages`，避免与新一轮流式冲突。
  - 提案卡片：`request_confirmation` 渲染 `detail` 段落，隐藏参数 JSON 与 `followup`；确认按钮文案「确认」。

## 6. 配置

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `payroll.chat.action-confirm.enabled` | `false` | 全局兜底；Nacos `actionConfirm` 优先 |
| `payroll.chat.action-confirm.ttl-minutes` | `15` | 提案有效期 |
| `payroll.chat.action-confirm.always-confirm-tools` | 知识库写 + `skill_run` | 开关关闭时仍强制；`request_confirmation` 为代码强制项 |

## 7. 验收

1. 管理台打开开关后 ≤60s，对话里调用任一 MCP 写工具出现待确认卡片，确认前无副作用。
2. 关闭开关后，普通写工具直接执行；`request_confirmation` 与 `skill_run` 仍出卡片。
3. 「帮我整理一份通知草稿」类请求：模型调用 `request_confirmation`，卡片展示 summary/detail，点「确认」后自动发送 followup 并继续作答。
4. 重复点击确认幂等；他人确认 404；过期 410。
5. 单元/集成测试：ActionGuard 强制项与摘要、注册表读-改-写、控制器权限与往返、前端构建。

## 8. 风险

- 全局开关打开后所有未标注只读的本地工具都会确认；新增工具必须显式 `readOnly = true`，否则会多一次点击。
- `followup` 由模型生成，前端只做文本转发，不拼接系统指令。

## 9. 落地状态（2026-11-21）

| 层 | 文件 | 内容 |
| --- | --- | --- |
| 工具 | `payroll-common/.../agent/ConfirmationTools.java` | `request_confirmation(summary, detail?, followup?)`，无副作用，经 `ActionGuard` 出卡片 |
| 闸门 | `ActionGuard.java` | 强制清单加入 `request_confirmation`；专属收尾文案与 summary 摘要 |
| 分类 | `ToolSafety.java` | 扫描 `ConfirmationTools` 注解，保持非只读 |
| 执行 | `PayrollCoreConfiguration.java`、`ActionProposalService.java` | 确认后返回 `{status: CONFIRMED, followup}`，结果消息提示“正在继续处理” |
| 注册 | `PayrollAgentFactory.java` | 活跃智能体、非薪酬流程节点、知识库兜底智能体注册该工具 |
| 开关 | `NacosAgentRegistry.java`、`PayrollChatService.java`、`PayrollAgentConfiguration.java` | Nacos `actionConfirm` 读-改-写，运行期 `BooleanSupplier` 按 TTL 生效，未配置回退环境变量 |
| 接口 | `AgentAdminController.java` | `GET/PUT /api/admin/agents/action-confirm`，权限 `agent:manage`，审计 `agent.action-confirm` |
| 前端 | `ChatView.vue`、`AgentsView.vue` | 卡片展示 summary/detail；确认后自动发送 followup（保留用户草稿）；管理台开关卡片 |

验证：`mvn -pl payroll-tests -am test` 2242 项全部通过；`npm run build` 通过。真机验收需重启 Web 与 runtime（runtime 必须 Run）。
