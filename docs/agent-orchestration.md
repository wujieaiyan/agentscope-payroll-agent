# 智能体流程编排（DAG）

## 目标与决策

管理台新增“流程编排”页签，用拖拽画布把已注册智能体与条件判断组成 DAG；
管理员把某条流程设为全局默认后，下一轮对话按该流程执行。

已确认的取舍：

| 决策点 | 结论 |
| --- | --- |
| 交付范围 | 完整 DAG：分支、汇合、多流程、全局默认流程 |
| 节点能力 | 开始 / 智能体 / 条件 / 结束；条件支持预设判断，不允许自由表达式 |
| 编辑器 | 本地 vendor Vue Flow，拖拽节点、连线、缩放 |
| 生效方式 | 全局默认流程，保存/激活后下一轮对话生效 |
| 并行语义 | 同一层就绪节点真并行，每个节点使用独立智能体实例与独立工具追溯 |

未设置默认流程时完全沿用现有逻辑（全局核算智能体或固定“计算→验证→核算”流水线），
保证升级后行为不变。

## 流程模型

```json
{
  "id": "payroll-default",
  "name": "薪酬标准流程",
  "description": "计算 → 是否有核算对象 → 验证 → 核算",
  "nodes": [
    { "id": "start", "type": "START", "name": "开始", "x": 80, "y": 160 },
    { "id": "calculator", "type": "AGENT", "name": "计算智能体",
      "agentId": "payroll-calculator", "x": 280, "y": 160 },
    { "id": "check", "type": "CONDITION", "name": "是否有核算对象",
      "condition": "HAS_CALCULATION_TARGETS", "x": 520, "y": 160 },
    { "id": "validator", "type": "AGENT", "name": "验证智能体",
      "agentId": "payroll-validator", "x": 760, "y": 60 },
    { "id": "accountant", "type": "AGENT", "name": "核算智能体",
      "agentId": "payroll-accountant", "x": 1000, "y": 60 },
    { "id": "end", "type": "END", "name": "结束", "x": 1220, "y": 160 }
  ],
  "edges": [
    { "id": "e1", "source": "start", "target": "calculator" },
    { "id": "e2", "source": "calculator", "target": "check" },
    { "id": "e3", "source": "check", "target": "validator", "sourceHandle": "TRUE" },
    { "id": "e4", "source": "validator", "target": "accountant" },
    { "id": "e5", "source": "accountant", "target": "end" },
    { "id": "e6", "source": "check", "target": "end", "sourceHandle": "FALSE" }
  ],
  "createdBy": "system",
  "createdAt": "2026-11-08T09:00:00",
  "updatedAt": "2026-11-08T09:00:00"
}
```

- 节点类型：`START`、`AGENT`、`CONDITION`、`END`。
- `AGENT` 通过 `agentId` 引用 Agent 管理中的定义；执行时使用该定义的技能与工具白名单。
- `CONDITION` 的条件只允许预设枚举，避免自由表达式带来的注入与不可预测性。
- 边的 `sourceHandle` 仅条件节点使用，取值为 `TRUE` / `FALSE`。

### 预设条件

| 枚举 | 判定 |
| --- | --- |
| `HAS_CALCULATION_TARGETS` | 条件节点的上游已完成节点中，工具调用了工资计算并产生了核算对象 |
| `HAS_TOOL_ERROR` | 上游已完成节点中出现工具执行错误 |
| `OUTPUT_CONTAINS` | 上游任一已完成节点的输出包含 `conditionValue`（忽略大小写） |

条件只统计该节点的祖先节点结果，不受并行兄弟分支影响。

## 校验规则

保存流程时校验，激活时再次校验引用可用性：

- 名称必填 ≤40 字，描述 ≤200 字；节点 ≤30、边 ≤60。
- 恰好一个 `START`、一个 `END`；至少一个 `AGENT`。
- 节点 id、边 id 唯一；边必须引用存在的节点；禁止自环与重复边。
- `START` 无入边、恰好一条出边；`END` 至少一条入边、无出边。
- `AGENT` 至少一条入边（支持汇合）、至少一条出边；引用的智能体必须存在。
- `CONDITION` 恰好一条入边、恰好两条出边，且 `TRUE`/`FALSE` 各一条。
- 图必须无环、所有节点从 `START` 可达且都能到达 `END`。
- `OUTPUT_CONTAINS` 必须填写非空关键字。
- 激活流程时，所有 `AGENT` 节点引用的智能体必须处于 `ONLINE` 状态。

