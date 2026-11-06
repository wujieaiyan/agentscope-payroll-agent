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
ReActAgent                         PayrollWebController（REST）
  ├─ 意图识别 / 追问补参 / 工具编排        ├─ 员工 / 考核 / 城市政策 / 考勤维护
  │  / 结果解释                          │  （含 Excel 导入与模板下载）
  ▼ @Tool（8 个，返回结构化 JSON）         ├─ 工资批次预览、微调、出账
PayrollTools                             ├─ 对话式问答（复用同一 ReActAgent）
      │                                  └─ 确认后 dry-run 全员发信
      │                                       │
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
| Excel 导入 | 员工、考核、考勤支持 `.xlsx/.xls` 批量导入：表头别名匹配、逐行校验、部分成功并返回行号与失败原因 |
| 导入模板 | 管理台一键下载标准表头与填写说明，避免列名不一致 |
| 对话界面 | “智能问答”页签复用同一 `ReActAgent`，SSE 流式输出回答、工具时间线与模型思考过程 |
| 城市政策 | 每个城市独立维护个税参数、社保公积金比例与基数上下限 |
| 工资批次 | 预览 → 人工微调 → 出账（服务端重算）→ 确认发送 |
| Excel 导出 | 工资明细、核算失败、出账说明三个工作表 |
| 明细入库 | 批次、逐人明细、微调快照、失败原因、邮件投递审计全部落 H2 |
| 邮件发送 | 仅 dry-run：逐人返回成功/失败状态与失败原因，不真实投递 |
| 政策问答 | 税率表、社保比例、基数上下限、指定城市政策、考勤就绪月份 |
| 结构化意图抽取 | 将自然语言解析为 `action / employeeId / period / department`（建议 DashScope 模型） |

## 快速开始

