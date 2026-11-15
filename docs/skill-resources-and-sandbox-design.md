# 技能资源与脚本沙箱设计方案

- 状态：P1 已实现（P1-1～P1-4，2026-11-15）；P2 沙箱执行待开发
- 日期：2026-11-15
- 关联：`docs/kb-chat-integration-design.md`、`payroll-common/.../skill/NacosSkillRegistry.java`、`payroll-agent/.../HarnessAgentSupport.java`

## 一、结论

技能从"单篇提示词"扩展为**技能包**：

1. **正文**（SKILL.md）仍存 Nacos `<id>.md`，管理台编辑方式不变；
2. **参考文件**（references）与**脚本**（scripts）以伴生配置 `<id>.files.json` 发布，解析进 `AgentSkill.resources`；
3. **长参考文档不入技能包**，继续走现有知识库（三路召回 + 引用），技能正文只写检索指引；
4. **脚本不在 agent 进程执行**，由独立沙箱服务承接；模型只能通过白名单 `skill_run` 工具提交脚本路径与参数，经确认与审计后执行。

决策记录：D1 references 采用"技能包伴生文件 + 知识库"双轨（已定）；D2 scripts 支持真实沙箱执行（已定）；D3–D7 按建议默认值确认（2026-11-15）。

实现落点（P1）：

| 层 | 文件 |
| --- | --- |
| 存储与校验 | `payroll-common/.../skill/SkillFilePackage.java`、`SkillFileSettings.java`、`NacosSkillRegistry.java` |
| 资源构建 | `payroll-common/.../skill/NacosSkillRepository.java`（`mdHash` 匹配后 `addResource`） |
| 读取工具 | `payroll-common/.../agent/SkillReadTools.java`、`SkillReadBudget.java` |
| 注入与注册 | `payroll-agent/.../agent/PayrollAgentFactory.java`（资源哈希进缓存签名、按需注册 `skill_read`、提示词指引） |
| 轮内额度 | `payroll-agent/.../service/PayrollChatService.java`（每轮清零，团队节点共享） |
| 管理台接口 | `payroll-backend/.../web/SkillAdminController.java` |
| 管理台页面 | `frontend/src/views/admin/SkillsView.vue`（正文 / 参考文件 / 脚本文件三页签） |

## 二、现状与约束

| 环节 | 现状 | 影响 |
| --- | --- | --- |
| 存储 | 每个技能只有一个 Nacos `<id>.md`，保存时拼 frontmatter + 正文（`NacosSkillRegistry.java:27`、`:321`） | 无文件概念，参考文件与脚本无处安放 |
| 构建 | `NacosSkillRepository.parse` 只设置 `skillContent`（`NacosSkillRepository.java:238-250`） | `AgentSkill.resources` 能力被闲置 |
| 注入 | 自定义角色正文预注入系统提示词，并显式移除 `load_skill_through_path`（`PayrollAgentFactory.java:586`、`:682`） | 即便有 resources，模型也没有读取通道 |
| 执行 | harness 关闭 shell（`HarnessAgentSupport.java:55`） | 脚本无法执行，且不应为脚本重新打开进程内 shell |
| 知识库 | 会话绑定 + `kb_search` + 引用链路已上线 | 长参考文档已有归宿，无需重复建设 |

AgentScope 原生能力（2.0.1）：`AgentSkill` 支持 `resources`（相对路径 → 文本）与 `originDir`；`SkillFileSystemHelper` 支持目录式技能；`SkillBox`/`DynamicSkillMiddleware` 提供 `load_skill_through_path(skillId, path)`。本方案只补 Nacos 侧的打包、读取与执行边界。

## 三、技能包模型

### 3.1 存储布局

| 配置 | dataId | 内容 |
| --- | --- | --- |
| 正文 | `<skill-id>.md` | frontmatter（`name`/`description`/可选 `execution`）+ Markdown 正文，沿用现有格式 |
| 文件包 | `<skill-id>.files.json` | `{"version":1,"mdHash":"<正文SHA-256前16位>","files":[{"path":"references/faq.md","content":"..."}]}` |

