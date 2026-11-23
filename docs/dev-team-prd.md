ljk想，# 开发团队智能体 PRD 与可行性分析

- 版本：v1.2（新增代码审查工程师，角色扩为九个；审查并入 G2 前的静态检查环节）
- 日期：2026-11-17
- 参考：EvoFlow（Lead Agent + Plan DAG + Workspace + Guardrails）、Qoder 的自然语言开发体验、OpenAI Codex 的沙箱/审批模型
- 结论先行：**分阶段可行，且比 v0.3 判断更乐观**。编排（DAG 并行/条件）、写确认、追溯、权限可复用；文件读写/编辑、Shell、Docker 沙箱、计划模式、任务查询在 `agentscope-harness 2.0.1` 中已内置，当前只是被 `HarnessAgentSupport` 显式关闭。真正的硬约束只剩三个：**模型能力与上下文预算**（默认 8192 ctx、27B 实测 1.49 token/s）、**审批粒度**（现有 ActionGuard 每轮只允许一次写提案）、**任务运行面**（聊天轮次 150 秒、无任务级恢复）。M0 可立即做；M1 起必须先换编码模型并改用任务级审批。

---

## 1. 背景与目标

### 1.1 背景

- 平台已具备多智能体、流程 DAG、技能包、沙箱脚本、知识库检索、动作确认与全链路追溯（见第 5 节）。
- 业务侧希望把这套能力从「薪酬问答/核算」扩展到「软件研发」，形态类似 Qoder：用户用自然语言提需求，智能体团队完成需求澄清、方案设计、编码、测试与交付。
- EvoFlow 提供了可借鉴的编排范式：Lead Agent + `SOUL.md` + 工具/技能/记忆/子智能体 + Plan（步骤含负责人、依赖、验收标准）+ Workspace + Guardrails + Task Center + 沙箱。

### 1.2 产品目标

1. 用户输入一句话需求（如「给员工列表加导出 CSV」），团队输出可评审的 PRD、技术方案与任务拆分。
2. 经用户确认后，在受控工作区内生成代码补丁，并给出自测/单测结果。
3. 全流程可追溯、可中断、可恢复，写操作一律经过确认与审批。
4. 同一套角色与流程既能在本仓库（payroll 平台）运行，也能绑定其他 Maven/Vue 仓库。

### 1.3 非目标（v1 明确不做）

- 不自动合并/发布/部署到生产。
- 不连接生产/共享数据库；数据迁移只在沙箱的空库与存量库副本上 dry-run。
- 不在 agent 侧执行真实发布，也不改主机网络/防火墙或重启正在运行的服务实例。
- 不在无人工确认的情况下写文件、建分支、提交或推送。
- 不替代 CI；v1 只做本地构建与测试的受控执行。
- 不做多租户 SaaS 计费与跨组织隔离。

---

## 2. 参考架构拆解（EvoFlow → 本平台）

| EvoFlow 概念 | 作用 | 本平台可复用的对应物 | 缺口 |
| --- | --- | --- | --- |
| Lead Agent + SOUL.md | 统一人设、行为边界 | 智能体定义 + Nacos Skill 提示词 | 需要「项目级 SOUL/规范」注入 |
| Plan（步骤：name/goal/agent/inputs/outputs/acceptance/depends_on） | 任务分解与 DAG | `TeamPlan` + `PlanValidator` + `TeamPlanCompiler` → `FlowDefinition` | 步骤缺少「产物/验收标准」字段 |
| Supervisor 自动跟进 | 按依赖调度、失败重试 | `AgentFlowExecutor` DAG 调度、节点并行 | 缺少跨步骤的自动续跑与人工插入 |
| tools/skills/memory/subagents | 能力扩展 | Toolkit、Skill 平台、KB 检索、子智能体（当前关闭） | 文件/终端/检索已内置，当前被 `HarnessAgentSupport` 关闭；记忆与子智能体按需后置 |
| Workspace（read/write/replace/delete/rg/find/search_code_index/terminal/process/worker） | 代码操作面 | harness `FilesystemTool`（read_file/write_file/edit_file/grep/glob/ls）+ `ShellExecuteTool` + `LocalFilesystemSpec`/`DockerFilesystemSpec`；当前被 `HarnessAgentSupport` 关闭 | 需启用并加路径策略/沙箱；无符号级索引 |
| Guardrails（工具允许/拒绝、OAP 策略、fail_closed） | 安全边界 | `ToolSafety` + `ActionProposal` + 权限/审计 | 缺工具级 allow/deny 策略引擎 |
| Sandbox | 隔离执行 | `payroll-sandbox`（Docker） | 只读、禁网、10 秒、脚本大小上限 |
| Task Center / Asset Center | 长任务与产物管理 | 会话/轮次/消息与追溯链 | 无独立任务中心与产物库 |

> 许可证提示：实施前必须复核 EvoFlow 仓库 LICENSE。本 PRD 仅参考其架构思想，不复制其代码。

---

## 3. 角色设计

职责边界（一句话）：产品经理只做需求分析 → 技术总监只定架构路线 → 项目经理拆分并向后端开发、前端开发、数据库工程师、运维工程师、测试、代码审查分发 → 执行角色回传结果。

共享能力：知识库检索、产物读写（按角色授权）。只读代码/配置检索授予技术总监、项目经理、后端开发、前端开发、数据库工程师、运维工程师、测试、代码审查；产品经理不读代码，避免把实现细节带进需求分析。

> 角色表中的工具名是**能力标签**；落地时统一映射到 harness 真实工具（`read_file`/`grep`/`glob`/`edit_file`/`write_file`/Shell 等），映射表由 P0-0 维护，避免提示词调用不存在的工具。

| 角色 | 输入 | 输出 | 下游 |
| --- | --- | --- | --- |
| 产品经理 | 用户原话、历史对话、产品文档 | `requirements.md` / `prd.md` | 技术总监 |
| 技术总监 | 需求分析、仓库结构与约定 | `architecture.md` / `interfaces.md`（含数据模型与部署拓扑） | 项目经理 |
| 项目经理 | 需求 + 架构路线 | `tasks.json` / `delivery-plan.md` | 后端开发、前端开发、数据库工程师、运维工程师、测试、代码审查 |
| 高级开发（后端） | 任务项 + 架构约束 | 服务端补丁提案 + 自测结果 | 测试、代码审查 |
| 前端工程师 | 任务项 + 接口契约 + 设计约束 | 前端补丁提案 + 构建结果 | 测试、代码审查 |
| 数据库工程师 | 任务项 + 数据模型/迁移策略 | 迁移脚本补丁 + 影响评估 + dry-run 结果 | 后端开发、测试、代码审查 |
| 运维工程师 | 任务项 + 部署拓扑 + 配置约束 | 部署/配置补丁 + 镜像与启动校验 + 回滚手册 | 测试、代码审查、交付 |
| 测试工程师 | 任务项 + 全部补丁 | `test-plan.md` / `test-report.md` | 代码审查、项目经理/交付 |
| 代码审查工程师 | `interfaces.md` + `tasks.json` + 四类补丁 diff + `test-report.md` | `review-report.md`（阻断/建议/通过 + 文件行号） | 项目经理/交付 |

并行与串行规则：

- 后端与前端按 `interfaces.md` 中的接口契约拆成成对任务，共享同一验收口径；前端不得自行假设未确认的接口字段，契约变更必须回到技术总监并触发 G2 复审。
- 涉及表结构变更时，数据库工程师的迁移脚本先于后端实现产出，后端按迁移后的模型编码；迁移脚本与后端补丁在 G2 同批确认。
- 运维变更（镜像、Compose、nginx、CI、环境变量清单）的补丁与代码并行产出；测试通过后由运维完成镜像构建与启动校验，并定稿回滚手册；实际发布不在 agent 侧执行。
- 代码审查与测试并行启动：补丁产出后即可审查，`test-report.md` 作为输入之一；G2 前必须出结论，阻断项打回对应角色，复审通过后随下一批进入 G2。审查不单独设门，结论并入 G2 摘要。
- 所有角色的凭据只经环境变量/密钥注入；任何产物（文档、脚本、补丁、日志）不得出现明文密码。

### 3.1 产品经理智能体（project-product）

- 职责：**只做需求分析**。根据用户自然语言输入，明确目标、用户与场景、范围与非目标、验收标准、歧义点与待确认问题。
- 输入：用户原话、历史对话、产品文档、知识库。
- 输出：`requirements.md`（需求分析）、`prd.md`（用户故事、边界、非目标、验收标准）。
- 工具：`kb_search`、`artifact_write`；不授予代码检索与终端。
- 完成判定：每条验收标准可测试；歧义点有默认假设或待确认清单。
- 禁止：技术选型/架构决策、拆分开发任务、修改代码。

