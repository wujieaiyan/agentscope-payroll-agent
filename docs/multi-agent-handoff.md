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
| `src/main/java/io/agentscope/examples/payroll/web/PayrollWebController.java` | 旧版对话与业务接口；共享 `ChatSse` 事件桥、600s SSE 超时、`[SSE]` 日志 |
| `src/main/java/io/agentscope/examples/payroll/web/ChatSessionController.java` | 多会话接口：列表、历史、非流式/SSE 对话、重置 |
| `src/main/java/io/agentscope/examples/payroll/service/ChatSessionManager.java` | 每会话一个 `PayrollChat` 实例、忙碌标记、消息持久化与归属校验 |
| `src/main/java/io/agentscope/examples/payroll/service/AuthService.java` | 基于 `HttpSession` 的登录、权限解析与改密 |
| `src/main/java/io/agentscope/examples/payroll/web/AuthInterceptor.java` | `/api/**` 登录态与 `@RequirePermission` 校验 |
| `src/main/java/io/agentscope/examples/payroll/db/H2AdminRepository.java` | 用户、部门、角色仓储与安全规则 |
| `src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java` | 会话与消息的 H2 持久化，按 `user_id` 隔离 |
| `src/main/java/io/agentscope/examples/payroll/support/Permissions.java` | 17 项权限码与内置角色定义 |
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
  - 没有核算对象（政策/考勤/员工查询）→ 跳过验证阶段，并跳过核算智能体，直接返回计算记录。
  - 计算或核算阶段失败 → 整轮失败，转成可操作的模型服务提示。
- **上下文隔离与压缩**：每个阶段开始前仍调用 `agent.clearContext(agent.getRuntimeContext())`，
  避免智能体内部历史复用旧金额。跨轮指代改由 `ConversationContext` 维护：
  - 每轮成功后记录“用户问题 + 最终答复”，只把最近 4 轮**用户问题**和滚动摘要注入计算智能体；
  - 超过 6 轮或渲染长度超过 3000 字时，调用 `PayrollContextAgent` 把较早轮次折叠进摘要；
  - 摘要只保留员工/工号、部门、期间、追问链与未完成事项；金额、比例、银行卡号、身份证号
    由 `ConversationContext.sanitize()` 在代码层按分句剔除，不依赖小模型遵守提示词；
  - 注入块明确标注“仅用于理解指代，不是数据来源”，所有金额/考勤/政策必须重新调用工具；
  - 摘要与已压缩轮数写入 `chat_sessions.context_summary/context_turns`，重启后从
    `chat_messages` 恢复最近轮次，避免重复压缩；
  - 压缩失败只丢弃最早轮次并记录省略说明，不阻断本轮回答。

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

上下文压缩日志（前缀 `[上下文]`）：

```
[上下文] 压缩完成：折叠 3 轮，保留 4 轮，摘要 86 字，耗时=4932ms
[上下文] 压缩失败，降级为丢弃最早 3 轮：Timeout on blocking read...
[上下文] 保存摘要失败：sessionId=12，原因=...
```

## 7. SSE 协议

新增事件：

| 事件 | data | 说明 |
| --- | --- | --- |
| `agent_start` | `{"agent","label"}` | 新建智能体区块 |
| `agent_text` | `{"agent","text"}` | 计算/验证阶段的流式文本 |
| `agent_end` | `{"agent"}` | 区块完成 |
| `context` | `{"compressed","kept","summarized"}` | 本轮较早轮次已压缩；前端在回答上方插入灰色系统提示 |

原有 `start / phase / thinking / tool_start / tool_args / tool_result / text / done / error` 保持不变。
`text` 只承载核算智能体的最终答复，避免中间记录混入回答气泡。

## 8. 配置与运行

- 默认模型：`ollama:qwen2.5:3b-instruct`，`OLLAMA_BASE_URL` 默认 `http://localhost:11434`。
- `PAYROLL_MODEL`、`PAYROLL_OLLAMA_THINK`、`PAYROLL_OLLAMA_NUM_CTX`、
  `PAYROLL_OLLAMA_TEMPERATURE` 语义不变。
- 阶段超时：150s；上下文压缩超时：60s；SSE 超时：600s。
- 上下文阈值：保留最近 4 轮、超过 6 轮或 3000 字触发压缩、单次最多折叠 12 轮。
- 管理台：`java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --web --port 18080`。
- 默认登录：首次建库创建 `admin / admin123`，可用 `PAYROLL_ADMIN_PASSWORD` 指定初始密码；
  `PAYROLL_AUTH_ENABLED=false` 仅用于测试与离线演示。