- `mdHash` 绑定两配置版本：读取时计算当前正文哈希，不一致则忽略文件包并告警（防"改完正文忘发文件"的错配），不阻断正文加载。
- 文件包缺失 = 无资源，旧技能行为不变。
- 删除技能时同时删除两份配置；删除文件包失败只告警，不阻断正文删除。

### 3.2 路径与大小约束

| 项 | 规则 |
| --- | --- |
| 路径白名单 | `references/**`、`scripts/**`；根目录文件只能通过正文承载 |
| 路径格式 | 相对路径，`/` 分隔，禁止 `..`、绝对路径、反斜杠、控制字符；仅 `[A-Za-z0-9._/-]` |
| 单文件 | ≤ 64KB，文本 UTF-8；P1 不支持二进制 |
| 总量 | ≤ 20 个文件、≤ 256KB |
| 脚本类型 | `scripts/**` 仅允许 `.py`、`.sh`、`.js`（P2） |
| 校验时机 | 保存时强校验；读取时再校验路径，越界直接拒绝 |

### 3.3 frontmatter 扩展（可选）

```yaml
---
name: payroll-policy
description: 制度问答角色
execution:
  enabled: true          # 仅此技能注册 skill_run（P2）
  timeout-seconds: 10    # 上限 10
---
```

未声明 `execution.enabled` 的技能，即使含脚本也不注册执行工具。

## 四、读取链路

### 4.1 构建与仓库

`NacosSkillRepository.parse` 读取 `<id>.files.json`（TTL 同正文），校验 `mdHash` 与路径/大小，构建 `AgentSkill.Builder.addResource(path, content)`。内置角色技能不变，继续走 `RoleSkills`。

### 4.2 新增只读工具 `skill_read`

自定义角色当前禁用 `load_skill_through_path`，改由业务侧注册：

```
skill_read(path)  # 只能读本智能体已绑定技能中的 references/** 与 scripts/**
```

| 项 | 规则 |
| --- | --- |
| 作用域 | 本轮绑定的技能集合（`AgentDefinition.skillIds`），跨技能同名路径按技能 id 前缀隔离 |
| 返回值 | 文本内容 + `path` + `truncated`；单次 ≤ 8KB，超出截断并标记 |
| 额度 | 单轮 ≤ 5 次读取（与 `KbSearchBudget` 同样的轮内清零模式） |
| 错误 | 路径不存在 / 越界 / 额度用尽分别返回中文错误，不泄露其他技能文件 |
| 只读 | `readOnly = true`，不触发写确认；不采集 citation（引用只属于 KB） |
| 追溯 | 读取结果写入 `ToolTraceRecorder`（8KB 截断）；会话页"引用文件"面板展示技能 ID、路径、大小与原文，便于客户核对数据来源 |
| 提示词 | 技能含 resources 时追加一段固定指引：参考文件用 `skill_read`，制度原文用 `kb_search` |

内置薪酬角色的 `load_skill_through_path` 行为不变；resources 存在时模型可按路径加载，属顺带收益。

### 4.3 references 与知识库的分工

| 类型 | 存放 | 读取方式 | 是否带引用 |
| --- | --- | --- | --- |
| 短参考：字段说明、模板、话术 | 技能包 `references/*.md` | `skill_read` | 否 |
| 长文档：制度、条款、操作手册 | 知识库 | `kb_search` | 是 |
| 脚本（只读示例之外的真实执行） | 技能包 `scripts/*` | `skill_run`（P2） | 否，走工具追溯与审计 |

技能正文增加"检索指引"小节即可，不新增数据模型；管理台技能编辑页提示该约定。

## 五、脚本沙箱（P2）

### 5.1 架构

```
agent-runtime --(HMAC + 内部令牌)--> sandbox-service --(docker run, 每次一次性容器)--> 解释器
```