### 3.2 技术总监智能体（project-architect）

- 职责：**只制定架构路线**。根据需求分析确定模块边界、关键技术选型、接口契约、数据与迁移策略、阶段里程碑、风险与回滚点。
- 输入：`requirements.md` / `prd.md`、仓库结构、现有约定（AGENTS/README/测试基线）。
- 输出：`architecture.md`（路线图、备选方案、风险）、`interfaces.md`（契约）。
- 工具：只读代码检索 + `artifact_write`。
- 完成判定：路线覆盖全部需求；标注不可行项、替代方案与回滚点。
- 禁止：拆分到人/任务颗粒度、直接生成代码补丁。

### 3.3 项目经理智能体（project-manager）

- 职责：**把需求与架构路线拆成可执行任务并分发**。维护任务 DAG、负责人（后端开发/前端开发/数据库/运维/测试/代码审查）、优先级、排期与验收口径。
- 输入：`requirements.md`、`architecture.md`、团队能力清单。
- 输出：`tasks.json`（任务、负责人、依赖、输入、输出、验收）、`delivery-plan.md`（排期/里程碑）。
- 工具：只读代码检索（评估工作量）、`artifact_write`、任务面板写入。
- 完成判定：每个任务有唯一负责人、依赖无环、验收可测试；后端/前端/数据库/运维/测试任务按契约成对覆盖，并为每个补丁任务生成对应审查任务。
- 禁止：修改业务代码、替开发做实现决策、跳过产品/总监确认。

### 3.4 高级开发工程师智能体（project-implementer，后端）

- 职责：按项目经理分发的任务实现服务端最小变更；先读后写；自测。
- 输入：任务项、架构约束、接口契约、相关代码片段与测试。
- 输出：补丁提案（unified diff）+ 变更说明 + 自测结果。
- 工具：只读检索、沙箱内 `edit_file`/`write_file`（限定 `payroll-*/src/**`）、`test_run`（受限）、`artifact_write`；任务结束统一取 `git diff` 作为补丁提案。
- 完成判定：补丁可应用；构建与目标测试通过；接口实现与 `interfaces.md` 一致；无新增越权文件。
- 禁止：触碰路径黑名单（`.git/`、`data/`、密钥文件）、自动 commit/push、擅自改动接口契约。

### 3.5 前端工程师智能体（project-frontend）

- 职责：按项目经理分发的前端任务实现界面与交互最小变更，严格对齐接口契约；自测构建。
- 输入：任务项、接口契约（`interfaces.md`）、设计/交互约束、`frontend/src/**` 相关组件与现有页面。
- 输出：前端补丁提案（unified diff，限定 `frontend/src/**`、`frontend/public/**`）+ 变更说明 + `npm run build` 结果。
- 工具：只读检索、沙箱内 `edit_file`/`write_file`（限定 `frontend/src/**`、`frontend/public/**`）、`test_run`（受限，当前仅 `npm run build`）、`artifact_write`；任务结束统一取 `git diff`。
- 完成判定：补丁可应用；`vite build` 通过；页面调用的接口字段与契约一致；无硬编码密钥/调试后门。
- 禁止：触碰 `frontend/node_modules/`、`frontend/dist/`、`data/`、密钥文件；不得修改后端代码或接口契约来迁就前端实现；自动 commit/push。
- 现状约束：`frontend/package.json` 的 devDependencies 只有 `vite` 与 `@vitejs/plugin-vue`，**没有 vitest/Playwright**，因此前端「自测」当前只能是构建通过；组件/端到端测试需随 P1-5 补齐。

### 3.6 测试工程师智能体（project-qa）

- 职责：根据需求验收标准与项目经理分配的任务设计用例；执行后端构建/单测与前端构建；回归与风险报告。
- 输入：`requirements.md`、任务项、架构约束、全部补丁（后端/前端/数据库/运维）、测试基线。
- 输出：`test-plan.md`、`test-report.md`（通过/失败、日志、复现步骤）。
- 工具：`test_run`（Maven/npm）、只读检索、`artifact_write`。
- 完成判定：验收标准逐条有结论；前后端联调口径一致；失败项有最小复现。
- 禁止：修改业务代码来「让测试通过」。

### 3.7 数据库工程师智能体（project-dba）

- 职责：数据模型落地与数据安全。按架构的迁移策略产出 Flyway 迁移脚本、索引与约束调整、数据修复脚本，并给出兼容性与回滚评估。
- 输入：任务项、`interfaces.md` 的数据契约、现有 `**/db/migration/**`、表结构与查询热点。
- 输出：迁移脚本补丁（限定 `**/db/migration/**`、`scripts/sql/**`）+ 影响评估（锁表/耗时/回滚）+ dry-run 结果。
- 工具：只读检索、沙箱内 `edit_file`/`write_file`（限定上述路径）、`test_run`（受限：迁移 dry-run 与方言校验）、`artifact_write`；任务结束统一取 `git diff`。
- 完成判定：脚本可在空库与存量库各执行一次；方言兼容（MySQL 走 Flyway、H2 走内联 DDL，两条路径分别验证）；可逆或明确标注不可逆并提供补偿方案；无明文凭据。
- 禁止：直接连接生产库/共享库；修改业务代码、`data/` 下的 H2 文件与上传文件；在脚本或产物中写死账号密码；跳过 G2 直接改 schema。
- 约束：MySQL/Redis 连接串与口令只从环境变量或密钥注入；DBA 无终端网络权限，全部在 `network=none` 容器内 dry-run。

### 3.8 运维工程师智能体（project-devops）

- 职责：环境与交付面。维护 Dockerfile/Compose、nginx 配置、CI 流水线、环境变量与密钥清单、健康检查、日志与指标接入，产出可回滚的发布方案。
- 输入：任务项、部署拓扑（`architecture.md`）、现有 `docker/**`、`deploy/**`、`frontend/` 构建配置、配置模板。
- 输出：部署/配置补丁（限定 `docker/**`、`deploy/**`、`scripts/**`、CI 配置）+ 镜像构建与启动校验结果 + `rollback.md`。
- 工具：只读检索、沙箱内 `edit_file`/`write_file`（限定上述路径）、`test_run`（受限：镜像构建、`docker compose config`、容器内健康检查）、`artifact_write`；任务结束统一取 `git diff`。
- 完成判定：镜像可构建；Compose 配置校验通过；端口/卷/环境变量与文档一致；密钥只引用变量名；回滚步骤可执行且有验证点。
- 禁止：执行真实发布（只产出方案，发布本身是人类在 G3 后的动作）；改主机网络/防火墙；重启本机正在运行的服务实例；在配置或手册中写明文口令；使用 `--privileged` 或未声明的宿主挂载。
- 约束：本机现有部署方式（nginx 8880、8100/8080/8200 端口、IDEA 运行配置）视为现状基线，运维补丁不得静默改变端口与启动方式，变更需在 G2 中单列。

### 3.9 代码审查工程师智能体（project-reviewer）

- 职责：对四类补丁做独立静态审查，只读不改：契约一致性、正确性、安全边界、可维护性与测试覆盖。
- 输入：`interfaces.md`、`tasks.json`、后端/前端/数据库/运维 diff、`test-report.md`、相关源码与测试。
- 输出：`review-report.md`（逐文件结论：通过/建议/阻断；阻断项带 `文件:行号` 与修复建议）。
- 工具：只读检索 + `artifact_write`；不授予 `edit_file`/`write_file`/Shell，避免既写又审。
- 完成判定：四类 diff 全覆盖；契约、安全、测试覆盖三类检查均有结论；阻断项可定位、可复现。
- 禁止：直接修改代码或补丁（只能打回）、代替测试执行、放行构建失败或含越权路径/明文凭据的补丁、跳过 G2 自行放行。
- 约束：与开发共用同一编码模型实例时独立性有限，必须以检查清单 + 行号证据 + G2 人工抽样复核补足，不因「模型自评通过」而免检。

---

## 4. 工作流与交付物

```text
用户需求
  → ① 需求分析（产品经理，必要时向用户追问）
  → ② 需求确认（人工门 G1）
  → ③ 架构路线（技术总监：模块边界、接口契约、数据与迁移策略、部署拓扑；可评审，不阻塞）
  → ④ 任务拆分与分发（项目经理产出 tasks DAG，可评审，并入 G2）
  → ⑤ 实现与自测（数据库迁移先于后端；后端/前端按契约并行；运维补丁独立；全部在隔离工作区内结构化编辑并取 diff，此阶段无需中途审批）
  → ⑥ 测试与回归（测试工程师：构建 + 目标测试 + 迁移 dry-run）
  → ⑦ 代码审查（代码审查工程师：四类 diff 的契约/安全/覆盖静态审查，与⑥并行）
  → ⑧ 运维校验（运维工程师：镜像/Compose/配置校验 + 回滚手册）
  → ⑨ 计划+补丁批量确认（人工门 G2：tasks/契约 + 四类 diff + 构建测试/审查/校验摘要）
  → ⑩ 验收与交付（人工门 G3：建分支/提交/PR；发布由人类执行）
```

