# 三端拆分与智能体路由规格

> 状态：实施中（2026-11-13）。目标是把单 Maven 模块拆成 **frontend（Vue3）/ backend（Web 应用）/ agent（智能体运行期应用）** 三部分，
> 并在对话入口引入一个由轻量模型驱动的 **智能体路由**，决定本轮交给单智能体、智能体团队还是默认流水线。

## 1. 已确认决策

| 维度 | 决策 |
| --- | --- |
| 前端 | 用 Vue3 + Vite 重写，独立工程 `frontend/`，Nginx 托管静态产物 |
| 后端/智能体 | 两个独立 Spring Boot 应用、两个镜像；后端不再内嵌本地执行 |
| 运行期不可用 | 返回明确错误，不回退到后端本地执行 |
| 路由策略 | 纯轻量模型路由（不做规则优先）；模型失败/非法输出时回退到既有确定性链路 |
| 兼容 | 保持既有 REST 路径 `/api/**`，前端只换实现不换契约 |

## 2. 目标拓扑

```
浏览器 ──> frontend (Nginx, :18088)
              │  /api/** 反向代理
              ▼
        backend × N (Spring Boot, :8080)
              │  /internal/turns (HMAC)
              ▼
        agent × N (Spring Boot, :8100)
              │
        MySQL / Redis / Ollama / Elasticsearch / Nacos
```

- backend：登录鉴权、RBAC、会话/消息/反馈/审批/审计/知识库/管理接口、SSE 续流代理。
- agent：模型与工具、三段流水线、流程编排、动态团队、语义缓存、轮次执行。
- frontend：只依赖 `/api/**`，不感知节点数量。

## 3. 模块与依赖方向

```
payroll-common   ← payroll-backend
      ↑          ← payroll-agent
      └──────────┘
```

- `payroll-common`：领域模型、持久化、向量/知识库、共享服务、轮次生命周期、公共 Web 支撑。
- `payroll-agent`：智能体工厂、工具、流程执行、动态团队、`PayrollChatService`、内部轮次接口、智能体路由。
- `payroll-backend`：控制器、鉴权拦截器、SSE 代理、管理台接口。
- 依赖只允许 `backend → common`、`agent → common`，禁止 backend 依赖 agent。

### 3.1 包归位

| 包 | common | agent | backend |
| --- | --- | --- | --- |
| domain / support / model / repository / db / memory / skill | 全部 | | |
| kb | 服务、存储、配置 | | 8 个 Controller |
| service | 基础设施与共享服务（会话管理、轮次、审计、审批、核算、缓存等） | PayrollChatService、AgentFlowExecutor、GraphPayrollOrchestrator | |
| team | TeamSettings、TeamMode、TeamAdminService、ShadowReportService | 组队/计划/执行/影子/ACTIVE | |
| web | ChatSse、NodeRoleFilter、ApiResponse、ApiExceptionHandler、RequirePermission | InternalTurnController | 其余控制器与鉴权 |
| agent | | 全部 | |
| router | | 新增 | |

### 3.2 必须抽取的接口（消除环依赖）

| 接口（common） | 现实现（agent） | 会话层用途 |
| --- | --- | --- |
| `ShadowEvaluator` | `ShadowTeamService` | 回答落库后触发影子团队 |
| `ActiveTeamCoordinator` | `ActiveTeamService` | 团队成功/回退后的运行结算 |
| `PayrollChatFactory`（已有） | 配置类里的 lambda | 创建会话级对话实例；backend 为 null |
| `PayrollChat`（已有） | `PayrollChatService` | 对话抽象 |

`ChatSessionManager` 只依赖上述接口；`chatFactory == null` 表示本节点不执行模型（backend 模式），
`beginTurn` 不再创建对话实例与静态运行记录，由 agent 的 `acceptRemoteTurn` 接管后创建。

## 4. 运行期契约

- 轮次仍由 backend 创建（`chat_turns` + 用户消息 + 租约），随后通过 `POST /internal/turns` 提交给 agent。
- agent 接管后执行并落助手消息；backend 通过 `GET /api/chat/sessions/{id}/turns/{turnId}/events?after_seq=` 续流。
- backend 在续流结束时只释放本机 busy/会话锁，不写业务数据。
- `/internal/**` 只在 agent 暴露，继续使用 HMAC-SHA256；后端配置 `PAYROLL_AGENT_RUNTIME_URLS` 与 `PAYROLL_AGENT_RUNTIME_TOKEN`。
- 未配置运行期或提交失败时，backend 返回 `PayrollException`（HTTP 502/503），**不本地执行**。
- 同步接口 `POST /messages` 在拆分部署下改为“提交 + 等待轮次终态 + 读取最后一条助手消息”；超时返回 504。