- 新增独立服务 `payroll-sandbox`（Spring Boot + Docker Engine API 或 testcontainers 风格 runner），不放进 Web/runtime 进程。
- 技能文件由 sandbox-service 从 Web 内部接口拉取或随请求传输，**只读挂载**；工作目录 tmpfs。
- 执行完返回 stdout/stderr（各 ≤ 8KB）与退出码；超时硬杀容器。

### 5.2 接口

```
POST /internal/sandbox/execute
{
  "skill_id": "payroll-policy",
  "script_path": "scripts/check.py",
  "args": ["--month", "2026-11"],
  "timeout_ms": 10000
}
→ { "exit_code": 0, "stdout": "...", "stderr": "...", "duration_ms": 1234 }
```

鉴权复用现有 HMAC 内部通道约定；参数为数组，不接受 shell 字符串拼接。

### 5.3 安全基线（硬约束）

| 面 | 规则 |
| --- | --- |
| 进程 | 一次性容器、非 root、只读 rootfs、tmpfs 工作目录、`--network none` |
| 资源 | CPU 1 核、内存 256MB、PID 64、超时 ≤ 10s、并发上限（全局 4 / 用户 1） |
| 文件 | 仅挂载当前技能包的 `scripts/**` 与声明的输入文件，禁止访问仓库与 `data/` |
| 秘密 | 不注入任何环境变量、令牌、数据库连接 |
| 解释器 | 镜像内固定 `.py/.sh/.js` 运行时，不安装第三方包，不允许运行时 pip/npm |
| 审计 | 记录 skill_id、脚本路径、参数、退出码、耗时、脚本哈希；不记录完整 stdout |
| 确认 | `skill_run` 视为写操作，走 `ActionGuard` 会话内确认（与 KB 写一致，不受全局开关关闭影响） |

### 5.4 工具与注册条件

`skill_run(script_path, args)` 仅当：沙箱已配置 + 技能 frontmatter `execution.enabled=true` + 用户具备该技能所属智能体的使用权限，三者同时满足才注册。失败时明确返回原因（沙箱未配置 / 技能未开启执行 / 超时 / 退出码非零），不静默降级为"模型自己猜结果"。

## 六、管理台与接口

| 接口 | 变更 |
| --- | --- |
| `GET /api/admin/skills/{id}` | 返回 `files: [{path, size, kind}]`（不返回正文，避免大响应） |
| `GET /api/admin/skills/{id}/files/{*path}` | 读取单个文件内容（编辑用） |
| `PUT /api/admin/skills/{id}/files` | 全量替换文件包（含路径校验与总量校验） |
| `DELETE /api/admin/skills/{id}/files/{*path}` | 删除单个文件 |
| `POST/PUT /api/admin/skills` | 正文保存后自动重算 `mdHash` 并重发文件包；无文件时不发 |

前端 `SkillsView.vue` 编辑弹窗增加三个页签：正文 / 参考文件 / 脚本文件；支持新增、编辑、删除与大小提示；脚本页签在 P2 增加"启用执行"开关（对应 frontmatter `execution.enabled`）。所有写操作沿用现有审计。

