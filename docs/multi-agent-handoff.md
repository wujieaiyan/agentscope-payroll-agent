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
- 召回：每轮对话前默认按 `session_id` 检索（`payroll.memory.cross-session=false`），
  显式开启 `PAYROLL_MEMORY_CROSS_SESSION=true` 才按 `user_id` 跨会话检索；
  topK=5、余弦阈值 0.55，最多注入 1200 字，失败返回空串。
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

## 19. Agent 管理（2026-11-08，分支 `feature/agent-management`）

管理台新增“Agent 管理”页签：维护专家智能体定义（名称、头像、描述、标签、绑定技能、
可见范围、上下架状态），整个列表存储为 Nacos 配置 `payroll-agents.json`。
当前只做管理与展示，尚未接入对话编排。

### 存储与回退

- `AgentDefinition`（record）字段：`id/name/avatar/description/tags/skillIds/
  visibility/status/createdBy/createdAt/updatedAt`，JSON 使用 camelCase。
- `NacosAgentRegistry`：
  - `list()` 带 TTL 缓存（复用 `PAYROLL_NACOS_SKILL_TTL_SECONDS`），Nacos 不可用、
    dataId 缺失或 JSON 损坏时回退三个内置智能体（计算/复核/入账）。
  - `save()` 按 `id` 新建或更新；更新保留 `createdBy/createdAt`，刷新 `updatedAt`；
    新建 id 为 `agent-<8 位>`，创建人取当前登录用户。
  - `delete()` 重写完整列表。
  - 未启用 Nacos（`PAYROLL_NACOS_SERVER` 为空）时 `writable=false`，写操作抛
    `PayrollException`，管理台禁用按钮。
- `NacosConfigClient` 新增 `publish`/`remove`，复用现有 token 刷新与超时逻辑；
  发布失败返回 `false`，由注册表转成中文异常，不修改本地缓存。
- 首次保存会把“当前列表（含内置默认项）+ 修改”整体写入 Nacos；多实例并发编辑为
  最后写入覆盖，暂无版本号/乐观锁。

