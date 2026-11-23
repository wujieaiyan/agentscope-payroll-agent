# 前端信息架构：双工作台归属矩阵（M8）

> 对应 `docs/architecture-remediation-spec.md` §13（M8）。D1 已拍板产品主线为「Agent 平台 + 薪酬运营」，本页是路由、菜单、默认落地页与权限展示的唯一归属依据。
>
> 约束：**不重命名现有权限码**；判定逻辑仍以 `Permissions` 与后端 `@RequirePermission` 为准，前端只负责可见性与归属。

## 1. 工作台划分

| 工作台 | 定位 | 页面 |
| --- | --- | --- |
| 平台工作台 `platform` | Agent 平台能力 | 智能体、流程编排、技能、团队运行、知识库 |
| 薪酬工作台 `payroll` | 薪酬运营 | 员工、绩效考核、考勤、工资批次、我的待办、政策 |
| 平台治理 `governance` | 平台与系统治理 | 模型配置、用户与角色、审计日志、用量看板、我的工作偏好 |
| 全局入口 | 对话 | 对话（`/chat`，不归属任何工作台，会话列表不拆分） |

侧栏顶部为工作区切换器；切换工作台时跳到该工作台第一个可见菜单项。

## 2. 归属矩阵

角色列为内置角色（`Permissions.builtinRolePermissions()`）与「纯员工」（只有 `chat:use` 的自定义角色）。

| 页面 | 路由 | 工作台 | 权限码 | ADMIN | HR | VIEWER | 纯员工 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 对话 | `/chat` | 全局 | `chat:use` | ✓ | ✓ | ✓ | ✓ |
| 智能体 | `/platform/agents` | platform | `agent:manage` | ✓ | — | — | — |
| 流程编排 | `/platform/flows` | platform | `flow:manage` | ✓ | — | — | — |
| 技能 | `/platform/skills` | platform | `skill:manage` | ✓ | — | — | — |
| 团队运行 | `/platform/team` | platform | 无（沿用旧行为） | ✓ | ✓ | ✓ | ✓ |
| 知识库 | `/platform/kb` | platform | `kb:read` | ✓ | ✓ | ✓ | — |
| 员工 | `/payroll/employees` | payroll | `employee:read` | ✓ | ✓ | ✓ | — |
| 绩效考核 | `/payroll/reviews` | payroll | `review:read` | ✓ | ✓ | ✓ | — |
| 考勤 | `/payroll/attendance` | payroll | `attendance:read` | ✓ | ✓ | ✓ | — |
| 工资批次 | `/payroll/runs` | payroll | `payroll:read` | ✓ | ✓ | ✓ | — |
| 我的待办 | `/payroll/tasks` | payroll | `payroll:approve` | ✓ | ✓ | — | — |
| 政策 | `/payroll/policies` | payroll | `policy:read` | ✓ | ✓ | ✓ | — |
| 模型配置 | `/platform/models` | governance | `agent:manage` | ✓ | — | — | — |
| 用户与角色 | `/platform/users` | governance | `user:manage` | ✓ | — | — | — |
| 审计日志 | `/platform/audit` | governance | `audit:read` | ✓ | — | — | — |
| 用量看板 | `/platform/usage` | governance | `usage:read` | ✓ | — | — | — |
| 我的工作偏好 | `/platform/profile` | governance | `profile:read` + 开关 | ✓* | ✓* | ✓* | — |

\* 「我的工作偏好」还要求 `payroll.memory.profile.enabled=true`（`/api/auth/me` 的 `profile_enabled`），默认关闭。

说明：

- 「团队运行」历史上对所有登录用户可见且页面无独立权限码，M8 不收紧，避免行为回退；后续若纳入 `agent:manage` 需单独评审。
- 「用户与角色」原侧栏未加权限，但页面接口本就要求 `user:manage`；M8 将菜单可见性对齐到同一权限码，HR/VIEWER 不再看到治理项。
- 纯员工看不到薪酬与治理菜单；其可用工作台只剩平台组的「团队运行」，对话始终保持全局入口。

## 3. 旧路径重定向

旧路径保留至少一个版本（书签、断点恢复、邮件链接不失效），实现见 `frontend/src/router/index.js` 与 `frontend/src/router/workspaces.js` 的 `LEGACY_REDIRECTS`。

| 旧路径 | 新路径 |
| --- | --- |
| `/agents`、`/flows`、`/skills`、`/team`、`/kb` | `/platform/agents`、`/platform/flows`、`/platform/skills`、`/platform/team`、`/platform/kb` |
| `/kb/create`、`/kb/:id` | `/platform/kb/create`、`/platform/kb/:id` |
| `/employees`、`/reviews`、`/attendance`、`/runs`、`/tasks`、`/policies` | `/payroll/*` 同名 |
| `/models`、`/users`、`/audit`、`/usage`、`/profile` | `/platform/*` 同名 |
| 其他未知路径 | `/chat` |

## 4. 菜单与落地规则

- 菜单由路由 `meta.menu` 生成（`menuFromRoutes`），手写分组数组已删除；`meta.menu` 含 `label/icon/order/workspace/permission/feature`。
- 过滤顺序：无 `permission` 或当前用户持有 → 保留；`feature=profile` 且开关关闭 → 隐藏；按 `order` 升序。
- 默认落地页（`landingPath`）按权限码推导，不写死角色名，兼容自定义角色：
  1. `agent:manage` → `/platform/agents`
  2. `employee:read` → `/payroll/employees`
  3. `payroll:read` → `/payroll/runs`
  4. `kb:read` → `/platform/kb`
  5. 其余 → `/chat`
- 登录成功与访问 `/` 时都跳默认落地页；`/login` 在已登录时同样跳落地页。

## 5. 权限页与审计的工作台维度

- 用户与角色页：权限 chip 按 `PERMISSION_GROUPS`（平台能力 / 薪酬业务 / 平台治理 / 通用）分组展示，未归类的权限码落到「其他」；**判定逻辑不变**。
- 审计日志页：新增「工作台」只读列，由 `workspaceOfAction(action, resource_type)` 按动作首 token 前缀归类（`agent/flow/skill/kb/team/model/usage` → 平台；`employee/review/attendance/policy/payroll/batch/approval/import` → 薪酬；`user/role/department/audit/settings/login/logout/password` → 治理；其余 → 通用），无法识别时再看资源类型。
- 该列仅用于浏览定位，不做服务端过滤：HTTP 兜底审计的 action 形如 `post /api/...`，归「通用」，客户端过滤会与分页计数不一致。

## 6. 验收与回归

- 前端单测 `frontend/tests/unit/navigation.test.js`：旧路径重定向、三类角色落地页、按权限的菜单可见性、工作偏好开关、审计归属。
- 手工冒烟：ADMIN 登录落在 `/platform/agents`，HR 落在 `/payroll/employees`，VIEWER 落在 `/payroll/employees`；旧路径无 404；普通角色无治理项；对话页行为不变。
- 回滚：菜单与路由 `meta` 可回退到旧手写数组；旧路径一直保留，回滚不影响深链。