- 多会话：`ChatSessionManager` 为每个会话缓存一个独立 `PayrollChat`，会话之间互不共享上下文；
  同一会话的并发流由忙碌标记拒绝，不同会话由 `chatStreamExecutor`（4 线程）并行处理。
- 消息隔离：`chat_sessions`/`chat_messages` 均带 `user_id` 查询条件；
  `/api/chat/sessions/{id}/**` 在读写前调用 `requireOwned`，越权返回 400「会话不存在或无权访问」。
- 上下文持久化：`chat_sessions` 新增 `context_summary CLOB` 与 `context_turns INT`，
  通过 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 兼容旧库；重置/删除会话时同步清空。

## 9. 测试与验证

- `mvn test`：49 个测试全部通过。
- 上下文压缩：`ConversationContextTest` 覆盖触发阈值、最近轮次保留、消息恢复、失败降级、
  金额/比例剔除与空上下文直通。
- 认证与权限：`PayrollAuthWebTest` 使用真实 BCrypt 登录，验证未登录 401、首页跳转、
  只读角色写入 403、管理员建用户，以及不同用户/不同窗口的会话与消息隔离。
- 既有管理台测试通过 `payroll.auth.enabled=false` 跳过登录。
- 真实 Ollama 验证（`qwen2.5:3b-instruct`）：
  - 计算阶段调用 `calculate_payroll`，耗时约 10s；
  - 验证阶段调用 `validate_payroll`，耗时约 7s；
  - 核算阶段约 5s；
  - 最终答复：应发 44,235.63、扣款 16,574.24、实发 27,661.39、企业成本 58,973.13、
    个税 4,915.35、验证结论“通过”。
- 浏览器实测：三个智能体区块依次出现并标记完成，工具状态“成功”，最终答复进入回答气泡。

## 10. 已知限制与后续建议

1. 多用户场景已通过 `ChatSessionManager` 按会话创建独立 `PayrollChat` 实例解决；
   同一会话内仍串行处理，避免同一上下文被并发写入。
2. 三个智能体共享同一个 `Model` 实例，但各持有一份裁剪后的独立 `Toolkit`；
   模型服务异常时三阶段会一起失败。
3. 验证对象来自工具参数解析，若模型用非标准参数名调用工具，可能提取不到对象；
   此时会跳过验证并在日志中体现。
4. 批量场景只抽检前 3 名员工，其余依赖 `validate_payroll` 的汇总结果。
5. 前端暂不支持手动折叠单个智能体区块；验证记录默认展示在思考面板内。
6. 查询类问题（员工清单/档案/政策/考勤）会跳过核算智能体，前端只显示计算与验证两个区块；
   工资核算仍固定经过三个智能体。
6. 改动尚未提交；提交前建议复核 `git diff` 并确认是否需要更新版本号。

## 11. 快速排障

| 现象 | 排查 |
| --- | --- |
| 一直停在计算阶段 | 查看 `[多智能体][calculator]` 日志与 Ollama 服务 |
| 验证区块显示“本轮无需校验” | 计算阶段没有提取到 `period` 或核算对象，检查工具参数 |
| 答复提示“本轮未完成独立验证” | 验证阶段异常或输出为空，查看 `[多智能体][validator]` 与 error 堆栈 |
| 前端没有智能体区块 | 检查 SSE 是否收到 `agent_start`，以及 `app.js` 是否被缓存 |
| 登录后立即跳回登录页 | 检查 Cookie/会话是否被清空、`app_users.active` 是否为真 |
| 会话列表为空或看不到他人会话 | 会话按 `user_id` 隔离，属预期；确认登录账号 |
| 同一会话提示“正在生成回答” | 同一会话不允许并发流，等待上一轮结束或切换窗口 |
| 日志过多 | 调整 `logback.xml` 根级别；`Toolkit` 已静音 |

## 12. 向量记忆（2026-11-07）

设计与实测细节见 [`vector-memory-spec.md`](vector-memory-spec.md)，这里只列接手要点。

- 开关：`payroll.memory.enabled` / `PAYROLL_MEMORY_ENABLED`，默认 `false`；
  开启前需 `ollama pull nomic-embed-text`，并确保 ES `127.0.0.1:9200` 可访问。
- 写入：用户消息落 H2 后异步提交；摘要压缩成功后替换旧摘要向量；助手答复不入索引。
- 召回：每轮对话前按 `user_id` 检索，topK=5、余弦阈值 0.55，最多注入 1200 字，失败返回空串。
- 重建：`POST /api/admin/vector/rebuild`（`ROLE_MANAGE`）清空索引后按 H2 历史重灌。
- 关键日志前缀 `[向量]`：启动探测、召回耗时/命中数、注入字数、重建条数。
- 排查顺序：`[向量] 未启用/初始化失败` → ES 与模型连通性 → `_count` 文档数 → 召回阈值。
- 版本约束：`elasticsearch-java` 与 `elasticsearch-rest-client` 必须同为 7.17.28；
  同时已排除 Spring Boot 的 `ElasticsearchClientAutoConfiguration`。

