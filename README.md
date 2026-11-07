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
| 用户与权限 | 登录后按角色控制接口与页面：内置管理员 / 人事 / 只读访客，支持自定义角色与 20 项权限码；用户、部门、角色均可在管理台维护 |
| 多用户会话 | 每个登录用户拥有独立会话列表；同一用户可打开多个会话窗口，消息按会话持久化并按用户隔离 |
| Excel 导入 | 员工、考核、考勤支持 `.xlsx/.xls` 批量导入：表头别名匹配、逐行校验、部分成功并返回行号与失败原因 |
| 导入模板 | 管理台一键下载标准表头与填写说明，避免列名不一致 |
| 对话界面 | “智能问答”页签由三个智能体串行完成：计算 → 验证 → 核算，SSE 流式输出各智能体阶段、工具时间线与模型思考过程 |
| 城市政策 | 每个城市独立维护个税参数、社保公积金比例与基数上下限 |
| 工资批次 | 预览 → 人工微调 → 提交审批 → 审批通过 → 出账（服务端重算）→ 确认发送 |
| 审批链 | 全局/部门审批链可配置优先级与金额区间；提交人不能自批；金额指纹固定审批快照，审批后数据变化必须重新提交 |
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

首次建库会创建默认管理员 `admin / admin123`（仅当 `app_users` 为空时执行），
请登录后立即使用右上角“修改密码”。生产环境应通过 `PAYROLL_ADMIN_PASSWORD` 指定初始密码；
`PAYROLL_AUTH_ENABLED=false` 会跳过登录并授予全部权限，仅用于本地测试与离线演示。

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
5. **工资批次**：选择期间与范围（全员或某部门）预览；有失败员工时不能提交审批。
   可对个别员工微调绩效奖金或其他补贴（微调值是最终值，不再叠加系数），
   页面会提示未应用的修改并禁用提交审批。
6. **提交审批**：批次进入 `PENDING_APPROVAL`，按“部门优先、金额区间、优先级”匹配审批链，
   并把期间、范围与逐人结果序列化为 SHA-256 金额指纹。
7. **审批**：具备 `payroll:approve` 的用户可通过或驳回（必须填写原因）；提交人不能审批/驳回
   自己提交的批次。审批时重新计算指纹，提交后任何明细变化都会返回 409 并要求重新提交。
8. **出账**：审批通过后服务端按微调快照重新核算并与审批指纹比对，一致才写入 `FINALIZED`，
   生成 `output/payroll-<期间>-run<id>.xlsx`；不一致同样返回 409。
9. **发送**：必须显式确认；发送后展示逐人结果（成功/失败及原因），批次进入 `SENT`，重复发送被拒绝。
10. **审批链配置**：`approval:manage` 用户可在“审批链”页签维护全局/部门流程。同一范围只能有一条
    生效流程；部门流程优先于全局流程，再按优先级与创建顺序匹配。
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
| `ADMIN` | 系统管理员 | 全部 20 项权限，含用户、部门、角色、审计与审批链管理 |
| `HR` | 人事专员 | 员工/考核/考勤/工资批次读写、审批工资批次、Excel 导入、智能问答；政策只读 |
| `VIEWER` | 只读访客 | 员工/考核/政策/考勤/工资批次/部门/角色只读 + 智能问答 |

权限码按“查看/维护”成对划分：`employee:*`、`review:*`、`policy:*`、`attendance:*`、`payroll:*`，
外加 `payroll:approve`、`approval:manage`、`import:use`、`chat:use`、`department:read|manage`、
`role:read|manage`、`user:manage`、`audit:read`。
自定义角色可自由组合权限码；内置角色不可修改或删除。