## 5. 智能体路由

### 5.1 候选

- 单智能体：Nacos 中 `status=ONLINE` 且非内置流水线的智能体，按 `id | 名称 | 职责` 提供给模型。
- 团队：`TeamSettings.mode=ACTIVE` 且白名单允许当前用户/会话时可选。
- 流水线：始终作为兜底候选。
- 默认流程已配置时保持 FLOW，不参与路由（管理员显式配置优先）。

### 5.2 模型输出契约

```json
{"target":"agent|team|pipeline","agent_id":"calc-001","reason":"一句话理由","confidence":0.86}
```

- `target=agent` 时 `agent_id` 必须命中候选；`target=team` 时团队必须可用；其余情况判定为非法。
- 调用复用 `TeamLlmClient`（无工具、单轮、超时由 `payroll.router.timeout-ms` 控制）；失败、超时、非法 JSON、未知 ID、置信度低于 `min-confidence` 均回退。
- 回退顺序：已配置的全局智能体 → 默认流水线/图编排。回退时在 `ChatRouting.reason` 中注明原因，不改变当前链路。

### 5.3 可观测

- 路由结果写入 `ChatRouting`：`path` 为 `AGENT/TEAM_ACTIVE/PIPELINE`，
  `definitionSignature` 增加 `router:<target>:<id>`，`reason` 附“路由决定（置信度 x.xx）”。
- 路由失败回退时写 `fallbackReason`，前端路由卡片展示。
- 日志前缀 `[智能体路由]`：候选数量、决策、耗时、失败原因。
- 提供 `POST /internal/route/preview`（agent）+ `POST /api/agents/route-preview`（backend 代理）便于管理台调试；预览只跑路由，不执行回答，返回 `target/agent_id/reason/confidence/failure_reason/candidates/team_available/elapsed_ms`。

### 5.4 配置

```yaml
payroll:
  router:
    enabled: ${PAYROLL_ROUTER_ENABLED:true}
    timeout-ms: ${PAYROLL_ROUTER_TIMEOUT_MS:10000}
    min-confidence: ${PAYROLL_ROUTER_MIN_CONFIDENCE:0.55}
```

## 6. 前端页面映射（Vue3）

| 路由 | 页面 | 主要接口 |
| --- | --- | --- |
| `/login` | 登录 | `POST /api/auth/login` |
| `/chat` | 对话（会话侧栏、微信风格消息、SSE、路由/依据/团队面板、反馈、取消） | `/api/chat/sessions/**` |
| `/agents` | 智能体管理 | `/api/admin/agents` |
| `/flows` | 流程管理 | `/api/admin/flows` |
| `/teams` | 团队与影子报告 | `/api/admin/teams/**` |
| `/skills` | 技能管理 | `/api/admin/skills` |
| `/kb` | 知识库 | `/api/kb/**` |
| `/admin` | 用户/部门/审批/审计 | `/api/admin/**` |

- 统一 `api` 客户端处理 401 跳转、错误提示、超时。
- SSE 使用 `EventSource` + `after_seq` 续流；取消调用 `POST .../turns/{turnId}/cancel`。
- 生产构建输出 `dist/`，由 Nginx 托管并反代 `/api`。

## 7. 构建与部署

- 构建：`mvn -q -DskipTests package` 产出 `payroll-backend/target/payroll-backend.jar`、`payroll-agent/target/payroll-agent.jar`。
- 前端：`cd frontend && npm ci && npm run build` 产出 `frontend/dist`。
- 镜像：
  - `payroll-frontend`：`nginx:alpine` + `dist/` + `nginx.conf`
  - `payroll-backend`：JRE 17 + backend jar
  - `payroll-agent`：JRE 17 + agent jar
- compose 保留 MySQL/Redis/Ollama/Nginx 入口，backend×2、agent×2。

## 8. 迁移阶段

1. 建父 pom 与三个子模块，按包迁移，接口抽取，`mvn package` 通过。
2. 拆配置与两个启动类；backend 去掉本地回退；既有测试迁移并全绿。
3. 实现路由（模型调用、校验、回退、快照、预览接口）并补单测。
4. Vue3 前端：登录 + 对话 + 管理页骨架，SSE/续流/取消/路由面板对齐。
5. 三镜像 compose 部署，跨节点续流、取消、团队路由实测；更新 README 与部署文档。

## 9. 风险与约束

- 252 个类的物理迁移以“保持包名”为原则，避免大规模改 import。
- `service↔team`、`service↔agent` 两处环必须通过接口或类归位消除，禁止 backend 传递依赖 agent。
- 前端重写期间旧静态页保留在 `frontend/reference/`，不参与构建，验收后删除。
- 未获授权不提交、不推送、不合并。