阶段产物与验收：

| 阶段 | 负责人 | 产物 | 验收 | 人工门 |
| --- | --- | --- | --- | --- |
| 需求分析 | 产品经理 | `requirements.md` / `prd.md` | 歧义点有默认假设、验收标准可测试 | **G1** |
| 架构路线 | 技术总监 | `architecture.md` / `interfaces.md`（含数据模型与部署拓扑） | 覆盖全部需求、有风险与回滚点、接口/数据契约可落地 | 否（可评审） |
| 任务拆分与分发 | 项目经理 | `tasks.json` / `delivery-plan.md` | 负责人明确、依赖无环、后端/前端/数据库/运维/测试按契约成对并生成审查任务 | 否（可评审，并入 G2） |
| 数据库变更 | 数据库工程师 | `db/migration/*.sql` + 影响评估 | 空库/存量库各执行一次、双库方言兼容、可逆或有补偿 | 否（沙箱内执行） |
| 后端实现 | 高级开发（后端） | `patch-backend-*.diff` + 说明 | 补丁可应用、目标测试通过、契约一致 | 否（沙箱内执行） |
| 前端实现 | 前端工程师 | `patch-frontend-*.diff` + 说明 | 补丁可应用、`vite build` 通过、字段与契约一致 | 否（沙箱内执行） |
| 运维变更（补丁） | 运维工程师 | 部署/配置补丁 + `rollback.md` 初稿 | 覆盖镜像/Compose/nginx/CI/变量清单；密钥只引用变量名 | 否（沙箱内执行） |
| 测试 | 测试工程师 | `test-report.md` | 验收标准逐条结论、迁移 dry-run 通过、联调口径一致 | 否 |
| 代码审查 | 代码审查工程师 | `review-report.md` | 四类 diff 全覆盖、阻断项带文件行号、契约/安全/覆盖检查齐全 | 否（并入 G2） |
| 运维校验 | 运维工程师 | 校验结果 + `rollback.md` 终版 | 镜像可构建、Compose 校验通过、容器内健康检查通过、回滚步骤可执行 | 否（沙箱内执行） |
| 计划+补丁确认 | 用户（项目经理汇总） | `tasks.json` + 四类 `git diff` + 构建/测试/审查/校验摘要 | 逐任务通过或打回；阻断项未关闭不得通过；打回回到对应角色 | **G2** |
| 交付 | 产品/总监/项目经理 | 汇总报告 + 变更清单 + 回滚手册 | 人审通过；发布由人类执行 | **G3** |

产物规范：全部产物按 `dev_tasks/<task_id>/` 存放（`requirements.md`、`architecture.md`、`tasks.json`、`patch-*.diff`、`test-report.md`、`review-report.md`、`rollback.md`），产物库为唯一事实源；每份产物记录 owner、task_id 与版本号，补丁另存 diff hash。

DAG 映射：九个角色分别对应 `FlowDefinition` 的 `AGENT` 节点（后端、前端、数据库、运维为同层可并行节点，数据库节点在存在 schema 变更时被后端任务依赖；审查节点依赖四类补丁并与测试节点并行；共享同一份 `tasks.json` 的接口与数据契约）；G1、G2、G3 对应 `CONDITION` 或人工确认节点；项目经理的 `tasks.json` 作为动态计划输入，扩展 `outputs` 与 `acceptance` 后由 `TeamPlanCompiler` 编译为可执行流程。

门禁语义（A2 已确认）：

- **G2 是唯一一次补丁级审批**。用户在 G2 之前可随时查看 `architecture.md`、`tasks.json` 与已生成的 diff；若计划本身存在明显偏差，可提前打回，不必等补丁齐备。默认不阻塞，是为了把长任务的停顿从 5 次降到 3 次。
- G2 前的实现全部发生在 `DockerFilesystemSpec` 隔离工作区（`network=none`、不写主工作区、不产生不可逆副作用），因此"计划错了先白跑"的代价被限制在算力内，可以接受。
- G2 通过后按任务接纳补丁，G3 再统一建分支/提交/PR；单任务打回只丢弃该任务的沙箱产物，其余任务继续。
- 代码审查不是独立人工门：审查结论（通过/建议/阻断）并入 G2 摘要；存在未关闭阻断项的任务不得进入 G2。

### 4.1 权限模型（A1 已确认）

权限由两个正交维度决定：**沙箱模式**决定「能动什么」，**审批策略**决定「什么时候必须停下来问人」。**不提供 danger-full-access。**

| 沙箱模式 | 可写范围 | 终端能力 | 允许的审批策略 |
| --- | --- | --- | --- |
| `read-only`（分析/验证默认） | 无写入，只读挂载 worktree | 仅只读命令（`grep`/`find`/`git status`/`git diff`） | `auto`、`batch`、`per-task` |
| `workspace-write`（编码任务默认） | 仅隔离 worktree；主仓库、`.git/`、`data/`、密钥不可写 | 容器内构建/测试，`network=none`，`memorySizeBytes`/`cpuCount` 限额 | `batch`（默认）、`per-task`；**禁止 `auto`** |

| 审批策略 | 语义 | 对应门禁 |
| --- | --- | --- |
| `auto` | 全程无人值守，仅 `read-only` 可用；违规或失败即停 | 无（只读任务） |
| `batch`（默认） | 隔离工作区内连续执行至补丁齐备，G2 一次确认全部 diff 后才进入交付；构建/测试失败、路径或策略违规时立即暂停待人工 | G1、G2、G3 |
| `per-task` | 高风险任务（契约变更、数据迁移、依赖升级）在 G2 内逐任务确认，其余任务仍批量 | G1、G2（拆分）、G3 |

失败与合规语义（fail-closed）：

- 任一护栏触发（越权路径、黑名单文件、网络出站、命令不在 allowlist）→ 该任务置 `FAILED` 并暂停，**不自动重试**；重试须人工发起且计次。
- 开发域不信任工具自报只读：第三方/MCP 工具默认按「可能修改」处理，只读声明必须进入 allowlist 才生效。
- 写权限在 `workspace-write` 之上还有按角色的路径白名单：后端 `payroll-*/src/**`、前端 `frontend/src/**`+`frontend/public/**`、数据库 `**/db/migration/**`+`scripts/sql/**`、运维 `docker/**`+`deploy/**`+`scripts/**`+CI 配置（如 `.github/workflows/**`）；越界即拒绝并记审计。代码审查、测试只读，不授予任何写入路径（产物经 `artifact_write` 落产物库）。
- 路径白名单按仓库 profile 配置（`payroll.dev-team.paths.<repo>`）；payroll 平台只是默认 profile，接入其他 Maven/Vue 仓库只替换 profile，不改代码。
- `mode` / `approval` 的每次变更写入审计；启动时校验组合合法性，`approval=auto` 且 `mode=workspace-write` 直接启动失败。

映射到现有代码：

| 维度 | 实现 |
| --- | --- |
| read-only | `LocalFilesystemSpec(SANDBOXED｜ROOTED)` + `PathPolicy`；开发域禁用 `LocalFsMode.UNRESTRICTED` |
| workspace-write | `DockerFilesystemSpec`（`network=none`）；`ShellExecuteTool` 只绑定容器文件系统 |
| 审批 | 以任务级审批令牌替换 `ActionGuard` 单轮单写；`ToolSafety` 保留分类，开发域默认 deny |
| 配置 | `payroll.dev-team.mode=read-only｜workspace-write`、`payroll.dev-team.approval=batch｜per-task｜auto` |

配置映射：`payroll.dev-team.*` 是 Spring 属性，可由环境变量按宽松绑定覆盖；§8.4 的 `PAYROLL_DEV_*` 是编码模型专属变量，两组命名不重叠、互不覆盖。

---

## 5. 现有平台可复用能力（证据）