## 13. MCP 工具（2026-11-07）

- 入口：`PayrollAgentFactory.createTeam` 构建计算 Toolkit 后调用
  `McpToolRegistrar.registerAll(...)`；MCP 只挂计算智能体，验证与核算阶段不可见。
- 配置：环境变量 `PAYROLL_MCP_SERVERS`（JSON 数组），支持 `stdio` / `sse` / `http`、
  `headers`、`allowedTools`、`timeoutSeconds`；为空表示不启用。
- 隔离：注册前对 Toolkit 现有工具名做快照，同名工具保留本地实现；`allowedTools`
  为空才放行该服务全部工具；单个服务初始化失败只 `warn` 并跳过。
- 生命周期：注册成功的客户端由 `PayrollAgentFactory.Team.close()` 统一关闭；
  Team 按会话创建，因此每个活跃会话各持一组 MCP 连接。
- 测试：`McpToolRegistrarTest` 使用假 `McpClientWrapper` 覆盖注册后转发、白名单过滤、
  同名冲突、故障隔离与配置解析；未依赖真实 MCP 服务。
- 注意：`Toolkit.callTool` 会先用 `ToolUseBlock.content` 做参数校验；测试调用 MCP 工具时
  需同时设置 `input` 与 `content`，否则会得到参数校验失败而不是转发到远端。
- 关键日志前缀 `[MCP]`：注册成功/失败、白名单、同名冲突、客户端关闭异常。

## 14. 角色 Skill 外置与 Nacos（2026-11-07）

按确认方案采用 **AgentScope 动态技能**（模型按需加载），Nacos 不可用或缺少配置时
**逐个技能回退代码内置提示词**，不阻断启动。

- 提示词拆分：
  - `RoleSkills` 保存三个角色的职责、工具选择与输出格式，作为内置兜底；
  - `PayrollAgentFactory.COMMON_RULES` 仍在系统提示词中固定金额安全规则；
  - 系统提示词 = `COMMON_RULES` + “强制第一步加载技能”指令（含精确 `skillId`）。
    模型必须先调用 `load_skill_through_path(skillId="<name>_<source>", path="SKILL.md")`。
- 仓库与回退：
  - `NacosSkillRepository`：dataId `<skill-id>.md`，`MarkdownSkillParser` 解析 frontmatter，
    TTL 缓存（默认 60s，负结果同样缓存，避免 Nacos 故障时每轮重试）；
  - `BuiltInSkillRepository`：只读，来自 `RoleSkills`；
  - `CompositeSkillRepository`：按顺序合并，同名技能 Nacos 优先、缺失回退内置；
  - `SkillRepositories.create()`：读取 `PAYROLL_NACOS_*` 环境变量并组装，启动日志给出实际来源。
- 工具门禁：业务工具通过 `Toolkit.createSkillToolGroup(..., false, skill.getName())` 挂到未激活的
  `role-tools` 组。**激活键必须是技能的裸名称**（如 `payroll-calculator`），不能传带来源后缀的
  完整 skillId：AgentScope 的 `SkillToolFactory.activateSkill` 内部用
  `findSkillToolGroupsByActivateOnSkill(skill.getName())` 匹配，传 `payroll-calculator_nacos`
  会导致技能加载成功但业务工具永远不可见。技能加载成功后 `Toolkit.updateToolGroups` 激活该组，
  业务工具才出现在模型工具列表。因此“必须加载技能”由工具可见性强制，而不是只靠提示词。
  MCP 工具在门禁之后注册，不受该组约束。
- 技能过滤：`SkillFilter.only("<name>_nacos", "<name>_builtin")`，运行期切换来源不会被过滤掉；
  门禁用裸名称，与来源无关，Nacos 上线/下线不会破坏已创建会话的工具激活。
- 生命周期：`PayrollChatService` 按会话创建 `Team`，技能仓库与缓存随之创建；`close()` 为空操作，
  多智能体共享同一仓库实例是安全的（Nacos 缓存使用 `ConcurrentHashMap`）。
- 模板与发布：`docs/nacos-skills/` 下的 3 个 `.md` 与发布命令；内容以 `RoleSkills.java` 为准，
  修改内置提示词后需重新生成模板并发布。

### 实测记录

