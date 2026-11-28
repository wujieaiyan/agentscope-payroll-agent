# payroll-backend

## 职责

控制面（默认 8080）：登录鉴权与 RBAC、会话与轮次生命周期、SSE 转发、管理台 REST、知识库读写与检索入口。**不加载模型**——所有模型执行都转发给 `payroll-agent`。

浏览器只与这个进程对话（经 nginx `/api` 反代）。

## 包地图

| 包 | 内容 | 说明 |
| --- | --- | --- |
| `web` | 20+ Controller、`AuthInterceptor`、`AuditFilter`、`WebConfig` | 所有对外 `/api/**` |
| `web` 里的对话 | `ChatSessionController`、`ChatActionController`、`ChatAttachmentController` | SSE 流与断点续流 |
| `kb` | 20+ 类：系列/目录/文档/切块/检索/成员/标签/任务/ES/嵌入 | 知识库全链路，内部接口 `KbSkillController` 挂在 `/internal/kb` |
| `chat` | `ChatAttachmentService`、`AttachmentParseQueue` | 附件落盘与解析排队 |
| `retention` | `RetentionSettings/Service/Configuration` | 每日保留任务（默认 dry-run） |
| `backend` | `PayrollBackendConfiguration` | 装配：runtime 客户端、`NodeRoleFilter`、`AuditFilter`、启动报告 |

## 请求进来之后

```
/api/**  → AuthInterceptor（未登录 401 / 缺权限 403）
         → AuditFilter（写请求落审计，含脱敏后的 body）
         → Controller → Service（在 payroll-common）→ JDBC

对话：POST /api/chat/sessions/{id}/stream → 提交 runtime 内部轮次
     → 订阅事件总线 → SSE 下发（seq/id，支持断点重放）
```

- 公开路径只有 `/api/auth/login` 与 `/api/health`（`AuthInterceptor.PUBLIC_PATHS`）。
- 权限注解是 `@RequirePermission`（不是 Spring Security 的 `@PreAuthorize`）。
- 内部接口（`/internal/**`）走 HMAC，不走会话。

## 改这里的规则

1. **新增管理接口必须四件套**：权限码（`payroll-common/support/Permissions` 登记）→ `@RequirePermission` → 审计事件 → `*WebTest` 测试（`@Tag("integration")`）。
2. Controller 只放这个模块的 `web` / `kb` 包；领域逻辑进 `payroll-common`。
3. 管理台的写接口要支持 `?revision=` 乐观并发，冲突返回 409 并回显 `currentRevision`（技能/流程/模型连接已有先例）。
4. 错误一律抛业务异常，由 `ApiExceptionHandler` 统一映射；不要在 Controller 里 `try-catch` 造响应。
5. 新增 `/internal/**` 接口要同时改 `NodeRoleFilter` 的白名单判断（见坑 1）。

## 常见改动路径

| 要做的事 | 动哪里 |
| --- | --- |
| 加一个管理页要的接口 | `web/*Controller` + 权限码 + 审计 + 测试 |
| 改 SSE 事件协议 | `ChatSessionController` + `payroll-common/web/ChatSse` + `frontend/src/views/ChatView.vue` 三处同改 |
| 加知识库能力 | `kb/` + 迁移 + 检索工具（runtime 侧 `KbTools` 走 `/internal/kb`） |
| 改保留策略 | `retention/` + `application-common.yml` 的 `payroll.jobs.*` |

## 坑

1. **`node-role=web` 会 404 掉 `/internal/kb/**`**：`NodeRoleFilter` 对 web 角色拒绝所有 `/internal/`，而 `KbSkillController` 恰好挂在该前缀下。集群（compose 里 web-1/web-2 都是 `PAYROLL_NODE_ROLE: web`）下 runtime 调 KB 工具会全链路失败。整改见 W0-3。
2. **发送工资条是 check-then-act**：`PayrollRunService.sendAll` 读状态 → 发送 → 记结果 → 改状态，三步无 CAS、无幂等键，并发或重试会给全员发两遍（当前 dry-run 掩盖了后果）。
3. 审计 IP 直接取 `X-Forwarded-For` 首段，可伪造；CSV 导出未转义 `=`/`+`/`-`/`@`，存在公式注入。
4. `WebConfig` 的拦截器只挂 `/api/**`，`/actuator/*` 不受保护，而 nginx 会把 `/actuator/` 反代出去。
5. 会话 Cookie 没有显式 `SameSite`/`Secure` 配置，写接口没有 CSRF 令牌。

## 验证

```bash
mvn -o -pl payroll-backend -am -DskipTests package
mvn -o -pl payroll-tests -am test -Dtest=PayrollAuthWebTest -Dsurefire.failIfNoSpecifiedTests=false
```

前端联动：`cd frontend && npm run build` → nginx 8880 打开页面走主路径（只跑单测不算验证）。