环境：JDK 17、Maven 3.6+、[Ollama](https://ollama.com/)（本地模型对话时需要）。

```bash
mvn clean package

# 本地 Web 管理台（Spring Boot 内嵌 Tomcat；员工 / 考核 / 政策 / 考勤 / 工资批次 / 智能问答 / Excel 导入）
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --web
# 默认 http://127.0.0.1:8080 ，可用 --port 18080 或 PAYROLL_WEB_PORT 覆盖

# 离线演示：不调用大模型，直接验证核算引擎
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --demo

# 单人工资条 + 发薪前校验
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --payroll EMP001 --period 2026-10

# 部门批量
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --department 研发部 --period 2026-10
```

首次启动 `--web` 会创建 `data/payroll.mv.db` 并从 classpath 导入演示数据；
之后所有修改都写入 H2，不再被演示数据覆盖。

`--web` 由 Spring Boot 3.5 启动：`PayrollWebApplication` 创建内嵌 Tomcat，
`PayrollConfiguration` 装配与 CLI 相同的 `PayrollServices`，`PayrollWebController` 暴露 REST 接口；
CLI 的 `--demo`、`--payroll` 等模式不会启动 Spring 容器。端口、Jackson、H2 与输出目录集中在
`src/main/resources/application.yml`，环境变量仍优先于默认值；IDEA 中也可直接运行
`PayrollWebApplication` 的 `main` 方法。

本地模型对话（默认，无需 API Key）：

```bash
ollama serve
ollama pull qwen2.5:3b-instruct
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar
```

管理台“智能问答”通过 `POST /api/chat/stream` 以 SSE 流式返回。若想看到类似 Qoder 的
“思考过程”，可换用带 thinking 能力的模型（启动时自动检测，无需额外开关）：

```bash
ollama pull deepseek-r1:1.5b
export PAYROLL_MODEL=ollama:deepseek-r1:1.5b  # Windows: set PAYROLL_MODEL=ollama:deepseek-r1:1.5b
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --web
```

思考内容来自模型原生 reasoning 增量，不是前端模拟；工具调用、阶段提示与回答文本仍按事件分开推送。
注意：1.5B 级思考模型的工具调用稳定性弱于 `qwen2.5:3b-instruct`，若发现它直接作答而不调用工具，
请换用更大的思考模型，或关闭思考后继续用默认模型。`PAYROLL_OLLAMA_THINK=false` 可强制关闭思考。

使用 DashScope 在线模型：

```bash
export DASHSCOPE_API_KEY=sk-xxx          # Windows: set DASHSCOPE_API_KEY=sk-xxx
export PAYROLL_MODEL=dashscope:qwen-plus # Windows: set PAYROLL_MODEL=dashscope:qwen-plus
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar
```

示例提问：

- `算一下张伟 2026-10 的工资`
- `研发部 10 月工资表`
- `王强这个月为什么实发这么低？`
- `上海社保基数上下限是多少？`
- `替我检查 EMP005 10 月工资能不能发`

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
5. **工资批次**：选择期间与范围（全员或某部门）预览；有失败员工时不能出账。
   可对个别员工微调绩效奖金或其他补贴（微调值是最终值，不再叠加系数），
   页面会提示未应用的修改并禁用出账。
6. **出账**：服务端按微调快照重新核算，写入 `FINALIZED`，生成 `output/payroll-<期间>-run<id>.xlsx`。
7. **发送**：必须显式确认；发送后展示逐人结果（成功/失败及原因），批次进入 `SENT`，重复发送被拒绝。
8. **智能问答**：在管理台直接提问，回答只做查询、核算与解释；数据维护仍走对应页签，
   金额始终来自确定性引擎。对话依赖 `PAYROLL_MODEL`；模型不可用时不影响其他管理功能。

批次状态机：

```
DRAFT --出账（存在失败则拒绝）--> FINALIZED --确认发送--> SENT
```

## 流式对话（SSE）

管理台“智能问答”通过 `POST /api/chat/stream` 消费 SSE；浏览器 `EventSource` 不支持 POST，
前端改用 `fetch` + `ReadableStream` 解析 `text/event-stream`。事件协议：

| 事件 | data | 前端行为 |
| --- | --- | --- |
| `start` | `{"model":"..."}` | 记录实际模型 |
| `phase` | `{"label":"模型思考中…"}` | 在思考面板追加阶段提示（相同阶段去重） |
| `thinking` | `{"text":"增量"}` | 追加模型原生思考内容 |
| `tool_start` | `{"tool_call_id","name"}` | 新增工具步骤 |
| `tool_args` | `{"tool_call_id","text"}` | 追加工具参数 JSON |
| `tool_result` | `{"tool_call_id","name","state"}` | 标记成功/失败等状态 |
| `text` | `{"text":"增量"}` | 追加回答正文 |
| `done` | `{"reply","tools","model"}` | 收尾：移除光标、显示工具汇总、折叠思考面板 |
| `error` | `{"message":"..."}` | 在当前回答位置显示错误 |

思考面板默认展开、结束后自动折叠并显示耗时，可手动重新展开；工具调用以步骤形式混排在同一时间线中。
流式接口的校验错误（空消息、超长）同样以 `event:error` 返回，避免 `Accept: text/event-stream`
下无法序列化 JSON 错误体。

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
| `PAYROLL_WEB_PORT` | `8080` | 管理台端口（`--port` 优先） |
| `PAYROLL_MODEL` | `ollama:qwen2.5:3b-instruct` | 模型标识；Ollama 用 `ollama:<模型名>`，DashScope 用 `dashscope:<模型名>` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 服务地址 |
| `PAYROLL_OLLAMA_NUM_CTX` | `8192` | Ollama 上下文窗口 |
| `PAYROLL_OLLAMA_TEMPERATURE` | `0.1` | 采样温度，核算场景建议保持低温度 |
| `PAYROLL_OLLAMA_THINK` | 自动检测 | `true`/`false`/`low`/`medium`/`high`；未设置时按模型的 thinking 能力自动启用 |
| `DASHSCOPE_API_KEY` | 无 | 仅 `dashscope:` 模型必填 |

启动时会预检 Ollama 服务与模型是否就绪，未启动或未拉取模型会直接给出中文提示。
本地小模型（1B–3B）在长系统提示下可能漏调工具，建议使用 7B 以上模型或在线模型；
核算金额始终来自确定性引擎，模型漏调工具时不会产生错误金额，但会影响回答完整性。
`--extract` 依赖结构化输出能力，Ollama 扩展暂不提供该元数据，建议改用 DashScope 模型执行。

> Windows 控制台如出现中文乱码，请加 `-Dfile.encoding=UTF-8`，或先执行 `chcp 65001`。

## 管理台 API

所有接口返回 `{"status":"OK","data":...}` 或 `{"status":"ERROR","message":"..."}`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查、考勤就绪月份、数据库/邮件模式与当前对话模型 |
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
| GET | `/api/runs/{id}` | 批次详情（逐人结果、失败、邮箱状态） |
| POST | `/api/runs/{id}/finalize` | 重算并出账，生成 Excel |
| POST | `/api/runs/{id}/send` | 全员 dry-run 发送，需 `{"confirm":true}` |
| GET | `/api/runs/{id}/excel` | 下载批次 Excel |
| POST | `/api/chat` | 管理台对话，body `{"message":"..."}`（≤2000 字符），返回回答与调用工具 |
| POST | `/api/chat/stream` | SSE 流式对话：思考、工具、回答增量与完成事件（见“流式对话”一节） |
| POST | `/api/chat/reset` | 清空当前对话上下文 |

导入接口直接读取请求体，不依赖 multipart/form-data；Excel 上传上限 10MB，对话消息上限 2000 字符。
错误统一返回 `{"status":"ERROR","message":"..."}`：业务校验与请求体格式错误为 400，未知路径为 404，
未预期异常为 500 并写入服务端日志。`/api/chat/stream` 的校验与运行错误以 SSE `event:error`
返回（HTTP 200），便于前端统一处理。

管理台只绑定 `127.0.0.1`，且**未实现登录鉴权**；如需多人访问，应放在反向代理与身份认证之后。

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

## 测试

```bash
mvn test
```

共 41 个测试，覆盖：

- 个税档位边界连续性（36,000 / 144,000 / 300,000 / 960,000）
- 社保基数上下限封顶，以及不同城市政策差异
- 事假 + 加班 + 社保 + 累计个税的全链路金额
- 入职首月自然日折算、跨月累计税额单调递增
- 批量合计等于逐人之和，考勤未就绪、入职前月份拒绝核算
- H2 仓库：种子数据、员工保存/停用、考核系数进入奖金、城市政策封顶
- 批次服务：预览合计、部门范围、微调、出账生成 Excel、发送需确认、
  逐人成功/失败原因、重复发送拒绝、失败员工阻断出账
- Excel 导入：员工部分成功与失败行号、考核系数/非法员工、考勤覆盖与负数拒绝、
  缺必填列拒绝、模板表头与填写说明
- 管理台对话与导入：回答与工具列表、空消息 400、重置、HTTP 上传 Excel、模板下载、未知类型 400
- SSE 流式对话：思考/阶段/工具/文本/完成事件、SSE 错误事件、空消息与超长消息处理
- Ollama 思考适配器：request 携带 think 与工具、thinking 增量转为思考块、工具调用参数解析
- Spring Boot 管理台（随机端口真实 HTTP）：静态页、员工、预览、出账、dry-run 发送、Excel 下载、
  批次历史、统一错误结构与 404 处理
- 工具结构化成功/错误返回、工资条输出、Toolkit 反射注册与 schema 生成

## 生产化清单

- 用 HR 主数据、考勤、绩效服务替换 H2 仓库，并做行级数据权限
- 政策参数（税率、基数、比例、生效日期）迁移到配置中心，按社保年度版本化
- 以 `employee_id + period` 做幂等键，核算结果落库并保留计算快照（已具备雏形）
- 管理台接入登录、角色与操作审计；限制停用、政策修改、出账与发送权限
- 接入真实 SMTP / 邮件服务商，增加重试、退信处理与发送配额
- 接入审批流、发薪复核与审计日志，敏感字段加密
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
│   ├── PayrollAgentFactory.java  # ReActAgent 装配、系统提示词与 Ollama 思考开关
│   ├── PayrollTools.java         # 8 个 @Tool
│   └── PayrollIntent.java        # 结构化意图
├── model
│   └── OllamaThinkingModel.java  # Ollama 流式适配：透传 thinking 与工具调用
├── domain                        # 员工、考勤、政策、批次、邮件、核算结果
├── db
│   ├── PayrollDatabase.java      # H2 连接、建表与首次种子数据
│   ├── H2PayrollRepository.java  # 主数据/考核/政策/考勤仓库
│   └── H2PayrollRunStore.java    # 批次、明细、失败与邮件审计
├── repository                    # 数据访问边界与 JSON 演示实现
├── service
│   ├── PayrollEngine.java        # 唯一金额计算入口
│   ├── PayrollRunService.java    # 预览/微调/出账/发送状态机
│   ├── ExcelExporter.java        # POI 导出
│   ├── ExcelImportService.java   # 员工/考核/考勤导入与模板
│   ├── PayrollChat.java          # 对话接口（便于测试替身）
│   ├── ChatStreamListener.java   # 流式事件回调：思考/阶段/工具/文本/完成
│   ├── PayrollChatService.java   # ReActAgent 会话封装与事件流收集
│   ├── DryRunEmailGateway.java   # dry-run 邮件网关
│   ├── PayrollServices.java      # 组装数据库、引擎、对话与批次服务
│   └── PayslipFormatter.java     # 工资条渲染
├── web
│   ├── PayrollWebController.java # REST API（契约与原管理台一致）
│   ├── ApiExceptionHandler.java  # 统一 400/404/500 错误响应
│   └── ApiResponse.java          # {status,data,message} 响应封装
└── support/JsonSupport.java      # snake_case JSON（CLI 与测试）

src/main/resources
├── application.yml               # 端口、Jackson、H2、输出目录
├── static/                       # index.html / app.js / style.css
└── data/                         # 演示员工、考勤、变动工资
```