- 本机 Nacos 2.1.1（`D:\nacos`，JDK 17，`startup.cmd -m standalone`）监听 8848。
- 发布 group=`payroll-agent` 的 3 个 dataId 后，启动日志：

  ```
  [Skill] 角色提示词优先读取 Nacos http://127.0.0.1:8848（group=payroll-agent，TTL=60s），缺失时回退代码内置版本
  [Skill] 从 Nacos 加载技能 payroll-calculator（579 字）
  [Skill] 生效技能：[payroll-calculator(nacos), payroll-validator(nacos), payroll-accountant(nacos)]
  ```

- 把 `PAYROLL_NACOS_SERVER` 指向未监听端口：三个技能全部输出 `回退代码内置版本`，`describe` 返回 `(builtin)`。
- 真实 CLI 对话（qwen2.5:3b + Nacos）中，计算智能体首个工具调用为
  `load_skill_through_path`，参数 `{"skillId":"payroll-calculator_nacos","path":"SKILL.md"}`，
  `state=SUCCESS`，证明外置技能已进入模型上下文。
- 排查发现首版门禁用完整 skillId 注册，技能加载后没有
  `Activated skill-bound tool group` 日志，业务工具始终不可见。改为裸名称后，
  日志出现 `Activated skill: payroll-calculator_builtin` 与
  `Activated skill-bound tool group: role-tools for skill: payroll-calculator`，
  第二次模型调用即可看到 `calculate_payroll`。
- 注意：同一个 3B 模型加载技能后仍可能漏调 `calculate_payroll` 并追问工号；这是模型能力问题，
  不影响金额安全（金额只来自确定性引擎）。生产建议 7B 以上或在线模型。

### 测试

- `NacosSkillRepositoryTest`：frontmatter 解析、无 frontmatter 回退描述、TTL 缓存/刷新、
  单技能缺失跳过、只读语义。
- `NacosConfigClientTest`：`HttpServer` 模拟 200/404/500、命名空间与登录 token 复用。
- `CompositeSkillRepositoryTest`：Nacos 覆盖、逐技能回退、名称去重、关闭来源。
- `DynamicSkillWiringTest`：系统提示出现 `<available_skills>` 与 `payroll-calculator_builtin`；
  门禁组按裸名称注册；脚本化模型先调用 `load_skill_through_path`，验证第二次模型调用
  已能看到 `calculate_payroll`（覆盖本次激活键缺陷）。
- 全量 `mvn test`：85 个测试通过。

### 已知限制

1. 技能 ID 含来源后缀（`_nacos` / `_builtin`），系统提示在创建会话时写入精确 ID。
   门禁组按裸名称注册，来源切换不影响工具激活；但若首选来源在会话期间不可用，
   模型需按提示用 `<available_skills>` 中的另一个 ID 重试一次。
2. 模板文件是快照，需随 `RoleSkills` 手动同步。
3. MCP 工具未纳入技能门禁；如需完全一致的“先加载再调用”，可把 MCP 注册也放进同一工具组。
4. Nacos v1 OpenAPI 默认无鉴权；生产需开启鉴权并配置 `PAYROLL_NACOS_USERNAME/PASSWORD`。

## 15. 审计日志基础设施（2026-11-07，分支 `feature/payroll-approval-audit-trace`）

已完成“所有写接口 + 登录”审计，审批链与对话金额追溯尚未开始。

### 数据与写入链路

- 表 `audit_log`：`occurred_at`、`actor_id`、`actor_name`、`action`、`resource_type`、
  `resource_id`、`before_json`、`after_json`、`payload_json`、`ip`、`user_agent`、
  `result`、`status_code`、`message`、`trace_id`，按时间、操作人、资源建索引。
  `payload_json` 是对通用请求事件的补充，显式业务事件仍用前后快照。
- `AuditFilter`（`OncePerRequestFilter`）只处理 `/api/**` 的 POST/PUT/DELETE：
  建立 `AuditContext`（traceId、actor、IP、UA），缓存 32KB 以内的 JSON 请求体；
  请求结束后若控制器没有显式记录，就补一条 `post /api/...` 事件。
- `AuditContext` 是 ThreadLocal；`AuditService.record(...)` 写显式事件并标记 `recorded`，
  过滤器据此避免重复。`ApiExceptionHandler` 与 `AuthInterceptor` 把 400/401/403 的
  中文原因写回上下文，失败请求也有可读 `message`。
- `AuthController` 显式记录 `auth.login`（成功含角色，失败只留尝试用户名与统一提示）、
  `auth.logout`、`auth.password`；密码永远不进入快照或请求体。