## 执行语义

1. 一轮对话开始时读取全局默认流程；未配置或读取失败则走既有链路。
2. 从 `START` 开始按“所有入边已确定”推进；同一批就绪节点并行执行。
3. 智能体节点输入 = `【用户问题】` + 所有经选中路径可达的上游记录
   （按拓扑层级排序拼接，兄弟分支的输出不会串入）。记录标题取节点名称并去掉“①②”编号，
   例如 `【计算智能体记录】`、`【验证智能体记录】`，与角色技能里的引用保持一致；
   已执行但没有文本输出的节点保留 `（本节点未返回内容）` 占位；
   上游产生过核算对象时追加 `【上游核算对象（最多处理前 3 个）】` 清单。
4. 条件节点不调用模型，只评估预设条件，并把未选中的出边标记为阻断。
5. 汇合节点等待所有入边确定；只要有任一入边被选中就执行，否则标记跳过并继续阻断下游。
6. 每个节点使用独立 `ReActAgent`、`PayrollTools` 与 `ToolTraceRecorder`，避免并行工具追溯串线；
   节点结束即关闭智能体与 MCP 客户端。
7. `END` 不执行模型；最终答复取选中入边中拓扑层级最深的节点输出。
   默认流程因此能保留“查询类问题直接返回计算记录”的既有行为。
   - **实时推流**：流程只有一个末端智能体（出边不再到达其他智能体节点）时，该节点以
     `answerStage` 执行，模型增量直接作为 `text` 事件推给前端；收尾只补发尚未流出的尾部差量。
   - 存在多个末端智能体（并行收尾或条件两腿各自收尾）时不推流，等流程结束后整段发送，
     避免多条分支交织进同一个回答气泡。
8. 任一节点失败则整轮失败，返回可定位的错误；超时沿用单节点 150 秒。

## 存储与接口

- 流程列表：Nacos dataId `payroll-flows.json`，group 默认 `payroll-agent`，JSON 数组。
- 默认流程：Nacos dataId `payroll-flow-settings.json`，字段 `activeFlowId`。
- 未启用 Nacos：列表回退内置 `payroll-default`，页面只读；默认流程保持未设置，对话仍走既有链路。
- 权限：新增 `flow:manage`（内置管理员自动拥有）。
- 审计：`flow.create`、`flow.update`、`flow.delete`、`flow.activate`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/flows` | 流程列表与激活状态 |
| GET | `/api/admin/flows/meta` | 是否可写、存储位置、可选智能体 |
| POST | `/api/admin/flows` | 新建流程 |
| PUT | `/api/admin/flows/{id}` | 更新流程 |
| DELETE | `/api/admin/flows/{id}` | 删除流程（默认流程不可删） |
| PUT | `/api/admin/flows/{id}/active` | 设为全局默认流程 |

## 前端交互

- 左侧流程列表：新建、复制、删除、设为默认；未启用 Nacos 时只读。
- 中间 Vue Flow 画布：从节点面板拖入开始/智能体/条件/结束；拖拽节点调整位置；
  从输出锚点拖到目标节点完成连线；条件节点有“是/否”两个输出锚点。
- 右侧属性面板：节点名称、绑定智能体、条件类型与关键字；支持删除节点与连线。
- 保存前展示后端校验错误；保存成功后刷新列表；激活后提示下一轮对话生效。
- 对话时间线按节点 id 展示 `agent_start/agent_output/agent_end`，条件判断通过 `phase` 事件展示。
- `vendor/vue-flow-core.iife.js` 为生产构建；若重新下载替换，需确认 `isDev()` 不再引用未定义
  标识符（当前已固定返回 `false`），否则挂载画布会抛 `ReferenceError: production is not defined`。

## 兼容与回退

- 未激活流程：行为与升级前完全一致。
- 流程读取失败、引用智能体被删除/下架：记录告警并回退既有链路，不让对话不可用。
- 既有 `payroll.orchestrator=graph` 的 langgraph4j POC 保留，仅在未激活流程时生效。