### 接口与权限

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/agents` | 列表，支持 `keyword/tag/skillId/status/visibility` |
| GET | `/api/admin/agents/meta` | `writable`、`location`、已有标签 |
| GET | `/api/admin/agents/skills` | 可绑定技能（名称、描述、来源） |
| POST | `/api/admin/agents` | 新建 |
| PUT | `/api/admin/agents/{id}` | 修改 |
| DELETE | `/api/admin/agents/{id}` | 删除 |

- 新权限码 `agent:manage`，仅内置 `ADMIN` 拥有；HR/VIEWER 访问返回 403。
- 校验：名称 ≤30 字、描述 ≤200 字、头像 ≤8 字符、标签 ≤6 个且单个 ≤12 字；
  技能必须存在于当前技能仓库；`visibility` 仅 `PUBLIC/PRIVATE`，`status` 仅
  `ONLINE/OFFLINE`；空白字段归一化（头像默认 🤖、可见范围默认公开、状态默认下架）。
- 审计：`agent.create` / `agent.update` / `agent.delete`，修改与删除带 before 快照。
  失败请求仍由 `AuditFilter` 兜底记录。

### 前端

- 新增页签与 `agents.js`，使用本地 vendored Vue 3（`static/vendor/vue.global.prod.js`），
  不引入构建链；复用 `app.js` 的 `api()/toast()/showError()`。
- 卡片网格 + 关键字/标签/技能/状态/范围筛选；弹窗编辑名称、头像、枚举、标签与技能多选。
- Nacos 未启用时顶部提示“存储：代码内置（未启用 Nacos，只读）”，新增/编辑/删除按钮禁用。
- 注意：`initAgentsTab` 必须保存 `createApp(App).mount(...)` 的返回值（组件代理）才能调用
  `reload()`；保存 app 实例会导致二次进入页签时报 `reload is not a function`。

### 日志

```
[Agent] Nacos 未提供 payroll-agents.json，使用内置默认智能体
[Agent] 从 Nacos 加载 3 个智能体定义
[Agent] 已保存智能体配置（共 4 个）
[Skill] 发布配置 payroll-agents.json 失败：HTTP 500
```

### 测试

- 新增 `NacosAgentRegistryTest`（8）：假 Nacos HTTP 服务覆盖回退、保存/重载、更新保留创建信息、
  删除、损坏 JSON、TTL 缓存、未启用只读、写入失败。
- 新增 `AgentAdminControllerTest`（8）：过滤、归一化、枚举/长度/技能校验、更新/删除不存在、
  审计前后快照、meta。
- 新增 `AgentAdminWebTest`（3）：camelCase 序列化、meta/skills、未登录 401、HR 403、
  校验 400、审计兜底。
- 全量：`JAVA_HOME="D:\tools\jdk\jdk-17.0.12" mvn test` → **31 个测试类、145 个测试全部通过**。

### 人工验收

1. 启动本机 Nacos 后，用环境变量启动管理台：

   ```bash
   PAYROLL_NACOS_SERVER=http://127.0.0.1:8848 \
   PAYROLL_DB_URL="jdbc:h2:mem:agent-verify;DB_CLOSE_DELAY=-1" \
   PAYROLL_WEB_PORT=18080 java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --web
   ```

2. 管理员登录，打开“Agent 管理”：应显示三个内置智能体，顶部提示 Nacos 存储位置。
3. 新建一个智能体，绑定 `payroll-calculator`，保存；刷新页面仍存在。
4. 在 Nacos 控制台查看 `payroll-agents.json`：应包含内置项与新增项。
5. 修改/删除该智能体，确认列表与 Nacos 内容同步，审计日志出现
   `agent.create/agent.update/agent.delete`。
6. 用 HR 账号访问页签或直接请求接口：应看不到页签，接口返回 403。
7. 取消 `PAYROLL_NACOS_SERVER` 重启：页签只读，保存按钮禁用，列表仍显示内置智能体。

### 已知限制与下一步

1. 智能体定义尚未接入对话编排；后续可让用户选择智能体，或按技能组合生成子智能体。
2. 单 JSON 配置、最后写入覆盖，缺少版本号/乐观锁与多实例冲突提示。
3. `visibility=PRIVATE` 目前只做展示，读取接口仍只对 `agent:manage` 开放，没有按用户可见性过滤。
4. 绑定技能来自当前技能仓库（Nacos 优先、内置回退），技能被删除后旧定义不会自动清理。
5. 前端使用 vendored Vue 3 与原生 `confirm`，后续可替换为带校验的弹窗并补充无障碍支持。




## 20. 智能体流程编排（2026-11-08，分支 `feature/agent-orchestration`）

> 本节补上 §19 的“已知限制 1”：智能体定义现已接入对话编排。

### 需求与结论

管理台新增“流程编排”页签：用 Vue Flow 拖拽画布把已注册智能体与条件节点连成 DAG，
管理员把某条流程设为全局默认后，下一轮对话按该流程执行。已确认的取舍：

| 决策点 | 结论 |
| --- | --- |
| 范围 | 完整 DAG：分支、汇合、多流程、全局默认流程 |
| 节点 | 开始 / 智能体 / 条件 / 结束；条件只允许预设枚举 |
| 编辑器 | 本地 vendored Vue Flow 1.42.1 + Vue 3.5.45，无构建链 |
| 生效 | 全局默认流程，保存/激活后下一轮对话生效 |
| 并行 | 同层就绪节点真并行，每节点独立智能体/工具/追溯 |

规格与 JSON 模型见 [agent-orchestration.md](agent-orchestration.md)。

### 关键文件

| 文件 | 作用 |
| --- | --- |
| `domain/FlowNode.java` / `FlowEdge.java` / `FlowDefinition.java` | camelCase 流程模型 |
| `service/FlowValidator.java` | 结构、引用、分支锚点、无环、可达性校验 |
| `service/AgentFlowExecutor.java` | 分层调度、条件求值、跳过传播、汇合输入与最终答复选择 |
| `service/SynchronizedChatStreamListener.java` | 并行节点 SSE 事件串行化 |
| `skill/NacosFlowRegistry.java` | `payroll-flows.json` / `payroll-flow-settings.json` 读写、内置流程 |
| `web/FlowAdminController.java` | `/api/admin/flows` CRUD、激活、审计 |
| `agent/PayrollAgentFactory.java` | `createFlowNodeAgent` / `createFlowModel`，按技能选择角色提示词 |
| `service/PayrollChatService.java` | 每轮解析默认流程；已配置但无法解析时直接报错，不静默回退 |
| `static/flows.js` + `style.css` + `index.html` | Vue Flow 画布、节点面板、属性面板 |

### 执行语义

1. 每轮对话读取 `activeFlow()`；为空时沿用三阶段流水线；已配置但结构非法、流程不存在、
   引用智能体缺失/下架或读取失败时抛出可定位错误并提示“已阻止回退薪酬链路”，
   不会静默切回薪酬角色。Nacos 短暂不可用时沿用上次成功加载的流程与智能体列表。
2. 从 START 出发，按“所有入边已确定”逐层推进；同层节点提交固定线程池并行执行。
3. 节点输入 = `【用户问题】` + 所有经**选中路径**可达的上游记录（按拓扑层级排序）。
   兄弟分支输出不会串入；汇合节点自然拿到多条分支结果。记录标题去掉“①②”编号，
   得到 `【计算智能体记录】` / `【验证智能体记录】`，与角色技能引用一致；
   已执行但无文本的节点保留 `（本节点未返回内容）` 占位，上游有核算对象时追加
   `【上游核算对象（最多处理前 3 个）】` 清单。实测中缺少这些结构会让小模型在
   验证/核算节点输出空文本。
4. 条件节点不调用模型：`HAS_CALCULATION_TARGETS` / `HAS_TOOL_ERROR` /
   `OUTPUT_CONTAINS`，只统计祖先节点结果。
5. 未选中的边标记阻断；无选中入边的节点标记 SKIPPED 并继续阻断下游；END 不执行模型。
6. 最终答复取 END 选中路径中拓扑层级最深的节点输出；同层多分支则按节点 id 拼接。
7. 每节点独立 `ReActAgent` + `PayrollTools` + `ToolTraceRecorder`，结束即关闭 MCP 客户端；
   模型实例每轮创建一次并由同层节点共享，避免重复探测 Ollama。
8. 单节点失败整轮失败并返回可定位错误；单阶段超时仍为 150 秒。

### 存储与权限

- 流程列表：Nacos `payroll-flows.json`；默认流程：`payroll-flow-settings.json`。
- Nacos 未启用：列表回退内置 `payroll-default`，页面只读，默认流程保持未设置。
- 新增权限 `flow:manage`；内置角色启动时按代码重同步，旧库无需迁移。
- 审计：`flow.create` / `flow.update` / `flow.delete` / `flow.activate`。

### 前端注意事项

- `flows.js` 与 `agents.js` 相同：保存 `app.mount()` 返回的组件代理（`flowsVm`），
  才能二次进入页签时调用 `reload()`。
- 条件节点通过 `sourceHandle=TRUE/FALSE` 区分“是/否”分支；前端两个锚点固定在节点右侧
  32%/68% 位置，属性面板只暴露预设条件与关键字。
- 未启用 Nacos 时画布仍可查看与拖动，但保存、删除、激活按钮禁用。
- `vendor/vue-flow-core.iife.js` 是下载后手工修正的生产构建：原始文件 `isDev()` 引用了
  未定义的 `production` 变量，每次进入页签都会抛
  `ReferenceError: production is not defined`（栈顶 `vendor/vue-flow-core.iife.js:4501`
  → `flows.js:520` → `app.js:234`）。现固定 `isDev()` 返回 `false`；替换该文件后需复查。
- 画布默认 `fitView` 会按整图缩放，节点较多时文字偏小；工具栏提供 `－ / ＋ / 适应画布`，
  也可滚轮缩放、拖拽平移。流程页采用“画布在左、流程列表与节点属性在右”的两列布局，
  让画布拿到最大宽度；`@media (max-width: 1100px)` 再退回单列。

### 管理台外壳（知识库风格）

外壳参考 `knowledge-base-console` 的布局与配色重做，修改界面时注意以下约定：

| 位置 | 约定 |
| --- | --- |
| `index.html` | `.app-layout` = 236px 深色侧边栏 + 主区；导航仍是 `nav#tabs > button[data-tab]` |
| `data-permission` | 页签与按钮的显隐唯一来源仍是 `applyPermissions()`；新增导航必须同时给 `data-tab` 与 `data-permission` |
| `app.js` `TAB_META` | 每个页签的分组、eyebrow、标题、副标题；`showTab()` 据此更新面包屑与页头，新增页签必须补条目 |
| `style.css` `:root` | 暖纸底 `--bg`、墨绿 `--primary`、黄铜 `--accent`、深色 `--sidebar`；组件只引用变量 |
| 聊天气泡 | 保留微信式左右布局（用户 `#95ec69`、助手白底、画布 `#f1efe8`），宽度上限 680px |
| 流程页 | `.flows-page` 用 grid areas：`main` 在左，`list` / `props` 在右 |

页面结构变更后至少回归：14 个页签切换、面包屑与用户卡片、登录页、≤900px 单列布局，
并确认控制台除登录页 `alreadyLoggedIn()` 的预期 401 外没有新增报错。