- 业务显式事件：`employee.save/deactivate`、`review.save/delete`、`attendance.save`、
  `policy.save`、`payroll.finalize/send`、`import.excel`、`user.*`、`role.*`、
  `department.*`、`vector.rebuild`。
- 脱敏：`AuditSanitizer` 递归替换 `password|old_password|new_password|password_hash|
  bank_account|bank_account_masked|id_card|token|secret|authorization` 为 `***`；
  非 JSON 请求体不入库。
- 查询/导出：`GET /api/audit`、`GET /api/audit/export`，均需 `audit:read`；
  CSV 带 UTF-8 BOM，导出上限 1 万条。
- 权限：`Permissions.AUDIT_READ` 只进入 ADMIN（`ALL`），HR/VIEWER 不含。
  内置角色权限在每次启动时按代码定义刷新，旧库无需手工补权限。
- 保留：`payroll.audit.retention-days`（默认 180），启动时清理更早记录。
- 关键文件：`web/AuditFilter.java`、`web/AuditController.java`、`service/AuditService.java`、
  `db/H2AuditStore.java`、`support/AuditContext.java`、`support/AuditSanitizer.java`。

### 测试与限制

- 新增 `AuditServiceTest`（6）、`AuditFilterTest`（4）、`AuditWebTest`（2）；
  全量 `mvn test` 99 个测试通过。`AuditWebTest` 覆盖失败登录、登录成功、写接口、
  退出、`result=FAILURE` 筛选与 CSV 导出。
- 限制：
  1. 保留期清理只在启动时执行；长驻进程需补定时任务或外部归档。
  2. 列表为 offset 分页，数据量大时应改为按 `audit_id` 游标。
  3. 审计写入失败只打 WARN，不阻断业务；生产应对接日志平台并告警。
  4. `/api/runs/preview` 与 `/api/chat*` 也是 POST，会产生通用事件；
     若嫌噪声可在 `AuditFilter.shouldNotFilter` 中排除。
  5. 管理台尚未提供审计查询页面，当前通过 API/CSV 查看。
- 下一步：任务 #49 可配置审批链与批次状态机。审批动作应直接调用
  `AuditService.record("payroll.approve", "approval_request", id, before, after)`，
  并复用 `AuditContext` 的 actor/IP。

## 16. 可配置审批链与批次状态机（2026-11-07，分支 `feature/payroll-approval-audit-trace`）

任务 #49 已完成：批次出账前必须走“提交审批 → 审批通过”，审批链可在管理台配置。

### 状态机

```
DRAFT / REJECTED --submit--> PENDING_APPROVAL --approve--> APPROVED --finalize--> FINALIZED --send--> SENT
                                     |
                                  reject
                                     v
                                  REJECTED
```

- `PayrollRunStatus` 定义状态常量与 `isFinal`；出账只接受 `APPROVED`。
- `REJECTED` 可再次提交；`PENDING_APPROVAL`/`APPROVED` 不能重复提交（并发时由 SQL
  `WHERE status IN (...)` 兜底，失败抛 `PayrollConflictException` → 409）。

### 数据模型

- `approval_flows`：`name`、`scope_type`（`GLOBAL`/`DEPARTMENT`）、`scope_value`
  （全局固定 `*`）、`min_amount`、`max_amount`、`priority`、`enabled`、`description`、
  `created_at`、`updated_at`。同一 `(scope_type, scope_value)` 只允许一条 `enabled` 流程。
  首次建库插入“默认审批链”（GLOBAL、0 起、无上限、优先级 100）。
- `payroll_runs` 新增 15 列：`approval_flow_id/name`、`submitted_by_id/name/at`、
  `submit_comment`、`approved_by_id/name/at`、`approval_comment`、
  `rejected_by_id/name/at`、`rejection_reason`、`snapshot_hash`。
  使用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，旧库自动升级。
- `payroll_approval_events`：追加式轨迹（`SUBMIT`/`APPROVE`/`REJECT`），
  记录 actor、flow 快照、备注与当次金额指纹。

### 金额指纹与校验

- `ApprovalService.snapshotHash(period, scope, items)`：按工号排序，
  对 `period|scope` 与每人的 `adjustment_json|result_json` 取 SHA-256。
- 提交时写入 `snapshot_hash`；审批时用库中明细重算并比对。
- 出账时 `PayrollRunService` 先调用确定性引擎全员重算，再比对审批指纹；
  任何考勤、考核、政策、微调变化都会返回 409“核算结果与审批快照不一致”，
  必须驳回或重新提交审批。

### 权限与接口