## 七、配置项

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PAYROLL_SKILL_FILES_ENABLED` | `true` | 是否读取技能文件包；`false` 时忽略 `<id>.files.json` |
| `PAYROLL_SKILL_FILE_MAX_BYTES` | `65536` | 单文件上限 |
| `PAYROLL_SKILL_FILES_MAX_BYTES` | `262144` | 单技能文件总量上限 |
| `PAYROLL_SKILL_READ_MAX_PER_TURN` | `5` | 单轮 `skill_read` 次数 |
| `PAYROLL_SANDBOX_URL` | 空 | 沙箱服务地址；为空不注册 `skill_run` |
| `PAYROLL_SANDBOX_TOKEN` | 空 | 沙箱内部 HMAC 令牌 |
| `PAYROLL_SANDBOX_TIMEOUT_MS` | `10000` | 单次执行上限，硬上限 10s |
| `PAYROLL_SKILL_EXEC_ENABLED` | `false` | 执行总开关，需与技能级开关同时为真 |

## 八、分期任务与验收

| 批次 | 任务 | 关键交付 | 验收 |
| --- | --- | --- | --- |
| P1-1 | 技能包存储 | `.files.json` 读写、`mdHash` 绑定、路径/大小校验、删除联动 | 发布带 3 个参考文件的技能后重启仍可读；改正文不发文件包时文件被忽略并告警 |
| P1-2 | 仓库与工具 | `resources` 构建、`skill_read` 工具、轮内额度、提示词指引 | 自定义角色可读 `references/faq.md`；越界路径 403 式拒绝；第 6 次读取返回额度提示 |
| P1-3 | 管理台 | 详情/文件读写接口、前端三页签、审计 | 网页新增/编辑/删除参考文件后对话立即可读；无权限账号 403 |
| P1-4 | 知识库分工 | 技能编辑指引与 README 约定 | 制度类问题走 `kb_search` 并带引用；短参考走 `skill_read` 无引用 |
| P2-1 | 沙箱服务 | `payroll-sandbox` 镜像与执行接口、资源/网络/超时隔离 | 死循环脚本 10s 被杀；`curl`/外网访问失败；无法读取宿主文件 |
| P2-2 | 执行工具 | `skill_run`、双开关、ActionGuard 确认、审计与追溯 | 未开启执行的技能无 `skill_run`；确认后执行并落审计；拒绝执行无副作用 |
| P2-3 | 部署与测试 | compose 增加沙箱、配置文档、端到端脚本 | 一键启动三服务；全量测试与前端构建通过 |

P1-1～P1-4 已于 2026-11-15 实现：回归测试覆盖文件包读写、`mdHash` 错配忽略、路径与大小校验、`skill_read` 额度与截断、管理台文件接口与审计；网页端已完成新增/编辑/删除参考文件的实测。P2 沙箱未开始。

## 九、风险与回滚

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 两配置版本错配 | 读到的参考文件与正文不一致 | `mdHash` 校验，不匹配即忽略并告警 |
| 提示词被文件内容撑爆 | 上下文超限、成本上升 | 文件不注入提示词，只按需 `skill_read`；单次 8KB + 单轮 5 次 |
| 路径穿越/跨技能读取 | 越权读取其他技能或宿主文件 | 白名单 + 归一化校验 + 只从 `AgentSkill.resources` 内存读取，不落盘 |
| 沙箱逃逸/资源滥用 | 宿主机风险 | 一次性容器、非 root、只读、禁网、限额、不注入秘密；独立服务部署 |
| 沙箱故障 | 工具不可用 | `skill_run` 返回明确错误；不注册时模型不可见；不影响正文与其他工具 |
| 回滚 | — | `PAYROLL_SKILL_FILES_ENABLED=false` 忽略文件包；`PAYROLL_SKILL_EXEC_ENABLED=false` 关闭执行；两者均可零数据迁移回退 |

## 十、不做的事

- 不在 agent 进程内重新启用 shell；
- 不允许技能文件包含二进制、可执行权限或符号链接；
- 不允许沙箱访问网络、数据库、`data/` 目录或其他技能；
- P1 不把参考文件直接拼进系统提示词（避免上下文膨胀）。

## 十一、已确认决策

- D3（已确认）：文件包格式采用 `<id>.files.json` + `mdHash`，正文变更后未重发文件包时文件被忽略并告警；
- D4（已确认）：`skill_run` 无条件走会话内确认，视为写操作，不受全局确认开关关闭影响；
- D5（已确认）：沙箱运行时基线 `python:3.12-slim` 并预装 node，禁 pip/npm 与网络；
- D6（已确认）：单技能文件总量 256KB、单文件 64KB、最多 20 个；
- D7（已确认）：`skill_read` 使用独立预算（5 次/轮），不占用 `kb_search` 额度。