### 测试

- `FlowValidatorTest`（7）：内置流程、智能体引用、条件分支/关键字、环与死路、汇合、名称/端点。
- `AgentFlowExecutorTest`（12）：线性、真/假分支、工具错误、关键字、同层并行（CyclicBarrier）、
  汇合输入、空输出占位与核算对象清单、跳过传播、节点失败、缺失端点/无答复、坏图。
- `NacosFlowRegistryTest`（9）：未启用只读、配置缺失/损坏回退、保存/重载、更新保留创建信息、
  激活、删除保护、写入失败。
- `FlowAdminControllerTest`（7）：归一化、校验、更新路径 id、删除/激活、审计、列表与 meta。
- `FlowAdminWebTest`（3）：camelCase、401/403、结构校验 400、未知流程 400。
- 全量回归命令：`JAVA_HOME="D:\tools\jdk\jdk-17.0.12" mvn test`。

### 人工验收

1. 启动 Nacos 与管理台（`PAYROLL_NACOS_SERVER=http://127.0.0.1:8848`）。
2. 管理员登录 → “流程编排”：应看到内置“薪酬标准流程”，可拖拽/连线；保存后 Nacos 出现
   `payroll-flows.json`。
3. 新建一条包含条件分支的流程，保存并“设为默认”；刷新页面默认标记仍在。
4. 回到“智能问答”提问：日志出现 `[流程编排] 使用全局默认流程`、节点完成与条件判断行；
   查询类问题应只执行计算节点并直接返回记录。
5. 构造两个同层智能体节点，确认日志时间接近且线程名为 `flow-node-*`。
6. 下架某节点引用的智能体后提问：日志告警并回退既有链路，对话不中断。
7. 用 HR 账号访问：页签不可见，`/api/admin/flows` 返回 403。

### 已知限制

1. 流程按最后写入覆盖，没有版本号/乐观锁与多实例冲突提示。
2. 条件只支持三种预设，暂不支持数值比较或自定义表达式。
3. 节点级失败即整轮失败，没有“忽略失败继续”策略。
4. 画布未做自动布局；节点较多时需要手动整理位置。
5. `FlowDefinition.createdBy/createdAt` 由注册表维护，客户端传入值会被忽略。

## 21. 多级审批工作流（2026-11-08）

### 背景与边界

原审批链只按“部门 + 实发合计”匹配一条流程，命中后任何拥有 `payroll:approve` 的用户都能一次通过，
没有顺序、节点与待办概念。本次补齐“工作流”能力，但**不替代**既有批次状态机、金额指纹与审计：

- 审批仍在 `PENDING_APPROVAL` 内完成；末节点通过后才进入 `APPROVED`。
- 提交时把匹配到的节点列表写入批次快照；之后修改流程配置不影响在途批次。
- 审批仍禁止提交人自批/自驳，仍校验 SHA-256 快照。
- 没有节点的旧流程自动退化为单级审批（`ApprovalStep.permissionFallback()`），保持兼容。

### 数据与契约

| 位置 | 变化 |
| --- | --- |
| `approval_flow_steps` | 新表：`flow_id`、`step_order`、`name`、`approver_type(ROLE/USER)`、`approver_value`、`min_amount`、`max_amount` |
| `payroll_runs.workflow_steps_json` | 提交时冻结的节点快照（CLOB） |
| `payroll_runs.current_step` | 当前待处理节点下标；审批用 `WHERE current_step = ?` 做乐观并发 |
| `payroll_approval_events.step_order/step_name` | 记录每一步；中间节点事件为 `STEP_APPROVE`，末节点为 `APPROVE` |
| `ApprovalFlow.steps` | 有序节点列表；create/update 在事务内替换节点 |
| `PayrollApproval.workflowSteps/currentStep` | 详情与待办读取快照 |

匹配规则：`ROLE` 比对 `roleCode`，`USER` 比对 `username`；节点金额区间按批次 `total_net` 过滤；
所有节点都不匹配金额时报“未配置匹配的审批节点”。流程本身仍按“部门优先 → 优先级 → 创建顺序”选择。

接口：

- `GET /api/approval/tasks`：当前用户的待办（`payroll:approve`）。非系统账号只返回
  当前节点匹配、且提交人不是自己的批次；关闭认证的系统账号（id=0）返回全部。
- `GET /api/approval/approvers`：角色与启用用户候选项（`approval:manage`）。
- `POST/PUT /api/approval/flows`：`steps` 为有序数组；最多 10 个节点，仅支持 `ROLE`/`USER`，
  校验角色/用户存在、金额非负、上限不小于下限。

### 前端

- 侧边栏新增“我的待办”（`payroll:approve`）；原“审批链”改名“审批工作流”（`approval:manage`）。
- 审批工作流表单新增节点编辑器：节点名称、按角色/指定用户、审批人、节点金额区间、增删与序号。
- 批次详情用 `.wf-track` 展示节点进度：进行中 `active`、已完成 `done`；
  批次 `APPROVED/FINALIZED/SENT` 时全部节点显示完成，`REJECTED` 时不再标进行中。
- 待办支持“查看 / 通过 / 驳回”，通过中间节点提示“已通过当前节点”，进入下一级；
  最后一级通过后批次变为“已审批”，出账按钮出现。
- 前端仍需匹配当前节点才显示审批按钮（`canApprove`），避免误操作后再由后端拒绝。

### 测试

- `ApprovalWorkflowTest`（4）：逐级审批与待办过滤、非当前审批人被拒绝、快照不可变、
  节点金额区间与无匹配报错、节点配置校验。
- `ApprovalWebTest` 扩展：`workflow_steps`/`current_step` 序列化、管理员与 HR 待办、
  只读用户 403、带节点创建流程、`/approvers` 返回角色与用户。
- 全量 `mvn test`：216 个测试通过（40 个测试类）。
- 端到端实测：管理员提交研发部批次 → 李敏（HR）在待办完成第 1/2 节点 →
  王岚（fin01）完成第 2/2 节点 → 批次“已审批”，轨迹包含
  `节点通过「人事初审」` 与 `审批通过「财务复核」`；fin01 在 HR 节点处理前看不到待办。

### 已知限制

1. 节点是串行会签；尚未支持或签、并行会签、加签/转办与超时催办。
2. 审批人只支持角色码与用户名，未按组织架构/汇报线解析。
3. 待办按当前节点实时过滤，没有站内信/邮件通知；`prompt()` 仍用于备注输入。
4. 流程删除不校验在途批次，但快照保证在途审批不受影响。
5. 本地验收可自行创建 HR/财务账号（角色 `HR` 与 `ADMIN`）；内置种子仍只有 `admin`。

## 22. 技能管理（2026-11-08）

### 背景与边界