- 新增权限：`payroll:approve`（HR、ADMIN）、`approval:manage`（仅 ADMIN）。
- 批次接口：`POST /api/runs/{id}/submit-approval`（`payroll:write`）、
  `POST /api/runs/{id}/approve|reject`（`payroll:approve`）、
  `GET /api/runs/{id}/approvals`（`payroll:read`）。
- 审批链接口：`GET/POST /api/approval/flows`、`PUT/DELETE /api/approval/flows/{id}`
  （`approval:manage`），分别记录 `approval_flow.create/update/delete` 审计事件。
- 提交人不能审批或驳回自己提交的批次；关闭认证（系统账号 id=0）时跳过该检查，
  便于本地演示与既有测试。
- 管理台：工资批次面板新增“提交审批/审批通过/驳回”按钮与审批信息/轨迹；
  新增“审批链”页签，可维护范围、金额区间、优先级与启用状态。

### 关键文件

- `domain/PayrollRunStatus.java`、`PayrollApproval.java`、`PayrollApprovalEvent.java`、
  `ApprovalFlow.java`、`PayrollConflictException.java`
- `db/H2ApprovalStore.java`、`db/H2PayrollRunStore.java`、`db/PayrollDatabase.java`
- `service/ApprovalService.java`、`service/PayrollRunService.java`
- `web/ApprovalController.java`、`web/PayrollWebController.java`、`web/ApiExceptionHandler.java`
- `support/Permissions.java`、`static/index.html`、`static/app.js`、`static/style.css`

### 测试

- 新增 `ApprovalServiceTest`（8）、`H2ApprovalStoreTest`（5）、`ApprovalWebTest`（3）；
  `PayrollRunServiceTest` 与 `PayrollAdminWebTest` 改为先提交/审批再出账。
  全量 `mvn test` 116 个测试通过。
- 覆盖：提交/通过/驳回/重新提交、自批与自驳拦截、审批轨迹、
  提交后明细变化检测、审批后考勤变化导致出账冲突、部门流程优先、无流程报错、
  同范围唯一生效、金额区间、HTTP 权限与驳回原因必填。

### 已知限制

1. 审批链只按“部门 + 批次总额”匹配，尚未支持多级会签、或签与按组织架构解析审批人。
2. 一个范围只允许一条生效流程；如需按金额分档，需在同一流程上设置区间或扩展为多级模型。
3. 提交后预调微调不落库（`preview` 直接生成草稿），重新预览会产生新批次，旧批次需人工清理。
4. 管理台审批使用 `prompt()` 输入备注/驳回原因，后续可替换为带校验的弹窗。
5. 微信风格审批卡片、审批超时提醒与催办尚未实现。

### 下一步

- 任务 #50：对话金额工具调用级追溯（把智能体工具结果与会话消息关联）。
- 任务 #51：回归测试与交接文档整合；任务 #50 完成后再统一跑一次全量回归。

## 17. 对话金额工具调用级追溯（2026-11-07，分支 `feature/payroll-approval-audit-trace`）

任务 #50 已完成：每条助手消息都能展开查看“计算依据”，包含工具、参数、结果、
金额、员工、期间与部门。追溯只随会话保存，不写审计日志。

### 采集方式

- AgentScope 2.0.1 的 `ToolResultEndEvent` 只有 `toolCallId/toolCallName/state`，
  **没有结果内容**，因此结果必须在工具方法内部采集。
- `PayrollTools` 的所有内置工具统一走 `execute(toolName, args, supplier)`：
  记录参数、完整结果与耗时写入 `ToolTraceRecorder`（FIFO，按工具名配对）。
- 编排层收到 `ToolResultEndEvent` 时调用 `traceRecorder.take(toolName)` 认领最早一条记录，
  补上 `tool_call_id` 与当前阶段（`calculator/validator/accountant`），形成 `ChatToolTrace`。
- MCP 等外部工具没有采集记录，退化为“事件参数 + `ToolTraceExtractor`”，结果为空、耗时 0。
- 每个阶段结束（`finally`）清空 recorder，避免失败/中断调用串到下一阶段。
- 同名工具并发风险：AgentScope 工具调用按顺序串行执行，FIFO 足以配对；
  若未来改为并行工具调用，需要在事件里补充结果或改用 tool_call_id 直接关联。

### 金额与证据提取

`ToolTraceExtractor` 从参数与 `result.data` 中提取：

- 金额白名单：`base_salary`、`gross_pay`、`attendance_deduction`、`taxable_gross`、
  个人/单位社保与公积金、`total_deductions`、`net_pay`、`employer_cost`、
  `total_gross`、`total_net`、`total_employer_cost`、`cumulative_tax`、`tax_this_month`、`tax`。
