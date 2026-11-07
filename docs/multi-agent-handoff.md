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