技能提示词此前只能通过 `NacosSkillRepository` 读取 `<技能ID>.md`，内置三个角色技能，
自定义技能需要手工调用 Nacos API 发布。本次新增管理台“技能管理”页签，提供列表、查看、
新增、编辑与删除，仍然以 Nacos 配置为唯一持久化来源，不引入新表。

- 与 Agent 管理共用 `PAYROLL_NACOS_SERVER` / `PAYROLL_NACOS_GROUP` / 账号配置。
- 管理的是“技能提示词”本身；Agent 绑定关系仍在 `payroll-agents.json` 维护。
- 删除自定义技能前校验智能体引用，避免运行期 `getSkill` 解析失败。

### 存储与语义

| 位置 | 变化 |
| --- | --- |
| Nacos `<技能ID>.md` | 技能正文 + 可选 YAML frontmatter（`name`、`description`），group 默认 `payroll-agent`，类型 `markdown` |
| `NacosConfigClient.list(pattern)` | 调用 `/nacos/v1/cs/configs?search=blur&dataId=*.md`，解析 `pageItems` 的 `dataId/content/gmtModified` |
| `NacosConfigClient.publish(dataId, content, type)` | 原两参 `publish` 委托为 `type=json`；技能固定 `markdown` |
| `NacosSkillRegistry` | 技能列表/详情/创建/更新/删除、ID 校验、frontmatter 组装与解析、TTL 快照 |
| `NacosSkillRepository` | 新增 `lister` 发现自定义技能；`knownIds()` TTL 缓存，失败回退内置 ID |
| `Permissions.SKILL_MANAGE` | 新权限码 `skill:manage`（“管理技能”），仅内置管理员拥有 |

语义：

- **内置覆盖**：`RoleSkills.ids()` 中的三个技能始终出现在列表；若 Nacos 存在同名配置则
  `overridden=true`、`source=nacos`，详情读取 Nacos 正文。删除该配置后回到内置版本。
- **自定义技能**：非内置 ID 只能来自 Nacos；列表排在三个内置技能之后并按字母排序。
- **ID 规则**：`^[a-z][a-z0-9-]{1,63}$`；新建不允许与任何已有技能（含内置）重名，
  覆盖内置必须走编辑。
- **正文格式**：`detail` 返回去掉 frontmatter 的正文；保存时重新组装并转义 YAML 引号，
  描述上限 200 字、正文上限 200000 字符。
- **删除保护**：自定义技能被任一 `AgentDefinition.skillIds()` 引用时返回 400 与引用者名称；
  内置覆盖可以删除，删除后回退内置。
- **只读回退**：`PAYROLL_NACOS_ENABLED=false` 时 `writable=false`，列表仍展示内置技能；
  已启用但 Nacos 连接失败时列表回退内置技能，写操作返回中文错误。

服务地址默认 `http://127.0.0.1:8848`；若传入带 `/nacos` 上下文路径的地址（例如从控制台复制的
`http://127.0.0.1:8848/nacos`），`NacosSkillSettings.normalizeServer` 会去掉该后缀再拼接
`/nacos/v1/...`。此前默认值被改成带 `/nacos`，导致请求路径变成 `/nacos/nacos/v1/cs/configs`
并返回 404，已通过归一化修复。

### 接口与审计

- `GET /api/admin/skills?keyword=`：`{writable, location, skills[]}`，`keyword` 过滤 ID/描述。
- `GET /api/admin/skills/{id}`：正文、描述、来源、是否内置/覆盖、更新时间。
- `POST /api/admin/skills`：body `{id, description, content}`，新建配置。
- `PUT /api/admin/skills/{id}`：更新描述与正文，路径 ID 为准。
- `DELETE /api/admin/skills/{id}`：删除 Nacos 配置或内置覆盖。
- 全部接口要求 `skill:manage`；审计事件 `skill.create` / `skill.update` / `skill.delete`，
  修改与删除带变更前元数据；审计不保存提示词正文，只保存 ID、描述、来源、是否内置与正文长度。

### 前端

- `index.html` 侧边栏新增“技能管理”（`data-permission="skill:manage"`），位于 Agent 管理与流程编排之间；
  `app.js` 增加 `TAB_META.skills` 与 `showTab` 分发；`skills.js` 使用独立 Vue 实例挂载 `#skills-app`。
- 左列技能清单显示来源徽标（`Nacos` / `覆盖内置` / `内置`）、描述与正文长度；
  右侧编辑器显示 ID、描述与正文；内置技能可直接编辑（保存即生成 Nacos 覆盖），
  纯内置不能删除，已有覆盖删除后回退内置。
- 保存后刷新列表并保持当前选中；删除前二次确认；未启用 Nacos 时顶部提示并禁用保存/删除。

### 测试与本机验收

- `NacosSkillRegistryTest`（7）：空 Nacos 列内置、创建自定义技能写入 frontmatter 并可列表/详情、
  内置详情去 frontmatter、内置覆盖后删除回退、ID/重复/正文/长度校验、TTL 缓存、未启用只读。
- `SkillAdminControllerTest`（7）：列表 meta 与关键字过滤、创建/更新/删除审计、
  自定义技能被引用时拒绝删除、内置覆盖可删除、详情透传。
- `NacosSkillRepositoryTest`：新增 lister 发现 `payroll-auditor.md`、lister 抛错回退内置。
- `NacosConfigClientTest`：新增搜索列表解析（`search=blur`、`dataId=*.md`、`gmtModified`）。
- 全量 `mvn test`：216 个测试通过（40 个测试类）。
- 本机 Nacos 2.1.1 端到端：创建 `payroll-ui-test` → 编辑 → 出现在 `/api/admin/agents/skills`
  可绑定列表 → 被智能体引用后删除返回 400 → 解绑后删除成功 → 审计含 `skill.create/update/delete`
  且不含正文；清理后 Nacos 恢复原有 3 个配置。
- 浏览器 DOM 级验收完成登录、页签切换、新建/编辑/删除；当前环境截图为 0×0 视口，
  未取得可视截图，需在正常窗口下补一次人工目检。

### 已知限制

1. Nacos 2.1.1 搜索接口不返回 `gmtModified`，列表 `updatedAt` 为 0，前端不显示时间；
   详情也未读取 `Last-Modified` 响应头，后续可补。
2. 并发编辑仍按最后写入覆盖，没有版本号/乐观锁。
3. 删除保护只覆盖智能体绑定；流程节点若通过智能体间接引用技能，删除时会随智能体解析失败暴露。
4. 自定义技能立即可绑定，但已创建会话的技能仓库按 TTL（默认 60s）刷新，期间可能看不到新技能。

## 23. 角色切换、树状菜单与头像选择（2026-11-08）

### 背景