| 能力 | 现状 | 证据 |
| --- | --- | --- |
| 动态组队与计划 | 意图 → 能力索引 → 短名单 → 计划 → 校验 → 编译 → DAG 执行 | `payroll-agent/.../team/TeamPlanGenerator.java`、`TeamPlanValidator.java`、`TeamPlanCompiler.java`；`docs/dynamic-agent-team-spec.md` |
| 流程 DAG | START/AGENT/CONDITION/END，节点并行与追溯 | `payroll-agent/.../service/AgentFlowExecutor.java`、`docs/agent-orchestration.md` |
| 写操作确认 | 工具只产出提案，用户确认后执行 | `payroll-common/.../domain/ActionProposal.java`、`ActionProposalService.java`、`ToolSafety.java` |
| 技能平台 | Nacos 技能、提示词、文件包与脚本 | `docs/nacos-skills/`、`SkillProjectParser`（setup 自动推导） |
| 知识库 | 检索、引用、主题门禁 | `docs/payroll-kb-routing-policy` 相关实现 |
| 追溯 | message_id → run_id → node_id → trace_id | `docs/dynamic-agent-team-spec.md` §7 |
| 沙箱 | Docker 只读脚本执行 | `DockerSandboxRunner.java:145-168` |
| harness 工具面 | 文件读写/编辑/检索、Shell、计划模式、任务查询、子智能体 | `agentscope-harness 2.0.1`：`FilesystemTool`、`ShellExecuteTool`、`PlanModeTools`、`TaskTool`、`SubagentDeclaration`；当前在 `HarnessAgentSupport.java:54-66` 全部关闭 |
| 文件系统策略 | ROOTED 模式 + 路径白名单 + 项目可写开关 + 命令超时/输出上限 | `LocalFilesystemSpec`（`mode(ROOTED)`、`projectWritable`、`executeTimeoutSeconds`、`maxOutputBytes`）、`PathPolicy` |
| 容器化文件系统 | 镜像、网络、内存、CPU、附加 run 参数 | `DockerFilesystemSpec`（`image`、`network`、`memorySizeBytes`、`cpuCount`、`additionalRunArgs`） |
| 异步任务队列 | 状态机、取消、重试、执行器 | `KbTaskService.java`、`KbTaskStore.java`、`@Qualifier("kbIngestExecutor")` |
| 流程 DAG 并行/条件 | 同层节点并行、CONDITION 分支、单末端节点推流 | `AgentFlowExecutor.java:146-205` |
| 前端工程 | Vue 3 + Vite 6 + Element Plus + Vue Router + Vue Flow，`npm run build` 可产出 `dist/` | `frontend/package.json`、`frontend/vite.config.js` |
| 权限/审计 | 登录、RBAC、审计日志 | `payroll-backend/...` 认证与审计模块 |

---

## 6. 差距分析（GAP1–GAP14）

> 复核结论：GAP1–GAP4 是**当前配置关闭**，不是框架能力缺失；调用 `HarnessAgentSupport` 的禁用开关并调整工具白名单即可恢复。GAP5/GAP6 可通过切换 harness 的 `DockerFilesystemSpec` 或扩展现有 sandbox 解决。状态列区分「可配置启用 / 方案已定 / 已覆盖 / 待解决」，真正需要新建的只剩符号索引、前端测试基线、产物中心与任务级审批 UI。

| 编号 | 差距 | 现状证据 | 影响 | 状态 |
| --- | --- | --- | --- | --- |
| GAP1 | 文件系统工具被显式关闭（可启用） | `HarnessAgentSupport.java:54` `disableFilesystemTools()`；harness 已有 `FilesystemTool` | 当前无法读写仓库；启用后受路径策略约束 | 可配置启用（P0-3） |
| GAP2 | Shell 工具被显式关闭（可启用） | `HarnessAgentSupport.java:55` `disableShellTool()`；harness 已有 `ShellExecuteTool` | 当前无法构建/测试；本地 shell 非沙箱，须优先容器 | 可配置启用（P1-3） |
| GAP3 | 子智能体关闭（可启用） | `HarnessAgentSupport.java:61-62`；harness 已有 `SubagentDeclaration`/`DefaultAgentManager` | 当前无法做角色内派生；M1+ 建议保持关闭以控成本 | 保持关闭（A12） |
| GAP4 | 工作区上下文关闭（可启用） | `HarnessAgentSupport.java:59`；harness 有 `WorkspaceContextMiddleware` | 模型不知道仓库结构；编码前必须恢复 | 可配置启用（P0-9/A8） |
| GAP5 | 沙箱只读且禁网 | `DockerSandboxRunner.java:147-168`：`--network none`、`--read-only`、`--user 65534`、`--cap-drop ALL`、`/work` noexec、256MB、1 CPU | 不能写文件、不能拉依赖、不能跑常规构建；需项目级可写工作区 | 方案已定（A6/C5） |
| GAP6 | 脚本执行上限 10 秒/64KB | `SandboxProtocol.java:22-28` | 无法运行 Maven/npm 测试 | 方案已定（C6/P1-3） |
| GAP7 | 无代码索引/语义检索 | 仅依赖在 classpath 中（tree-sitter core 与 bash 语法），未接入 | 大仓库定位困难 | 待解决（P2-2） |
| GAP8 | 无独立任务中心/产物库 | 仅有会话消息持久化 | 长任务无法脱离聊天轮次 | 方案已定（C8/P1-2） |
| GAP9 | 轮次硬超时 150 秒 | `PayrollChatService.java:80` `STAGE_TIMEOUT=150s` | 多步编码必超时 | 方案已定（C8/D6） |
| GAP10 | 路由/计划与主回答共用同一模型，且超时 10 秒 | `PayrollAgentConfiguration.java:176`；实测 27B 下 `route-preview` 10.9s 返回「轻量模型不可用或超时」 | 大模型下每次请求先浪费 10 秒并退化；需模型分层 | 已覆盖（A7） |
| GAP11 | 前端无测试基线 | `frontend/package.json` devDependencies 只有 `vite` 与 `@vitejs/plugin-vue`，无 vitest/Playwright | 前端「自测/回归」只能做到 `vite build`，交互与端到端无自动验证 | 待解决（P1-5） |
| GAP12 | 审批粒度不匹配编码循环 | `ActionGuard.java:124-130`：本轮已生成提案后拒绝同轮后续写调用 | 一次任务需要几十次编辑，逐调用确认不可行；需任务级批量审批 | 已覆盖（A1 + P0-8） |
| GAP13 | 上下文预算不足 | `PayrollAgentFactory.java:56-57`：`numCtx=8192`、`numPredict=1024` | 读几个文件就溢出；补丁输出被截断，M1 前必须提高 | 已覆盖（A7） |
| GAP14 | 产物交付工具在 2.0.1 缺失 | `ArtifactDeliveryTool` 只存在于 harness `2.0.3-SNAPSHOT`，当前依赖 `2.0.1`（`pom.xml:35`） | 产物中心需自建存储或升级 harness | 待解决（C10） |

---

## 7. 需要新增的能力

| 编号 | 能力 | 最小范围 | 实现方式（复核后） | 安全约束 |
| --- | --- | --- | --- | --- |
| C1 | Workspace 服务 | 按任务绑定仓库、创建 worktree/分支、记录基线 commit | **适配**：JGit worktree + harness `workspace`；`WorkspaceManager` 不管 Git | 每任务独立目录；只挂载目标仓库 |
| C2 | 只读文件工具 | `read_file`、`grep`、`glob`、`ls` | **启用**：harness `FilesystemTool` + `LocalFilesystemSpec(ROOTED)` + `PathPolicy` | 路径白名单；大小上限；拒绝 `.git/`、密钥、`data/` |
| C3 | 代码检索 | 文本/正则检索 + 结果行号 | **启用**：`grep`/`glob`；符号索引后置 | 结果截断；敏感文件过滤 |
| C4 | 补丁提案 | 结构化编辑 + 任务结束后统一出 diff | **调整**：用 `edit_file`（old/new）或 `write_file` 在沙箱工作区应用，再取 `git diff`；不要求模型直接产出 unified diff | 仅沙箱内应用；G2 批量确认后才进入交付；记录 diff 与 hash |
| C5 | 受限终端 | 容器内命令，默认禁网，预热依赖缓存 | **启用+收紧**：`ShellExecuteTool` + `DockerFilesystemSpec`（`network`、`memorySizeBytes`、`cpuCount`、`additionalRunArgs`） | 只允许容器；本地 shell 仅只读分析 |
| C6 | 测试运行器 | Maven/npm 配置档，后端单测 + 前端构建 | **适配**：`execute` + 按命令类型分档超时（构建 10–20 分钟）+ `.m2`/npm 缓存卷 | 只读仓库 + 可写临时目录；禁止 publish/deploy |
| C7 | Git 服务 | JGit 建分支、commit、diff、生成 PR 描述 | **新建**：JGit（依赖已在 `payroll-common/pom.xml`） | 凭证由宿主代理；push/PR 需 G3 审批 |
| C8 | 任务中心 | 异步 job、状态机、取消、重试、恢复 | **复用**：`KbTaskService`/`KbTaskStore` 模式，新增 dev_task 表与执行器 | 与聊天轮次解耦；按用户配额 |
| C9 | 护栏引擎 | 工具 allow/deny、路径策略、fail_closed、注入防护 | **适配**：`ToolSafety` + `PathPolicy` + 容器边界；新增两维权限配置（§4.1）与任务级策略 | 默认拒绝；策略变更走审计 |
| C10 | 产物库 | 需求/架构/任务/补丁/测试报告/审查报告版本化 | **新建**：2.0.1 无 `ArtifactDeliveryTool`；可复用附件/技能文件存储 | 与会话/任务/追溯关联 |