安全规则：不能停用自己或修改自己的角色；系统始终保留至少一名启用状态的管理员；
角色与部门必须存在（用户只能归属启用部门）；删除被员工或用户占用的部门会被拒绝；
部门改名会在同一事务内同步 `employees.department`。

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
  按 `user_id` 隔离召回相关历史片段，帮助解析“他”“上次那个部门”等指代。
  助手答复不入索引；金额与比例在写入前替换为占位符，注入前再清洗一次，所有数值仍必须重新调用工具获取。
  默认关闭，开启步骤：

  ```bash
  ollama pull nomic-embed-text
  PAYROLL_MEMORY_ENABLED=true java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar
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
     "allowedTools":["get_employee_profile","get_attendance_summary"],"timeoutSeconds":15}
  ]' java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar
  ```

- **角色 Skill 外置（可选）**：三个角色的提示词已拆成 AgentScope 动态技能，系统提示只保留
  金额安全规则与“第一步加载技能”的指令，模型通过 `load_skill_through_path` 按需读取角色说明。
  默认使用代码内置版本；设置 `PAYROLL_NACOS_SERVER` 后按 `payroll-calculator.md`、
  `payroll-validator.md`、`payroll-accountant.md`（group 默认 `payroll-agent`）从 Nacos 读取，
  缺失或连接失败时逐个技能回退内置版本。发布模板与命令见
  [`docs/nacos-skills/README.md`](docs/nacos-skills/README.md)。
  业务工具挂在未激活的技能工具组中，技能加载成功后才会出现在模型工具列表，
  因此“先加载技能再核算”由工具可见性强制，而不是只靠提示词约束。

  ```bash
  PAYROLL_NACOS_SERVER=http://127.0.0.1:8848 \
  PAYROLL_NACOS_GROUP=payroll-agent \
  java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar
  ```

### 控制台日志

开发排查以 `[多智能体]` 为前缀，输出到 stderr（`logback.xml` 配置），与 CLI 结果分开：

```
[多智能体] 初始化完成：计算 / 验证 / 核算三个智能体 + 上下文压缩器，模型=ollama:qwen2.5:3b-instruct
[Skill] 生效技能：[payroll-calculator(nacos), payroll-validator(nacos), payroll-accountant(nacos)]
[多智能体] 计算智能体工具集：[...]
[多智能体][calculator] 阶段开始：① 计算智能体：理解诉求并调用核算工具
[多智能体][calculator] 调用工具：calculate_payroll
[多智能体][calculator] 工具结束：calculate_payroll，state=SUCCESS，参数={"employee_id":"张伟","period":"2026-10"}
[多智能体][calculator] 阶段结束：耗时=20631ms，工具=[calculate_payroll]
[多智能体] ① 计算完成：耗时=20631ms，核算对象=1，输出=...
[多智能体][validator] 阶段开始：② 验证智能体：独立复核勾稽关系
[多智能体] ② 验证完成：输出=...
[多智能体][accountant] 阶段开始：③ 核算智能体：汇总计算与验证结论
[上下文] 压缩完成：折叠 3 轮，保留 4 轮，摘要 86 字，耗时=4932ms
[多智能体] 完成：总耗时=36771ms，工具=[calculate_payroll, validate_payroll]，答复长度=242，上下文=摘要6轮+最近4轮
```

- 阶段输入 / 输出全文为 DEBUG；INFO 只打印截断预览，避免日志过长。
- `io.agentscope.core.tool.Toolkit` 的注册日志已降到 WARN，工具集改为每角色一行 INFO。
- Web 侧另有 `[SSE] 对话开始 / 完成 / 失败` 与耗时日志。