使用者在 Nacos 创建了 `ai-coding` 技能与「java工程师」智能体（`agent-26aa1eaf`，
`skillIds: ["ai-coding"]`），并把包含该 AGENT 节点的流程 `flow-2a690298` 设为全局默认。
对话仍回复“先加载薪酬核算相关技能”，角色没有切换。

### 根因

- AgentScope 的 `SkillFilter` 只作用于系统提示中的 `<available_skills>`；
  `DynamicSkillMiddleware.reloadSkills()` 会把仓库 `getAllSkills()` 全部注册进 `SkillBox`，
  `load_skill_through_path` 因此仍能加载未绑定的 `payroll-accountant_nacos`。
- `activeAgentPrompt` 对非角色技能也拼接了薪酬 `COMMON_RULES` 和
  “管理员已指定你负责本轮会话的薪酬核算”，模型被提示词拉回薪酬场景。
- 旧单智能体路径的 `allowedTools` 在自定义技能下回退到 `CALCULATOR_TOOLS`，工具面仍是薪酬。

### 修改

- `SkillRepositories.scoped(repository, skillIds)`：新增只读仓库视图，按技能裸名称过滤
  `getSkill` / `getAllSkillNames` / `getAllSkills`；`PayrollAgentFactory.createAgent` 统一用它
  包装 `skillRepository`，流程节点与自定义智能体只注册绑定技能。
- `PayrollAgentFactory.GENERAL_RULES` + 重写 `activeAgentPrompt`：自定义技能不再注入薪酬口径，
  角色由智能体名称、描述与已加载技能定义；角色技能（计算/验证/核算）仍沿用原提示词。
- `allowedTools` 仅在 `skillIds` 为空时回退 `CALCULATOR_TOOLS`；显式自定义技能返回空集合，
  与 `flowTools` 行为一致。
- 侧边栏改为树状：分组标题为可点击按钮（`nav-group-title` + `nav-caret` + `nav-children`），
  顶栏新增 `#sidebar-toggle` 可整体收起为 72px 图标栏；两种折叠状态分别持久化到
  `localStorage`：`payroll.nav.collapsed`、`payroll.sidebar.collapsed`。
  `showTab` 会展开当前页签所在分组；`applyPermissions` 会隐藏没有可见子项的整组。
- 智能体头像：新增 `static/avatars.js`，内置 26 个 Ant Design outline 图标的本地 SVG
  （MIT，`ant:<name>` 作为持久化值），并提供 `payrollAvatarMarkup(value)` 统一渲染，
  未知值按文本转义回退。`agents.js` 新建/编辑弹窗改为 26 宫格选择器 + 自定义 Emoji 输入；
  Agent 卡片、流程节点同步使用；后端 `MAX_AVATAR_LENGTH` 由 8 提升到 40。

### 测试与本机验收

- `DynamicSkillWiringTest`（9）：新增“仓库视图只暴露绑定技能”“自定义技能提示词不含薪酬口径”
  “自定义技能不继承薪酬工具白名单”三个用例。
- `AgentAdminControllerTest`：超长头像用例改为 41 字符（上限 40）。
- 全量 `mvn test`：**219 个测试通过（40 个测试类）**。
- 隔离实例（18081，内存 H2，`PAYROLL_AUTH_ENABLED=false`，本机 Nacos）端到端：
  默认流程 `flow-2a690298` 的 `agent-1` 回复“我是java工程师，负责高并发编程相关的工作”；
  日志只出现 `Registered skill 'ai-coding_nacos'`，不再注册任何 `payroll-*` 技能。
- 浏览器 DOM 验收：3 个分组可折叠/恢复并写入 localStorage；顶栏按钮切换
  `app-layout sidebar-collapsed`；Agent 管理弹窗渲染 26 个头像，选择后输入值变为 `ant:robot`
  且预览 SVG 生效。当前环境视口为 0×0，未能截图，需在正常窗口补一次人工目检。

### 已知限制

1. 头像图标为静态内置集合；新增图标需在 `avatars.js` 补充 SVG 与名称。
2. 侧边栏折叠动画依赖 `grid-template-columns` 过渡；极旧浏览器会直接切换无动画。
3. 自定义技能智能体不获得薪酬工具；若未来需要混合角色，应在智能体定义中显式增加技能到工具的映射。

## 24. 会话角色隔离与折叠菜单重设计（2026-11-08）

### 背景

新增「情感专家」技能与对应智能体/默认流程后，提问仍返回“武杰的工资”，
不同角色与话题的消息互相串线。

### 根因（三条链路叠加）

1. Nacos 读取短暂失败时，`NacosAgentRegistry` / `NacosFlowRegistry` 回退到内置薪酬智能体，
   已配置的默认流程无法解析；`resolveActiveFlow()` 随后静默回退三阶段薪酬流水线。
2. `chat_sessions` 没有记录当前角色，同一会话切换默认流程后仍携带上一角色的
   `ConversationContext`（最近问题与摘要），模型据此继续回答薪酬问题。
3. 向量记忆按 `user_id` 跨会话召回，其他会话中关于武杰工资的历史片段被注入新角色。

### 修改

- **last-known-good 目录**：`NacosAgentRegistry` / `NacosFlowRegistry` 在读取或解析失败时
  沿用上次成功加载的列表与 `activeFlowId`，不再切回内置薪酬角色。
- **失败即报错**：`PayrollChatService.resolveActiveFlow()` 对“已配置但流程不存在、结构非法、
  引用智能体缺失/下架、供应商异常”抛出 `PayrollException`，消息包含
  “已阻止回退薪酬链路”；仅 `activeFlowId` 为空时才使用三阶段流水线。
- **按会话绑定角色**：`chat_sessions` 新增 `persona_key`（`flow:<id>` / `agent:<id>` / `pipeline`），
  `ChatContextStore` 提供 `persona` / `savePersona`。每轮 `run()` 解析 persona 后调用
  `applyPersona()`：角色变化时清空并持久化上下文、删除该会话向量、推送阶段事件
  “检测到智能体切换，已隔离上一角色的会话上下文”。
- **向量召回默认按会话隔离**：`VectorMemorySettings` 新增 `crossSession`（默认 `false`），
  `ElasticsearchVectorStore.search` 在 `sessionId > 0` 时追加 `session_id` 过滤；
  `PAYROLL_MEMORY_CROSS_SESSION=true` 才回到按用户跨会话召回。
- **技能缺失不回退**：`PayrollAgentFactory.boundSkills` 在智能体未绑定技能或绑定技能不存在时
  直接报错，禁止回退 `CALCULATOR_TOOLS` / 薪酬角色提示词。