---

## 8. 可行性评估

### 8.1 分能力结论

| 能力 | 结论 | 依据 |
| --- | --- | --- |
| 九角色分工与文档产出（产品/总监/项目经理/后端/前端/数据库/运维/测试/代码审查） | **高** | 现有 Skill 平台与 KB 可直接承载；交接物以文档与 diff 为主 |
| 团队 DAG 与任务分发 | **高** | 动态团队已实现并落地（P0–P2 已合入）；项目经理产物可直接编译为计划 |
| 后端与前端并行实现 | **中高** | `AgentFlowExecutor` 已支持同层并行；前端只需检索 + 编辑 + `vite build` |
| 只读代码检索 | **高** | harness `grep`/`glob`/`ls` 直接可用，配 `PathPolicy` 即可 |
| 写补丁 + 确认应用 | **中高** | `edit_file`/`write_file` 已内置；需任务级审批与 diff 快照（GAP12） |
| 终端/构建/测试 | **中** | `ShellExecuteTool` + `DockerFilesystemSpec` 已具备；需镜像、缓存卷、超时档位 |
| 任务中心 | **中高** | `KbTaskService` 的状态机/取消/重试可复用，新增 dev_task 表 |
| 前端交付质量门 | **中低** | 当前只有 `vite build`（GAP11）；交互回归需新增 vitest/Playwright 与浏览器环境 |
| 数据库迁移产出（DBA） | **中高** | MySQL 走 Flyway、H2 走内联 DDL，双库路径均已落地；迁移路径固定，可加白名单与双路径 dry-run；需在 `network=none` 容器内执行 |
| 运维配置与回滚（DevOps） | **中高** | 已有 Docker/Compose 与 nginx 部署可参照；只产出补丁与校验结果，发布保留给人类 |
| Git/PR | **中** | JGit 依赖已在；缺凭证代理、分支策略与审批 |
| 代码审查（静态） | **中高** | 只读检索 + 检查清单即可产出逐文件结论；与开发共用编码模型时独立性有限，需行号证据与 G2 人工抽样补足 |
| 端到端交互式编码 | **低（当前环境）** | 3B 无法编码；27B 实测 1.49 token/s；8192 ctx 读不下多文件（GAP13） |

### 8.2 模型可行性实测（2026-11-17，本机）

- 硬件：32GB RAM，GTX 1650 4GB；`ollama ps` 显示 **90% CPU / 10% GPU**。
- 模型：`qwen3.8:27b`，27.3B，Q4_K_M，17GB，支持 tools/thinking。
- 冷启动：加载 29.5 秒；首个短请求总耗时 48.4 秒。
- 稳定吞吐：约 **1.49 token/s**（34 token 生成耗时 22.8 秒）。
- 应用内验证：`/api/agents/route-preview` 在 10.9 秒返回「轻量模型不可用或超时」；一次聊天流 215 秒仅产生 64 个 thinking token，未产出回答（已取消）。
- 结论：路由 10 秒、阶段 150 秒的现有预算与该模型不匹配。模型分层（控制面小模型 + 编码模型）或云端/GPU 推理是前置条件。
- 复核：测试后已把运行期模型切回 `qwen2.5:3b-instruct`；预热后 `route-preview` 6.9 秒、端到端回答约 10.9 秒，证明「控制面小模型」路径可行；编码模型已按 A7 定为独立 `PAYROLL_DEV_MODEL`（§8.4）。

### 8.3 商业化与合规

- EvoFlow 仅作架构参考；LICENSE 需实施前复核。
- 代码外发、仓库读权限、Prompt 注入（仓库内容视为不可信输入）必须纳入护栏与审计。

### 8.4 编码模型分层（A7 已确认）

分两档，**不共用模型实例**：

| 层 | 承担 | 配置 | 参数 |
| --- | --- | --- | --- |
| 控制面 | 意图/路由/团队规划/JSON 抽取/文档角色 | 复用现状 `PAYROLL_MODEL` + `sharedModel()` | `numCtx=8192`、`numPredict=1024`、`temperature=0.1`、单次 10 秒 |
| 编码面 | 后端/前端补丁生成、自测修复 | **新增独立** `PAYROLL_DEV_MODEL`（`ollama:<模型>` 或 `dashscope:<模型>`），独立实例，不走 `sharedModel()` | `numCtx ≥ 32768`、`numPredict ≥ 4096`、`temperature ≤ 0.2`、单次 300 秒 |

约束：

- 编码模型必须原生支持 ≥32k 上下文。本机 4GB 显存的 27B 实测 1.49 token/s，不满足要求；可选云端 API 或 ≥24GB 显存本地推理。
- Ollama 单实例串行：编码面每实例并发 1（对应 D10），控制面维持现有并发。
- `mode=workspace-write` 启动时探测编码模型（可用性 + 上下文长度）。缺失或低于 32768 时**编码能力关闭**，dev-team 退回 `read-only`；禁止静默用 3B 生成补丁。
- 编码面超时独立：模型单次 300 秒，构建/测试 10–20 分钟；不沿用聊天轮次 150 秒与 `TeamLlmClient` 10 秒。
- 文档角色默认仍走控制面小模型；M0 实测不达标时再单独配置，不提前抽象第三档。