- 员工：对象里的 `employee_id`、数组里的字符串或对象的 `employee_id`，最多 50 个。
- 期间/部门：优先取参数，其次取结果。
- 批量结果只取 `totals` 顶层合计；`records` 逐人明细不展开金额，避免重复且无法归属。
- 结果超过 8KB 时截断并追加“（已截断）”，`truncated=true`；
  **金额提取始终基于截断前的完整结果**。

### 持久化

- 新表 `chat_tool_traces`：`trace_id`、`message_id`、`session_id`、`tool_call_id`、
  `tool_name`、`agent`、`args_json`、`result_json`、`truncated`、`result_state`、
  `duration_ms`、`employees_json`、`period`、`department`、`amounts_json`、`created_at`。
  索引：`(message_id, trace_id)`、`(session_id)`。
- `H2ChatStore.appendAssistant(...)` 在同一事务里插入助手消息与全部追溯，
  避免“有消息无依据”；失败整体回滚并返回 `null`，SSE 仍正常结束。
- `clear(sessionId)` 与 `delete(sessionId)` 都会删除对应追溯，防止孤立数据。
- `ChatSessionManager.complete(...)` 返回保存后的 `message_id`，供 `done` 事件携带。

### SSE 与接口

- `tool_result` 新增：`duration_ms`、`amounts`、`employees`、`period`、`department`、`truncated`
  （空值不下发）。
- `done` 新增：`message_id`、`trace_count`。
- 新增 `GET /api/chat/sessions/{sessionId}/messages/{messageId}/traces`
  （`chat:use`），先用 `requireOwned` 校验会话归属，再按 `session_id + message_id` 查询；
  跨用户或会话已删除返回 400。

### 前端

- 助手回答下方新增 `<details class="chat-traces">`“计算依据 · N 次工具调用”。
- 实时回答：`done.message_id` 到达后按需拉取追溯，避免把大段 JSON 挤进 SSE。
- 历史消息：`appendStoredMessage` 渲染后异步拉取，失败只打 `console.warn`，不影响正文。
- 每个工具卡片显示：状态、耗时、金额项数、智能体阶段、期间/部门、员工 chips、
  金额卡片（中文标签 + `¥` 千分位）、参数与结果的格式化 JSON。
- 样式与“思考过程”面板保持一致的微信风格，金额网格自适应列宽。

### 调试日志

- `[多智能体][stage] 工具结束：tool，state，耗时，参数，金额，员工，期间`：逐次调用证据。
- `[多智能体] 工具追溯汇总：N 次调用，金额类 M 次`：每轮结束汇总。
- `[会话] 回答已保存：sessionId，messageId，工具追溯 N 条`：确认持久化成功。
- 前端 `[计算依据] 加载失败：...`：历史追溯接口失败时保留正文。

### 关键文件

- `support/ToolTraceExtractor.java`、`agent/ToolTraceRecorder.java`、`agent/PayrollTools.java`
- `domain/ChatToolTrace.java`、`service/PayrollChatService.java`
- `db/H2ChatStore.java`、`db/PayrollDatabase.java`、`service/ChatSessionManager.java`
- `web/ChatSse.java`、`web/ChatSessionController.java`
- `static/app.js`、`static/style.css`

### 测试

- 新增 `ToolTraceExtractorTest`（4）、`ToolTraceRecorderTest`（4）、
  `H2ChatTraceStoreTest`（3）、`ChatTraceWebTest`（1）。
- 覆盖：金额/员工/期间提取、批量只取合计、FIFO 配对、未知工具、8KB 截断与金额完整性、
  消息+追溯同事务、跨会话隔离、清空/删除级联、SSE 字段、依据接口与跨用户 400。
- 全量 `mvn test` 128 个测试通过。

### 已知限制

1. 追溯依赖工具内部埋点；第三方 MCP 工具只有参数，没有结果内容。
2. 金额提取基于字段白名单，工具返回结构改名后需要同步维护。
3. 单条结果最多保存 8KB，超长明细仍需回到工资批次/工资条查看。
4. 未提供按金额、员工反查追溯的检索接口；当前只能按消息读取。
5. 管理台未做追溯导出，也未把追溯接入审计日志（按任务决策）。

### 下一步

- 任务 #51：回归测试与交接文档整合（见下一节）。
- 可选增强：追溯反查（按员工/期间/金额筛选）、Excel/PDF 依据导出、
  MCP 工具结果采集（若 AgentScope 后续事件暴露结果内容）。

## 18. 全量回归与交接验收（2026-11-07，分支 `feature/payroll-approval-audit-trace`）