- **折叠菜单重设计**：`.app-layout.sidebar-collapsed` 宽度 64px → 72px；折叠项 40×40、
  图标芯片 32×32（SVG 17px）、组间距收紧；隐藏分组标题/文字/演示卡/用户信息，
  保留底部头像；展开态导航项 42px、13px 字号，使用 Ant Design 图标芯片与分组分隔线。

### 测试与本机验收

- 新增/更新测试：
  - `DynamicSkillWiringTest#missingSkillFailsLoudly`：未绑定/技能不存在均报错；
  - `NacosAgentRegistryTest#keepsLastKnownGoodWhenReadFails`、`NacosFlowRegistryTest#keepsLastKnownGoodWhenReadFails`：
    Nacos 500 时沿用上次列表与默认流程；
  - `PayrollChatServicePersonaTest`（2）：首次绑定不清空、切换角色写入一次空上下文快照、
    同角色不重复清空、`sessionId=0` 空操作；
  - `VectorMemoryServiceTest#recallScopesToSessionUnlessCrossSessionEnabled`：
    默认以 `session_id` 召回，开启跨会话后以 `0` 检索。
- 全量 `mvn -o test`：**225 个测试通过，0 失败**。
- 浏览器静态壳验收（真实 `style.css` + `index.html` + `avatars.js`，1440×900，视口 1414×807）：
  - 展开：侧栏 236px，14 个 SVG 图标，导航项 207×42，图标芯片 28px，分组标题/文字/用户信息可见；
  - 折叠：侧栏 72px，导航项 40×40，图标芯片 32×32，分组标题/文字/用户信息隐藏，
    `toggle-expand` 可见，底部头像可见（内容底部 797 < 侧栏底部 807）。
  - 截图：`target/ui-expanded.png`、`target/ui-collapsed.png`。
- 说明：本机 H2 的管理员密码已非默认值，未修改用户数据；视觉验收使用离线静态壳完成，
  交互逻辑（折叠状态、权限显隐）仍由既有 DOM 级用例覆盖。

### 排查提示

- 对话出现 `已阻止回退薪酬链路`：检查 Nacos `payroll-flows.json`、
  `payroll-flow-settings.json` 与流程引用的智能体是否仍存在/上架。
- 角色切换后仍提到旧话题：确认 `chat_sessions.persona_key` 是否随角色更新，
  以及事件流是否出现“检测到智能体切换”。
- 开启向量记忆时，跨会话回忆需显式设置 `PAYROLL_MEMORY_CROSS_SESSION=true`。

## 25. 技能仓库 last-known-good（2026-11-08）

### 背景

使用 `user-talk` 技能的情感专家智能体时，回答开头出现“角色技能未加载”。

### 根因

该句来自 `PayrollAgentFactory.SKILL_BOOT_RULE`，是模型两次调用
`load_skill_through_path` 都失败后的降级话术，不是启动异常。实测当前代码 + 本机 Nacos
可正常加载 `user-talk`，问题出在 Nacos 瞬断时的仓库行为：

- `NacosSkillRepository.knownIds()` 列表请求异常时只返回内置技能，并把这个结果缓存整个
  TTL（默认 60s）；`getSkill` 又先按 `knownIds()` 过滤，自定义技能（无内置兜底）因此消失 60s。
- `cachedSkill()` 拉取异常时把 `null` 写入缓存（负缓存 60s），已加载成功的技能同样消失。

### 修改

- `knownIds()` 新增 `lastGoodIds`：列表失败且存在上次成功快照时沿用该快照，日志
  `[Skill] 列出 Nacos 技能失败，沿用上次成功快照（N 个技能）`，并按 TTL 退避重试；
  首次调用就失败仍退回纯内置清单（原行为）。
- `cachedSkill()` 拉取异常且已有上次成功内容时复用旧内容，日志
  `[Skill] 读取 Nacos 配置 ... 异常，沿用上次成功加载的版本`，同样按 TTL 退避；
  配置确实不存在（返回空）仍走负缓存，避免删除技能后无限重试。

### 测试

- `NacosSkillRepositoryTest` 新增“列表失败时沿用上次成功快照，Nacos 自定义技能不消失”
  “单个技能拉取异常时沿用上次成功内容”；原“列表读取失败时仅保留内置技能”保留，覆盖首次失败场景。
- 全量 `mvn -o test`：**227 个测试通过，0 失败**。

### 排查提示

- 再遇到“角色技能未加载”，先 grep `[Skill]`：出现“仅保留内置技能”说明启动后首次列表就失败；
  出现“沿用上次成功快照”说明技能仍可用，应检查 Nacos 恢复情况或模型是否跳过工具调用。

## 26. 智能体运行期缓存与上下文隔离（2026-11-08）

### 背景

原实现每轮对话（流程模式为每个节点）都会重新创建技能仓库（含 Nacos `HttpClient`）、
探测 Ollama `/api/tags` 与 `/api/show`、构建智能体，并重新注册工具。技能 TTL 缓存因此
在流程模式下几乎失效，万人并发下会放大连接与构建开销。改造前先用
`RuntimeContextIsolationTest`（提交 `2742d67`）验证单实例边界，再落地进程级复用。

### 结论（spike 证据）

| 能力 | 单实例 + RuntimeContext | 说明 |
| --- | --- | --- |
| 系统提示中的技能清单 | 隔离 | 不同 `RuntimeContext` 的 `SkillFilter` 生效 |
| 会话历史 | 隔离 | 历史按上下文保存，`clearContext(ctx)` 只清本会话 |
| `load_skill_through_path` | **不隔离** | core 的 `SkillBox` 忽略 per-context `SkillFilter`，B 会话能加载 A 的角色技能 |

因此共享智能体必须同时绑定 `SkillRepositories.scoped(...)` 限定视图；仅靠 `SkillFilter`
不足以阻断跨角色加载。`agentscope-harness` 的上下文级技能目录也能阻断，但需要替换 core
动态技能中间件并调整过滤语义（裸名称 vs `skillId`），本次不迁移。

### 修改

- `SkillRepositories.shared()`：进程级技能仓库单例（Nacos 优先 + 内置回退）。
  `NacosSkillRepository` 本身是并发缓存 + volatile 快照，TTL 与 last-known-good 语义不变；
  管理台仍用 `create()` 读取最新配置。`resetShared()` 仅供测试/热切换。