配置映射（新增，全部为环境变量）：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PAYROLL_DEV_MODEL` | 未设置 | 未设置则编码能力关闭 |
| `PAYROLL_DEV_OLLAMA_BASE_URL` | 继承 `OLLAMA_BASE_URL` | 可指向另一台推理机 |
| `PAYROLL_DEV_NUM_CTX` | 32768 | 低于 32768 启动即告警并禁用写模式 |
| `PAYROLL_DEV_NUM_PREDICT` | 4096 | |
| `PAYROLL_DEV_TEMPERATURE` | 0.2 | |
| `PAYROLL_DEV_TIMEOUT_SECONDS` | 300 | 单次模型调用 |

M1 门槛：在 3 个真实任务上，编码模型在 32k 上下文内完成「读 3–5 个文件 + 产出可应用补丁」，补丁可应用率 ≥ 80%；首补丁时延按实测记录，不设硬门槛（本机 27B 已确认不达标）。

---

## 9. MVP 范围与里程碑

| 里程碑 | 范围 | 退出标准 | 预估 |
| --- | --- | --- | --- |
| S0 工具面验证（1 周） | `read-only + auto`：在只读 worktree 上启用 `read_file`/`grep`/`glob`，用现有模型完成「定位并解释一个实现」 | 3 个真实问题可定位；无越权路径；记录 token 与耗时 | 1 周 |
| M0 只读分析团队 | 产品经理做需求分析 → 技术总监出架构路线（含数据与部署拓扑）→ 项目经理向后端/前端/数据库/运维/测试/代码审查分发（任务 DAG），全程不改文件 | 3 个真实需求产出可评审文档与任务 DAG；全链路可追溯 | 1–2 周 |
| M1 补丁提案 | 切 `workspace-write + batch`；编码走独立 `PAYROLL_DEV_MODEL`（≥32k）；后端、前端、数据库、运维四类补丁按任务产出，代码审查与测试出结论后，G2 一次性批量确认 | 后端/前端 diff 可应用率 ≥ 80%；迁移 dry-run 100%；运维配置校验 100%；四类补丁审查覆盖 100% 且阻断项清零；无越权写入 | 2–3 周 |
| M2 测试闭环 | 受限 runner 执行后端构建/单测、前端 `vite build`、迁移 dry-run 与镜像构建；任务中心异步化 | 目标测试可复现；迁移 dry-run 可复现；任务可取消/恢复 | 3–4 周 |
| M3 Git 交付 | 分支/commit/PR 描述 + 回滚手册，G3 审批 | 人工审批后建 PR；回滚步骤可执行；零自动推送 | 4–6 周 |

M0 不依赖写入沙箱，可完全复用现有团队 DAG 与确认机制，是风险最低的验证路径。

---

## 10. 决策记录

### 10.1 已决（2026-11-17）

| 编号 | 决策 | 结论 |
| --- | --- | --- |
| D1 | 编码主模型 | 独立 `PAYROLL_DEV_MODEL`，`numCtx ≥ 32k`、`numPredict ≥ 4096`；控制面固定 3B 小模型（A7，详见 §8.4） |
| D3 | 写模式 | 结构化编辑仅在隔离 worktree + 容器内应用，G2 批量确认 diff；禁止直接写主工作区、禁止自动落盘到交付分支（A1/A2 下的默认值） |
| D5 | 终端边界 | 容器内 allowlist，默认禁网，依赖走预热缓存（A1 下的默认值） |
| D7 | 审批门 | 三道门：G1 需求、G2 计划+补丁、G3 交付（建分支/commit/PR）；push 由宿主凭证代理，不在 agent 侧持有（A2） |
| D12 | 权限默认值 | 分析类 `read-only + auto`；常规编码 `workspace-write + batch`；契约变更/迁移/依赖升级 `workspace-write + per-task`（A1） |

### 10.2 待决（含建议默认）

| 编号 | 决策 | 建议默认 |
| --- | --- | --- |
| D2 | 工作区隔离 | 每任务独立 worktree + 容器；不复用现有脚本沙箱 |
| D4 | 仓库与路径范围 | 单仓库白名单 + 路径黑名单（`.git/`、`data/`、密钥） |
| D6 | 长任务 | 独立任务中心异步执行，不绑定 150 秒聊天轮次 |
| D8 | 追溯粒度 | 记录 diff、命令、退出码、模型与 token 用量 |
| D9 | 许可证 | 只参考 EvoFlow 思想，不复用代码 |
| D10 | 并发与配额 | 每用户 1–2 个任务并发，容器资源限额 |
| D11 | 前端质量门 | v1 以 `vite build` + 人工浏览器验收为准；M2 后补 vitest 组件测试，M3 前评估 Playwright 端到端 |
| D13 | 数据库与运维边界 | DBA 只写迁移/SQL 脚本、只在 `network=none` 容器 dry-run，不连生产库；运维只改部署/配置文件并做构建校验，真实发布由人类在 G3 后执行 |

> D13 已按 §3.7/§3.8 写入角色约束，待业务确认后转入已决。

### 10.3 版本变更

- v0.1 初版（角色与流程草案）。
- v0.3 现框架复核与可行性重估（修正记录见 §14.3）。
- v0.5 人工门由五道压缩为三道（A2）。
- v0.8 新增数据库工程师与运维工程师，角色扩为八个。
- v0.9 P0 复核修正：G2 与沙箱执行顺序、角色工具名、路径白名单、时延指标。
- v1.0 P1 复核修正：测试与运维阶段顺序、A12 表述、GAP 状态列、决策记录拆分、指标分类。
- v1.1 P2 复核修正：附录证据、Codex/Qoder 对照补齐、非目标、产物规范、仓库 profile、指标定义。
- v1.2 新增代码审查工程师（`project-reviewer`），角色扩为九个；审查作为只读静态检查环节与测试并行，结论并入 G2 摘要，阻断项未关闭不得过 G2。

---

## 11. 风险与缓解

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| 编码模型吞吐/显存不足导致超时 | 高 | 编码面独立 `PAYROLL_DEV_MODEL`（§8.4）；任务异步化；超时按任务类型分级 |
| 编码模型资源不足/不可用 | 高 | 启动探测；不可用时关闭 `workspace-write` 并降级只读，禁止回落到控制面 3B 出补丁 |
| 写坏仓库 | 高 | 补丁 + 确认 + 基线 commit + 一键回滚 |
| 仓库内容 Prompt 注入 | 高 | 内容标记为不可信；工具参数校验；fail_closed |
| 密钥/隐私外泄 | 高 | 路径黑名单；禁网；输出脱敏；审计告警 |
| 数据库迁移不可逆/锁表 | 高 | 空库+存量库各 dry-run；标注不可逆并提供补偿；迁移与后端补丁同批 G2；生产执行由人类操作 |
| 部署配置改坏环境 | 高 | 仅沙箱校验镜像/配置；端口与启动方式变更单列；回滚手册 + 发布由人类执行 |
| 依赖下载失败 | 中 | 预热 `.m2`/npm 缓存镜像；离线构建 |
| 前后端契约漂移 | 中 | `interfaces.md` 为唯一契约；前端不得自行假设字段；契约变更回到 G2 复审 |
| 前端缺乏自动测试 | 中 | v1 用 `vite build` + 人工验收；M2 补 vitest，M3 前评估 Playwright |
| 多人并发冲突 | 中 | worktree 隔离 + 分支命名规范 + 锁 |
| 角色串线/空回复 | 中 | 沿用现有角色绑定与空回复重试机制 |
| 代码审查流于形式或独立性不足 | 中 | 审查角色只读、与开发同模型但不同会话；强制 `文件:行号` 证据 + 固定检查清单（契约/正确性/安全/测试覆盖）；G2 由人类抽样复核，审查报告与补丁 diff 一并展示 |

---

## 12. 任务清单（优先级）

### P0（MVP 前置，先做）

- P0-0 九角色提示词与交接模板：产品经理（仅需求分析）、技术总监（仅架构路线，含数据与部署拓扑）、项目经理（拆分与分发）、后端开发、前端（接口契约与组件约定）、数据库（迁移与索引）、运维（部署/配置/回滚）、测试、代码审查（只读、逐文件结论）；固定各角色输入/输出字段、路径白名单与禁止项。
- P0-1 模型分层（A7，已在 §8.4 确认）：控制面沿用 `PAYROLL_MODEL` + `sharedModel()`；新增独立编码实例 `PAYROLL_DEV_MODEL`（`numCtx` 默认 32768、`numPredict` 默认 4096、单次 300 秒），启动探测失败则关闭写模式，禁止编码调用回落到控制面模型。
- P0-2 Workspace 服务：JGit worktree + harness `workspace` 绑定，记录基线 commit。
- P0-3 只读工具：启用 harness `read_file`/`grep`/`glob`/`ls` + `LocalFilesystemSpec(ROOTED)` + `PathPolicy`（产品经理不授权；前端默认限定 `frontend/src/**`、`frontend/public/**`）；同步调整 `HarnessAgentSupport.removeHarnessOnlyTools` 白名单。
- P0-4 TeamPlan 扩展 `outputs` 与 `acceptance`，打通产物落库。
- P0-5 产物中心：需求分析 / 架构路线 / 任务清单 / 四类补丁（后端/前端/数据库/运维）/ 测试报告 / 审查报告版本化。
- P0-6 护栏：路径白名单、结果截断、注入防护基线。
- P0-7 项目经理任务面板：分发（后端/前端/数据库/运维/测试/代码审查）、负责人、依赖、状态跟踪。
- P0-8 任务级审批：以「任务」为单位的 diff 批量确认（G2 一次覆盖后端+前端+数据库+运维），替代 `ActionGuard` 单轮单写提案。
- P0-9 上下文预算：提高 `numCtx`/`numPredict`，启用 workspace context、compaction 与工具结果淘汰。
- P0-10 两维权限模型（A1）：`payroll.dev-team.mode`（read-only/workspace-write）× `payroll.dev-team.approval`（batch/per-task/auto），启动校验非法组合（auto 仅限只读），禁用 UNRESTRICTED 与 danger-full-access，策略变更写审计。

### P1（补丁闭环）

- P1-1 结构化编辑 + G2 批量确认应用 + diff 审计（`edit_file`/`write_file`，不再要求模型产出 unified diff）。
- P1-2 任务中心：复用 `KbTaskService` 状态机/取消/重试，新增 dev_task 表与执行器。
- P1-3 受限 runner：启用 `ShellExecuteTool` + `DockerFilesystemSpec`（network、内存、CPU），分档超时与 `.m2`/npm 缓存；覆盖后端构建/单测、前端 `vite build`、迁移 dry-run（H2/MySQL）、镜像构建与 `docker compose config` 校验。
- P1-4 工具 allow/deny 策略与 fail_closed。
- P1-5 前端质量门：引入 vitest 组件测试与最小 Playwright 冒烟（对应 GAP11）。
- P1-6 编码面独立并发与预算：每编码实例并发 1、单任务 token 预算与超时档位（模型 300 秒、构建 10–20 分钟），与控制面 10 秒/150 秒预算隔离。
- P1-7 代码审查闭环：`review-report.md` 模板（逐文件 通过/建议/阻断 + `文件:行号` 证据）、固定检查清单（契约一致性/正确性/安全边界/测试覆盖）、审查任务由项目经理按补丁自动生成、阻断项打回并跟踪至关闭、结论并入 G2 摘要。

### P2（交付闭环）

- P2-1 JGit 分支/commit/PR 描述 + G3 审批（合并后端/前端/数据库/运维四类补丁）。
- P2-2 tree-sitter 多语言代码索引与符号检索。
- P2-3 质量评分、回归集与团队效果报表。

---

## 13. 验收指标

| 指标 | 目标 |
| --- | --- |
| M0 文档产出可评审率 | ≥ 80%（人工评审一次通过率；打回率 ≤ 20%） |
| 后端/前端 diff 可应用率（M1） | ≥ 80% |
| 任务分发覆盖率（唯一负责人 + 可测验收） | 100%（执行角色口径：后端/前端/数据库/运维/测试/代码审查） |
| 代码审查覆盖率（四类补丁逐文件结论） | 100%（每份 diff 均有审查结论） |
| G2 前审查阻断项关闭率 | 100%（存在未关闭阻断项不得进入 G2） |
| 前后端契约一致率（字段/路径对 `interfaces.md`） | 100% |
| 迁移脚本 dry-run 通过率 | 100%（空库 + 存量库，H2/MySQL 双库方言） |
| 部署配置校验通过率 | 100%（镜像构建 + `docker compose config` + 健康检查） |
| 交付回滚覆盖 | 100%（每次交付附可执行回滚步骤与验证点） |
| 产物中出现明文凭据 | 0 |
| 前端构建通过率 | ≥ 95% |
| 越权写入/路径逃逸 | 0 |
| danger-full-access / `UNRESTRICTED` 可达性 | 0（启动即拒绝） |
| 护栏触发后的自动重试 | 0（一律人工发起并计次） |
| 编码阶段调用控制面模型 | 0 次（补丁生成只能走 `PAYROLL_DEV_MODEL`） |
| 编码模型有效上下文 | ≥ 32768（低于则关闭 `workspace-write`） |
| 编码模型可用性 | 未配置/探测失败时写模式关闭，任务降级为只读，不产出补丁 |
| 任务可取消/恢复 | 100% 可取消，恢复成功率 ≥ 95% |
| 单任务人工确认次数 | ≤ 3（G1 需求、G2 计划+补丁批量确认、G3 交付；后端/前端/数据库/运维四类补丁在同一个 G2 内确认，代码审查不单独设门、不额外计次） |
| 模型单次调用时延（编码面） | ≤ 300 秒 |
| 单次构建/测试时延 | ≤ 20 分钟 |
| 端到端时延（M2） | 单任务 ≤ 30 分钟（异步，不阻塞聊天） |

---

## 14. 现框架复核（2026-11-17）

### 14.1 能力分档

| 档位 | 内容 | 证据 |
| --- | --- | --- |
| 直接可用 | 流程 DAG 并行/条件、写确认状态机、追溯、RBAC/审计、KB 检索、Nacos 技能、JGit 依赖、Vue 前端 | 第 5 节；`AgentFlowExecutor.java:146-205`；`ActionProposal.java` |
| 启用 + 治理 | 文件读写/编辑/检索、Shell、Docker 沙箱、计划模式、工作区上下文、压缩/结果淘汰、子智能体 | harness 2.0.1 的 `FilesystemTool`、`ShellExecuteTool`、`DockerFilesystemSpec`、`LocalFilesystemSpec`、`PlanModeTools`、`WorkspaceContextMiddleware`；当前被 `HarnessAgentSupport.java:54-66` 关闭 |
| 复用 + 适配 | 异步任务中心（复用 KB 任务队列）、产物库（复用附件/技能文件存储）、审批（从单写提案改为任务级 diff） | `KbTaskService.java`；`ActionGuard.java:124-130` |
| 真正新建 | JGit 分支/PR 编排、树形符号索引、前端测试基线、任务级审批 UI、dev_task 模型 | 见第 12 节 P1/P2 |

### 14.2 关键阻塞点（按严重度）

1. **模型与上下文**：`sharedModel` 单例，`numCtx=8192`、`numPredict=1024`（`PayrollAgentFactory.java:56-57,936-947`）。读 3–5 个源文件即接近上限，补丁输出会被截断。这是 M1 的第一阻塞项；A7 已定独立 `PAYROLL_DEV_MODEL`（≥32k，§8.4）。
2. **审批粒度**：`ActionGuard` 在本轮生成首个提案后拒绝同轮后续写调用（`ActionGuard.java:124-130`）。编码任务需要几十次编辑，不能逐调用确认；必须以任务为单位批量审批。
3. **任务运行面**：阶段硬超时 150 秒（`PayrollChatService.java:80`），计划调用 10 秒（`TeamLlmClient.java:21`），且 DAG 没有节点级超时/取消。M2 必须走异步任务中心，不复用聊天轮次。
4. **团队模型是薪酬域**：`TeamRole` 固定为 CALCULATOR/VALIDATOR/…（`TeamRole.java:4-10`），`TeamPlan` 无 `outputs`/`acceptance` 字段（`TeamPlan.java:62-93`）。dev-team 需要独立的角色枚举/模板或把角色配置化，不能直接塞进现有枚举。
5. **工具白名单过滤**：`HarnessAgentSupport.removeHarnessOnlyTools` 会移除未在 `expectedTools` 中的工具；启用文件/Shell 工具时必须同步白名单，否则“开了也不生效”。
6. **本地 Shell 非沙箱**：`LocalFilesystemSpec` 的 `executeTimeoutSeconds`/`maxOutputBytes` 只限制时长与输出，不限制命令能力；编码任务必须走 `DockerFilesystemSpec`，本地模式仅用于只读分析。

### 14.3 对 v0.3 结论的修正

- 原“需要从零新增 workspace/文件工具/终端”的判断不成立：这些是 harness 内置能力，工作重心从“开发”转为“启用、白名单、路径策略、容器化与审批”。
- 原「五角色 + 五道人工门」在当前小模型下过重：文档阶段可行，编码阶段应压缩门禁并任务化；已在 v0.5 按 A2 落为三道门。
- 原可行性评级整体上调一档，但“端到端交互式编码”在本机模型下仍为低；若编码模型达到 §8.4 门槛（≥32k 上下文且吞吐可用）则升至中高。

---

## 15. 与 Codex / Qoder 的对照

### 15.1 设计维度对照

| 维度 | Codex | Qoder | 本平台现状 | 结论 |
| --- | --- | --- | --- | --- |
| 权限模型 | 沙箱模式（read-only / workspace-write / danger-full-access）× 审批策略，两者正交；默认 workspace-write，非版本库时退化为 read-only | Spec 审核后执行，改动以 diff 呈现 | `ToolSafety` + 单次写提案 | 改为「沙箱模式 × 审批策略」，不提供 danger-full-access |
| 编辑表示 | `apply_patch` 结构化补丁 | 多文件编辑 + diff 审核 | harness `edit_file`（old/new） | 用结构化编辑在沙箱内应用，任务结束统一出 `git diff` |
| 仓库知识 | `AGENTS.md` 项目约定 + 检索 | Repo Wiki 多智能体分阶段生成工程知识 + 索引 | 仅 KB 检索；harness `WorkspaceIndex` 只是路径索引 | v1 注入 AGENTS/README + `grep`；v2 再做符号索引/Repo Wiki |
| 长任务 | 本地长会话 + 云端隔离任务 | Quest 模式：需求 → Spec → 自主执行 | 聊天轮次 150 秒 | 建独立任务中心，Spec 作为执行契约 |
| 验证 | 命令执行 + 测试，沙箱内完成 | 构建/测试/预览 | 现有只读 10 秒沙箱 | 以「构建 + 测试通过」作为完成定义 |
| 数据迁移 | 无专职角色，迁移由开发手写并在部署时执行 | 无专职角色 | DBA 角色 + MySQL Flyway / H2 内联 DDL + 空库与存量库 dry-run | 迁移与后端补丁同批 G2；生产执行留人类 |
| 部署交付 | 沙箱内构建，发布由用户在本机执行 | 构建产物 + 预览 | 运维角色产出部署补丁与校验结果，人类在 G3 后发布 | 不自动发布；回滚手册随交付 |
| 代码审查 | 由用户或 CI 承担，无专职角色 | 内置代码评审，无独立只读审查角色 | 新增只读审查角色，产出逐文件结论并并入 G2 | 覆盖 Codex/Qoder 缺失环节；不设独立门，避免门禁膨胀 |
| 安全边界 | 进程外沙箱 + 路径/网络限制；2026 年出现过 sandbox escape，官方强调护栏不能只放在 agent 内 | 工作区 + 审核 | `ToolSafety` 在进程内 | 路径策略 + 容器 + 禁网 + fail_closed，护栏放工具层与进程外 |
| 交互 | 计划 → 执行 → diff 审核 → 继续 | Spec/计划 → 执行 → 变更审核 | ActionProposal 卡片 | 扩展为「任务级 diff 审核」卡片 |

### 15.2 可借鉴与不照搬

- **借鉴**：两维权限模型；任务级审批；结构化编辑而非让模型写 diff；`AGENTS.md`/项目规则注入；构建测试作为完成定义；异步任务中心；Repo Wiki 式的分阶段仓库知识生成。
- **不照搬**：danger-full-access 模式；自动 commit/push；把护栏写进提示词；用同一个模型承担全部角色与全部阶段。

---

## 16. 需要调整的清单

### 16.1 调整项（按优先级）

| 编号 | 调整 | 对应原设计 | 优先级 |
| --- | --- | --- | --- |
| A1 | 权限模型改为「沙箱模式（read-only / workspace-write）× 审批策略（batch / per-task / auto 仅限只读）」，无 danger-full-access；详见 §4.1 | §4 三道人工门、C9 | **已确认（2026-11-17）** |
| A2 | 门禁压缩为 3 道：G1 需求确认、G2 计划+补丁批量确认、G3 交付审批；原 G2/G3 降为可评审产物，不阻塞 | §4 G1–G3 | **已确认（2026-11-17）** |
| A3 | 补丁表示改为结构化编辑（`edit_file` old/new、`write_file`），任务结束时生成 `git diff` 供审 | C4、§12 P1-1 | P0 |
| A4 | 任务中心复用 `KbTaskService` 模式，新增 dev_task 表（状态、取消、重试、恢复、产物引用） | C8、P1-2 | P0 |
| A5 | 工作区用 JGit worktree + 路径白名单 + 仓库级互斥锁，禁止多任务写同一目录 | C1、D2 | P0 |
| A6 | 编码/构建一律走 `DockerFilesystemSpec`（`network=none`、内存/CPU 限额、附加 run 参数），本地 shell 只用于只读分析 | C5/C6、GAP2 | P0 |
| A7 | 模型分层：规划/路由用 3B，编码用独立 `PAYROLL_DEV_MODEL`；`numCtx ≥ 32k`、`numPredict ≥ 4096`；详见 §8.4 | D1、GAP13 | **已确认（2026-11-17）** |
| A8 | 启用 workspace context、compaction、工具结果淘汰，并注入 `AGENTS.md`/项目规则 | GAP4、§12 P0-9 | P0 |
| A9 | 检索 v1 用 `grep`/`glob` + 关键文件摘要；v2 再接 tree-sitter 符号索引 | C3、P2-2 | P1 |
| A10 | 完成定义改为「构建 + 目标测试通过」；前端补 vitest/Playwright 后再谈交互验收 | §13、GAP11 | P1 |
| A11 | 安全基线：工具层 allowlist、路径黑名单（`.git/`、`data/`、密钥）、禁网、注入防护、secret 扫描、fail_closed | C9、§11 | P0 |
| A12 | 九角色 = 提示词 + 工具授权 + 产物契约，不是九个常驻服务；后端/前端共享编码运行时与模型实例（并发 1 时表现为串行），DBA/QA/运维校验/代码审查为独立节点；审查节点只读、不参与写入 | §3、§4 | P0（认知调整） |
| A13 | 追溯增加 task_id、diff hash、命令与退出码、token 用量 | D8 | P1 |

### 16.2 修订后的落地顺序

1. **S0（1 周）**：`read-only + auto`，只读 worktree + `read_file`/`grep`/`glob`，验证定位能力与路径策略。
2. **M0（1–2 周）**：九角色文档流（不写代码），沿用 `read-only + auto`，复用现有 KB/DAG/追溯。
3. **M1（2–3 周）**：切 `workspace-write + batch`；配置独立 `PAYROLL_DEV_MODEL`（≥32k 上下文）替换 3B 编码；`edit_file` 写入 worktree；G2 批量审批；`git diff` 审核。
4. **M2（3–4 周）**：dev_task 异步任务中心（复用 KB 队列）；Docker 构建/测试 runner（含迁移 dry-run、镜像构建）；超时与缓存；契约变更类任务启用 `per-task`。
5. **M3（4–6 周）**：JGit 分支/commit/PR 描述 + 交付审批；回滚手册；前端测试补齐。

### 16.3 验收口径调整

完整指标以第 13 节为准，本节只记录相对 v0.5 的口径变化：

- 人工门由「≤5」改为「≤3」（G1 需求、G2 计划+补丁、G3 交付）。
- 新增编码面时延与预算（模型 300 秒、构建 20 分钟、端到端 30 分钟）、编码模型上下文与可用性、迁移 dry-run、运维配置校验、回滚覆盖、越权拦截数。
- 补丁可应用率拆分：后端/前端 diff ≥80%，迁移 dry-run 与运维配置校验各 100%。

### 16.4 参考

- Codex Sandbox / Approvals：<https://learn.chatgpt.com/docs/sandboxing>
- Codex 沙箱逃逸与护栏边界：<https://devops.com/codex-sandbox-escapes-show-why-agent-guardrails-cant-live-inside-the-agent>
- Qoder Repo Wiki：<http://docs.qoder.com/qoder/repo-wiki>
- Qoder Quest（Spec 驱动）：<https://qoder.com/en/blog/qoder-case-creditstips>

---

## 17. 附录：本次实测与证据

- 模型：`qwen3.8:27b`（27.3B，Q4_K_M，17GB，90% CPU / 10% GPU）。
- 冷启动加载 29.5s；首请求 48.4s；稳定 1.49 token/s。
- `route-preview`：10.9s 返回 `failure_reason=轻量模型不可用或超时`。
- 聊天流：215s 仅 thinking token，无回答，已取消。
- 测试后运行期已切回 `qwen2.5:3b-instruct`：预热后 `route-preview` 6.9s，端到端回答约 10.9s（27B 数据保留作为编码模型选型依据）。
- 前端现状：`frontend/package.json` 为 Vue 3 + Vite 6 + Element Plus，devDependencies 无测试框架，`npm run build` 即 `vite build`。
- 关键代码位置：
  - `payroll-agent/src/main/java/io/agentscope/examples/payroll/agent/HarnessAgentSupport.java:54-66`
  - `payroll-agent/src/main/java/io/agentscope/examples/payroll/agent/PayrollAgentFactory.java:56-57`（`numCtx`/`numPredict` 默认）、`:62-63`（`sharedModel` 单例字段）、`:936-947`（`sharedModel()`）、`:1002-1011`（模型环境变量）
  - `payroll-agent/src/main/java/io/agentscope/examples/payroll/team/TeamLlmClient.java:21`（轻量模型 10 秒超时）
  - `payroll-agent/src/main/java/io/agentscope/examples/payroll/team/TeamRole.java:4-10`（固定薪酬域角色枚举）
  - `payroll-agent/src/main/java/io/agentscope/examples/payroll/team/TeamPlan.java:62-93`（`PlanNode` 无 `outputs`/`acceptance`）
  - `payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/DockerSandboxRunner.java:145-168`
  - `payroll-protocol/src/main/java/io/agentscope/examples/payroll/sandbox/SandboxProtocol.java:22-28`
  - `payroll-agent/src/main/java/io/agentscope/examples/payroll/service/PayrollChatService.java:80`
  - `payroll-agent/src/main/java/io/agentscope/payroll/runtime/PayrollAgentConfiguration.java:176`
  - `payroll-common/src/main/java/io/agentscope/examples/payroll/agent/ToolSafety.java`
  - `payroll-common/src/main/java/io/agentscope/examples/payroll/agent/ActionGuard.java:124-130`（首个提案后拒绝同轮后续写）
  - `payroll-common/src/main/java/io/agentscope/examples/payroll/domain/ActionProposal.java`
  - `payroll-common/src/main/resources/db/migration/mysql/V*.sql`（MySQL Flyway 迁移）；`payroll-common/src/main/java/io/agentscope/examples/payroll/db/PayrollDatabase.java:31`（H2 内联 DDL）、`:783`（Flyway 位置）、`H2ToMysqlMigration.java:50`（存量迁移）
  - `docker/docker-compose.yml`、`docker/nginx.conf`（部署与网关基线）
  - `pom.xml:35`（`agentscope-harness` 2.0.1）
  - `frontend/package.json`
  - `docs/dynamic-agent-team-spec.md`

> 数据库方言口径：MySQL 走 Flyway 版本化脚本，H2 走内联幂等 DDL，因此 DBA 的「双库 dry-run」是两条路径分别验证，而不是同一份 Flyway 脚本在两端执行。