完整交接说明（契约、排障、限制）见 [`docs/multi-agent-handoff.md`](docs/multi-agent-handoff.md)。

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
| `PAYROLL_AUTH_ENABLED` | `true` | 是否启用登录与权限；`false` 时所有请求视为内置管理员（仅测试/离线演示） |
| `PAYROLL_ADMIN_PASSWORD` | `admin123` | 首次建库时创建 `admin` 的初始密码，仅在 `app_users` 为空时生效 |
| `PAYROLL_AUDIT_RETENTION_DAYS` | `180` | 审计日志保留天数，启动时清理更早记录 |
| `PAYROLL_MCP_SERVERS` | 空 | MCP 服务 JSON 数组；为空不启用。支持 `stdio`/`sse`/`http`、`allowedTools`、`timeoutSeconds` |
| `PAYROLL_NACOS_SERVER` | 空 | Nacos 地址；设置后启用角色 Skill 外置，例如 `http://127.0.0.1:8848` |
| `PAYROLL_NACOS_ENABLED` | 有地址则 `true` | 显式关闭/开启 Nacos 技能读取 |
| `PAYROLL_NACOS_GROUP` | `payroll-agent` | 技能配置所在分组 |
| `PAYROLL_NACOS_NAMESPACE` | 空 | 命名空间（Nacos 的 tenant） |
| `PAYROLL_NACOS_USERNAME` / `PAYROLL_NACOS_PASSWORD` | 空 | 开启鉴权的 Nacos 登录账号 |
| `PAYROLL_NACOS_TIMEOUT_MS` | `2000` | 单次配置读取/登录超时 |
| `PAYROLL_NACOS_SKILL_TTL_SECONDS` | `60` | 技能内容缓存时间；过期后下一轮重新读取 |
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
| GET | `/api/approval/flows` | 审批链列表（需 `approval:manage`） |
| POST | `/api/approval/flows` | 新建审批链，按 `scope_type` 区分 `GLOBAL`/`DEPARTMENT` |
| PUT/DELETE | `/api/approval/flows/{id}` | 修改 / 删除审批链 |
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
  审批链增删改、Excel 导入、用户/角色/部门、向量重建，
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

共 124 个测试，覆盖：

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
- 审批 HTTP 权限：HR 提交、管理员审批与出账、HR 不能管理审批链、只读用户不能提交、
  驳回原因必填、审批轨迹查询
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
- 工具结构化成功/错误返回、工资条输出、Toolkit 反射注册与 schema 生成

既有管理台测试通过 `payroll.auth.enabled=false` 跳过登录，专注业务回归；
`PayrollAuthWebTest` 使用独立内存库与真实 BCrypt 登录验证认证、权限与会话隔离。

## 生产化清单

- 用 HR 主数据、考勤、绩效服务替换 H2 仓库，并做行级数据权限
- 政策参数（税率、基数、比例、生效日期）迁移到配置中心，按社保年度版本化
- 以 `employee_id + period` 做幂等键，核算结果落库并保留计算快照（已具备雏形）
- 管理台已具备登录、角色、接口级权限与写操作审计；生产化还需补充会话超时策略、行级数据权限与审计日志异地留存
- 接入真实 SMTP / 邮件服务商，增加重试、退信处理与发送配额
- 审批流已支持可配置审批链、自批拦截与金额快照校验；生产化还需支持多级会签、审批人按组织架构解析，
  并将审计日志对接日志平台、加密敏感字段
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
│   ├── PayrollAgentFactory.java  # 计算/验证/核算三个 ReActAgent、角色提示词与 Ollama 思考开关
│   ├── PayrollTools.java         # 8 个 @Tool
│   └── PayrollIntent.java        # 结构化意图
├── model
│   └── OllamaThinkingModel.java  # Ollama 流式适配：透传 thinking 与工具调用
├── domain                        # 员工、考勤、政策、批次、邮件、核算结果
├── db
│   ├── PayrollDatabase.java      # H2 连接、建表与首次种子数据
│   ├── H2PayrollRepository.java  # 主数据/考核/政策/考勤仓库
│   ├── H2PayrollRunStore.java    # 批次、明细、失败与邮件审计
│   ├── H2ApprovalStore.java      # 审批链、审批状态与轨迹
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
├── web
│   ├── PayrollWebController.java # REST API（契约与原管理台一致）
│   ├── ApprovalController.java   # 审批链配置接口
│   ├── ApiExceptionHandler.java  # 统一 400/409/404/500 错误响应
│   └── ApiResponse.java          # {status,data,message} 响应封装
└── support/JsonSupport.java      # snake_case JSON（CLI 与测试）

src/main/resources
├── application.yml               # 端口、Jackson、H2、输出目录
├── static/                       # index.html / app.js / style.css
└── data/                         # 演示员工、考勤、变动工资
```