- `PayrollAgentFactory`：
  - `AgentCache`（新文件）：`ConcurrentHashMap.computeIfAbsent`，同 key 只构建一次；
    `clear()` 关闭 `AutoCloseable` 条目。
  - `sharedModel()`：按 `PAYROLL_MODEL` 懒加载模型单例，避免重复探测 Ollama。
  - `sharedTeam(engine, activeAgent)`：key 为
    `team|engine@<identityHashCode>|<pipeline|定义签名>|<modelId>`。
  - `sharedFlowNodeAgent(engine, agent)`：key 为
    `node|engine@<identityHashCode>|<定义签名>|<modelId>`。
  - 定义签名 = `id|name|description|skillIds`；任一变化即缓存未命中。
  - `Team.clearSession(RuntimeContext)`：只调用各智能体的 `clearContext(ctx)`；
    会话不再调用 `Team.close()`，避免关闭其它会话正在使用的共享实例。
  - `resetRuntimeCache()` / `cachedAgentCount()`：测试与日志排查入口。
- `PayrollTools`：8 个 `@Tool` 方法增加末尾 `RuntimeContext context` 参数。
  AgentScope 反射注入该参数且不写入工具 schema；`execute` 优先从
  `context.get(ToolTraceRecorder.class)` 取记录器，未提供时回退构造器注入
  （兼容 CLI 与直调测试）。
- `PayrollChatService`：
  - 构造会话级 `RuntimeContext`（`userId`、`sessionId`、`ToolTraceRecorder`）。
  - `runStage` / `summarize` / `clearHistory` 全部显式传上下文；每个阶段执行前把该阶段
    记录器放入上下文。
  - 流程节点使用 `sessionId + ":" + node.id()` 的独立上下文与独立记录器，执行完
    `clearContext(nodeContext)`；并行节点即使复用同一智能体实例也不互相串状态。
  - `reset()` 清理会话上下文后置空本地 `team` 引用并打日志，不关闭共享实例。

### 行为与生命周期

- **定义变化**：签名变化 → 下一轮构建新实例；旧实例保留，继续服务在途会话。
- **技能内容变化**：无需重建智能体。core 的 `DynamicSkillMiddleware` 每次调用比较内容
  SHA-256，变化时只重建 `SkillBox`。
- **Nacos 抖动**：共享仓库沿用 last-known-good，缓存命中不受影响。
- **会话隔离**：历史、系统提示、工具追溯都挂在 `RuntimeContext`；`reset()` 只清本会话。
- **进程退出**：共享实例不逐会话关闭；由 JVM 退出回收，测试可调用
  `PayrollAgentFactory.resetRuntimeCache()`。

### 日志

```
[多智能体] 智能体缓存就绪：薪酬计算（pipeline），模式=三段流水线，缓存条目=1，耗时=1ms
[多智能体] 复用已缓存智能体：薪酬计算（pipeline）        # DEBUG，第二轮起
[多智能体] 会话已重置，共享智能体实例保留
```

- `缓存条目` 是进程内缓存的智能体定义数；持续增长说明定义签名在变化（检查 Agent 管理保存、
  流程节点引用是否每轮生成新 id）。
- 首次构建仍会探测一次 Ollama；之后每轮不再探测。

### 测试

- `RuntimeContextIsolationTest`（3，改造前提交）：共享实例的提示词/历史隔离；
  scoped 仓库阻断跨技能加载；harness 上下文目录方案对照。
- `AgentCacheTest`（5）：同 key 仅构建一次（含 8 线程并发首次访问）、不同 key 各自构建、
  `clear()` 关闭 `AutoCloseable`、定义签名覆盖名称/描述/技能变化。
- `PayrollToolsContextTest`（4）：记录写入上下文记录器；不同会话互不可见；
  无上下文或上下文未带记录器时回退构造器注入。
- 全量 `mvn test`：**239 个测试通过，0 失败**。

### 本机端到端验证（2026-11-08）

环境：18083 端口，`PAYROLL_AUTH_ENABLED=false`、`PAYROLL_NACOS_ENABLED=false`、
`PAYROLL_MEMORY_ENABLED=false`、内存 H2，使用本机 Ollama `qwen2.5:3b-instruct`。

1. 会话 1 首条消息：`[多智能体] 初始化完成` 后出现
   `智能体缓存就绪：薪酬计算专家(payroll-calculator)，模式=三段流水线，缓存条目=1，耗时=177ms`。
   177ms 包含首次 Ollama 探测与三个智能体构建。
2. 同会话第二条“销售部有哪些员工？”：日志只出现
   `DEBUG 复用已缓存智能体`，耗时 4.5s，答复工具为 `list_employees`；
   追溯接口返回 `message_id=4`、`agent=calculator`、`args_json={"department":"销售部"}`，
   证明共享智能体下上下文记录器仍能正确归属到本轮。
3. 新建会话 2 首条消息：日志为 `智能体缓存就绪 ... 缓存条目=1，耗时=0ms`。
   这是新会话第一次绑定本地 `team` 引用时打印的，0ms 与条目数不变说明命中了缓存；
   同一 `PayrollChatService` 的后续轮次才会出现 DEBUG `复用已缓存智能体`。
4. 重置会话 2：日志 `会话已重置，共享智能体实例保留`，随后其它会话仍可继续使用同一实例。

注意：Windows 下 `spring-boot:run` 的日志按 GBK 写入文件，直接 grep 中文会失效；
可先 `iconv -f GBK -t UTF-8 target/e2e-cache.log` 再检索。验证完成后已停止该测试实例。

### 已知限制与后续建议

1. 缓存没有容量上限与 TTL。生产环境智能体定义数量有限，影响可控；若管理台高频修改定义，
   旧实例会累积。后续可按“最后使用时间 + 最大条目数”做 LRU 淘汰（淘汰前需确认无在途调用）。
2. 定义签名只覆盖影响运行时的字段；`avatar/tags/visibility/status` 变化不会重建实例，
   这些字段只在管理台展示或解析阶段使用。
3. 单进程内共享；多实例部署各自缓存，Nacos 仍是配置源，行为一致。
4. `Model` 单例依赖其无状态假设（formatter/transport/options 不可变）。若替换为带可变
   session 的模型客户端，需要重新评估共享。

### 排查提示

- 修改智能体后回复没变化：grep `[多智能体] 智能体缓存就绪`，确认出现新的耗时日志与
  缓存条目增加；若没有，检查保存是否成功、`resolveActiveAgent()` 是否解析到旧定义。
- 不同会话串话：确认所有 `streamEvents` / `call` 都传了 `RuntimeContext`；
  新增阶段或工具时不要在无上下文的情况下直接调用共享智能体。
- 金额追溯缺失：确认 `PayrollTools` 调用链传入了 `RuntimeContext`，且当前阶段已把
  `ToolTraceRecorder` 放入上下文（`runStage` / `executeFlowNode` 已处理）。
- 修改 `PAYROLL_MODEL` 后行为异常：该变量按进程读取，切换模型会生成新的缓存键并重建
  共享模型；不要在运行中依赖环境变量热更新。

## 27. 整体迁移到 agentscope-harness（2026-11-09）