任务 #51 完成：审批、审计、追溯三条链路统一回归，测试矩阵与人工验收步骤固化如下。

### 需求覆盖矩阵

| 需求 | 测试 | 关键断言 |
| --- | --- | --- |
| 审批状态机 | `ApprovalServiceTest` | 提交→通过→出账；重复提交/非法状态返回冲突；驳回后可重新提交 |
| 自审拦截 | `ApprovalServiceTest`、`ApprovalWebTest` | 提交人不能审批或驳回自己的批次；关闭认证时跳过 |
| 金额变化失效 | `ApprovalServiceTest`、`PayrollRunServiceTest` | 提交后改明细拒绝审批；审批后改考勤出账 409；失败员工阻断提交 |
| 审批链配置 | `ApprovalServiceTest`、`H2ApprovalStoreTest` | 部门优先于全局、金额区间、同范围唯一生效、默认流程 |
| 审批权限 | `ApprovalWebTest` | HR 可提交不可审批、管理员可审批出账、只读用户 403 |
| 审计脱敏 | `AuditServiceTest`、`AuditFilterTest` | 密码/银行卡/证件/令牌 → `***`；请求体与前后快照都脱敏 |
| 审计权限与导出 | `AuditWebTest`、`AuditServiceTest` | 未登录 401、无 `audit:read` 403、筛选分页、CSV 最多 1 万条 |
| 追溯采集与提取 | `ToolTraceExtractorTest`、`ToolTraceRecorderTest` | 金额/员工/期间；批量只取顶层合计；FIFO 配对；8KB 截断后金额完整 |
| 追溯持久化 | `H2ChatTraceStoreTest` | 消息+追溯同事务；跨会话隔离；清空/删除级联 |
| 追溯 SSE 与接口 | `ChatTraceWebTest` | `tool_result` 金额/耗时字段；`done.message_id/trace_count`；跨用户 400 |
| 会话隔离回归 | `PayrollAuthWebTest`、`ChatTraceWebTest` | 不同用户/窗口会话与消息互不可见；依据接口同样校验归属 |

### 全量回归

```bash
JAVA_HOME="D:\tools\jdk\jdk-17.0.12" mvn test
```

- 29 个测试类、128 个测试全部通过（Surefire `Failures: 0, Errors: 0`）。
- 关键回归类可单独执行：

```bash
mvn -Dtest=ApprovalServiceTest,ApprovalWebTest,AuditWebTest,\
AuditServiceTest,AuditFilterTest,PayrollRunServiceTest,\
ToolTraceExtractorTest,ToolTraceRecorderTest,H2ChatTraceStoreTest,\
ChatTraceWebTest test
```

### 人工验收步骤

1. 启动管理台（本地可用内存库，避免污染正式数据）：

   ```bash
   PAYROLL_AUTH_ENABLED=false PAYROLL_DB_URL="jdbc:h2:mem:verify;DB_CLOSE_DELAY=-1" \
   PAYROLL_WEB_PORT=18080 java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --web
   ```

2. 打开“智能问答”，提问“张伟 2026-10 的工资是多少”。
3. 回答完成后展开“计算依据”：应看到 `get_employee_profile` / `calculate_payroll`
   等工具卡片、¥金额卡片、员工与期间；参数与结果为格式化 JSON。
4. 刷新页面或切换会话再切回：历史消息下方仍能异步加载同一份依据。
5. 用另一账号访问同一 `sessionId + messageId`：接口返回 400。
6. 删除会话后再次查询依据：接口返回 400，库中追溯随会话级联删除。
7. 服务端日志应出现 `[多智能体][calculator] 工具结束：…金额=…员工=…期间=…`、
   `[多智能体] 工具追溯汇总：N 次调用，金额类 M 次`、`[会话] 回答已保存：…工具追溯=N条`。

> 内置浏览器在某些环境不提供可见视口（0×0），只能做 DOM 结构断言，无法截图核对像素样式；
> 本次已确认面板节点、金额卡片与 JSON 区块数量，视觉走查需在常规浏览器完成。

### 交接注意事项

- 提交审批前必须确认批次已生成且无失败员工；审批后任何考勤/考核/政策/微调变化都会使出账冲突。
- 追溯表不参与审计，不要把它当作合规留痕；合规查询仍走 `/api/audit`。
- 工具结果字段改名时同步维护 `ToolTraceExtractor` 白名单，否则金额卡片会变空。
- 新增工具必须走 `PayrollTools.execute(toolName, args, supplier)`，否则结果无法落库。
- 分支 `feature/payroll-approval-audit-trace` 已包含 #49/#50/#51；PR #1 指向 `main`，
  后续提交推送前需再次确认。