规格见 [`harness-migration-spec.md`](harness-migration-spec.md)。本次把运行期智能体从
core `ReActAgent` + `DynamicSkillMiddleware` 全部切换到 `HarnessAgent`，包括三段流水线、
上下文压缩器、意图抽取、流程节点与自定义技能智能体；业务工具、金额安全、会话隔离和
运行期缓存均未改变。

### 为什么迁移

- core 的 `SkillBox` 忽略 per-context `SkillFilter`，共享智能体只能靠
  `SkillRepositories.scoped(...)` 阻断跨角色加载（见 §26）；harness 的技能目录天然按
  `RuntimeContext` 解析，边界更直接。
- 统一到一个运行期基类，避免 core/harness 两套技能提示与加载逻辑长期并存。

### 关键差异与处理

| 差异 | 处理 |
| --- | --- |
| harness 的 `load_skill_through_path` **不激活工具组** | 新增 `SkillToolGroupActivationMiddleware`：`onActing` 观察加载工具的 `ToolResultEndEvent`，`SUCCESS` 时按 `skillId` 解析裸名称并 `Toolkit.updateToolGroups(..., true)` |
| `SkillFilter` 按 `AgentSkill.getName()` 匹配，现有过滤传的是 `<name>_<source>` | 不再设置 `SkillFilter`，只用 `SkillRepositories.scoped(...)` 限定仓库视图 |
| 默认注册文件/Shell/记忆/子智能体/转录/压缩等能力并额外调用 LLM | `HarnessAgentSupport` 逐个 `disable*()`；构建后按白名单删除 harness 追加的工具 |
| harness 可能复制 `RuntimeContext` 注入会话默认值 | 所有调用继续显式传原上下文；工具追溯记录器在阶段开始时放入上下文，复制不影响读取 |
| harness 无 `call(Msg, Class)` 重载 | 意图抽取改 `call(List.of(msg), PayrollIntent.class, ctx)` |

### 修改文件

- `pom.xml`：`agentscope-harness` 由 test 改为 compile scope。
- `agent/HarnessAgentSupport.java`（新）：最小能力集构建、工作区、工具白名单清理。
- `agent/SkillToolGroupActivationMiddleware.java`（新）：技能加载成功后激活业务工具组。
- `agent/PayrollAgentFactory.java`：`Team` / `NodeAgent` / `createIntentAgent` 全部返回
  `HarnessAgent`；有技能时用 scoped 仓库 + 激活中间件；构建日志
  `[harness] 智能体已构建：...`。
- `service/PayrollChatService.java`、`service/GraphPayrollOrchestrator.java`、
  `PayrollApplication.java`：仅类型迁移，编排逻辑不变。
- `model/OllamaThinkingModel.java`：保留请求/响应 DEBUG/TRACE 日志（默认关闭），
  仅在启用思考时使用；非思考模型仍是官方 `OllamaChatModel`。
- 测试 `agent/HarnessAgentWiringTest.java`（新，2 个）；
  `README.md`、`docs/agent-runtime-cache-spec.md` 同步更新。

### 日志

```
[harness] 智能体已构建：薪酬计算专家，技能=[payroll-calculator]，工具=[calculate_payroll, ...]
[Skill] 已激活技能 payroll-calculator 的业务工具组：[role-tools]
[多智能体][calculator] 阶段结束：耗时=18696ms，工具=[load_skill_through_path, calculate_payroll]
```

`[Skill] 已激活技能` 只应在角色技能加载成功后出现一次；若重复出现，检查是否每轮都在
新建智能体（缓存未命中）。

### 测试

- `HarnessAgentWiringTest`（2）：
  - 首轮工具集只有 `load_skill_through_path`，系统提示包含 `<available_skills>`；
    加载成功后工具集出现 `calculate_payroll`。
  - scoped 仓库下加载未绑定技能返回 “Skill not found”，且不泄露其它角色内容。
- 全量 `mvn -o test`：**241 个测试通过，0 失败**（迁移前 239，新增 2）。

### 本机端到端验证（2026-11-09）

环境：18085 端口、独立 H2（`target/e2e-db`）、`PAYROLL_AUTH_ENABLED=false`、
`PAYROLL_NACOS_ENABLED=false`、`PAYROLL_MEMORY_ENABLED=false`，本机 Ollama
`qwen2.5:3b-instruct`。

1. 计算阶段：`load_skill_through_path` SUCCESS → `[Skill] 已激活技能 payroll-calculator`
   → 同一轮调用 `calculate_payroll`，张伟 2026-10：
   gross 44,235.63、net 27,661.39、employer cost 58,973.13，与确定性引擎一致。
   这次验证使用**生产路径**（未开启思考时走官方 `OllamaChatModel`），非仅适配器。
2. 同会话第二轮不再加载技能，直接调用 `calculate_payroll`（`AgentState.toolContext`
   同步了工具组激活状态），证明激活在会话内可持久。
3. 工具集被清理为「业务工具 + `load_skill_through_path`」；SSE 时间线与金额追溯正常。
4. 流程模式与 CLI 走同一 `HarnessAgentSupport`，未观察到行为差异。
5. 注意：直接用“核算张伟 2026-10 的工资”时，3b 模型有一次跳过技能加载、改为追问信息；
   明确写出加载指令后稳定通过。属下述模型能力问题，不影响编排与工具链正确性。

### 已知限制

- `qwen2.5:3b-instruct` 在验证/核算阶段偶发
  `empty completion (no text, no tool call) — ReAct loop will terminate`。抓取原始 Ollama
  响应为 `content:""`、`done_reason:"stop"`、`eval_count≈115` 且无 `tool_calls`：模型尝试
  调用尚未可见的业务工具，Ollama 静默丢弃非法/未知工具调用，于是返回空内容。
  迁移前后业务代码 diff 仅为类型变化，这不是 harness 回归；换更大模型或启用思考模型可缓解。
- 若后续要加重试，建议只对「阶段无文本且无工具调用」重试一次，并在日志中标注，
  不要无条件重放（避免重复核算与重复追溯写入）。

### 排查提示

- 业务工具不可见：确认日志出现 `[Skill] 已激活技能`；确认技能 `toolGroup` 与
  `createSkillToolGroup(..., false, skillName)` 的绑定关系没被改坏。
- 技能清单为空：检查是否误设了 `SkillFilter`（harness 要裸名称），或 scoped 仓库的
  `skillIds` 与技能实际 ID 不一致。
- 模型看到多余工具：检查 `HarnessAgentSupport.removeHarnessOnlyTools` 的白名单；
  harness 升级新增默认工具时只会被移除，不会暴露给模型。
- 上下文串话：确认 `streamEvents` / `call` 都传了会话级 `RuntimeContext`；不要依赖
  harness 自动注入的上下文副本保存追溯记录器。
