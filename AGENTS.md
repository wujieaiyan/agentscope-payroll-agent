# AGENTS.md

本文件是本仓库对所有编码智能体（Qoder / Claude Code / Codex 等）的常驻约束。与用户当次指令冲突时以用户指令为准；本文件未覆盖处，以现有代码与 README 为准。

## 项目概览

AgentScope Java 工资管理台：多用户 RBAC、对话式薪酬问答、知识库检索、Skill 执行沙箱、审批/审计/追溯、Token 用量看板。

| 模块 | 职责 |
| --- | --- |
| `payroll-protocol` | 跨模块契约：DTO、`JsonSupport`、`InternalAuth` 内部签名 |
| `payroll-common` | 配置、持久化（H2/MySQL）、向量检索、服务层 |
| `payroll-agent` | 智能体运行期：模型调用、中间件、内部轮次接口（默认 8100） |
| `payroll-backend` | Web 层：鉴权、会话、SSE 转发、管理接口（默认 8080） |
| `payroll-sandbox` | 技能脚本沙箱（默认 8200） |
| `payroll-tests` | 集成/端到端 JUnit 测试（依赖真实 MySQL/Redis 的部分见下） |
| `frontend` | Vue 3 + Element Plus 控制台，Vite 构建 |

## 构建与测试

环境固定：JDK 17（`D:\tools\jdk\jdk-17.0.12`）、Maven 3.6.3（`D:\tools\apache-maven-3.6.3`），仓库无 `mvnw`，本机依赖已缓存，统一 **离线** 执行。

```bash
# 全量编译（不跑测试）
mvn -o -q -DskipTests package

# 全量测试
mvn -o test

# 定向测试：-am 会让无匹配测试的模块也进入构建，必须加 failIfNoSpecifiedTests=false
mvn -o -pl payroll-tests -am test -Dtest=LlmUsageStoreTest -Dsurefire.failIfNoSpecifiedTests=false
```

前端：

```bash
cd frontend
npm run build        # vite build，产物 dist/ 由 nginx 8880 托管
npm run test         # vitest run（单测）
npm run test:e2e     # playwright，需 PLAYWRIGHT_BROWSERS_PATH=D:/playwright-browsers
```

E2E 的 webServer 固定 `127.0.0.1:4173`；不要在 5173 上跑 E2E。

## 本地运行拓扑

| 端口 | 进程 | 说明 |
| --- | --- | --- |
| 8080 | `PayrollWebApplication`（payroll-backend） | `PAYROLL_WEB_PORT` |
| 8100 | `AgentRuntimeApplication`（payroll-agent） | `PAYROLL_AGENT_PORT`，Web 通过 `PAYROLL_AGENT_RUNTIME_URLS` 调用 |
| 8200 | `payroll-sandbox` | `PAYROLL_SANDBOX_PORT` |
| 8880 | 本机 nginx 1.21.6 | 托管 `frontend/dist`，`/api/` 反代 8080 |
| 5173 | Vite dev | 仅开发调试 |

Spring profile：默认 H2 单机；`dept` = `mysql,redis`（`application-common.yml` 的 group），IDEA 运行配置使用 `SPRING_PROFILES_ACTIVE=dept`。

**运行期必须用 Run 启动，不要用 Debug。** Debug（jdwp）会让首次内部轮次提交挂起，Web 侧 15s 超时后报「智能体运行期不可达」，而 `/actuator/health` 仍为 UP，极易误判为代码缺陷。已确认这是环境问题。

内部接口签名：`HMAC-SHA256(token, "timestamp\nMETHOD\npath\nbody")`，头 `X-Payroll-Timestamp` / `X-Payroll-Internal`，时钟偏差上限 120s。排查 401/超时先核对两端 `PAYROLL_AGENT_RUNTIME_TOKEN` 一致、系统时间正常。

## 红线（任何情况下不得违反）

- 不得复述、打印、写入任何口令/密钥：GitHub 密码、`PAYROLL_AGENT_RUNTIME_TOKEN`、`PAYROLL_SANDBOX_TOKEN`、MySQL root 密码、SSH 私钥。凭据只从环境变量或 IDEA 运行配置读取。
- Git remote 只能是 `git@github.com:wujieaiyan/agentscope-payroll-agent.git`（SSH）。GitHub 直连需 `HTTPS_PROXY=http://127.0.0.1:8980` 时仅用于非 Git 的 HTTPS 请求。
- `commit` / `push` / `merge` / 建 PR 必须先获得用户明确授权；只提交当次任务范围内的文件，共享文件按 hunk 暂存。
- 本地 `data/`（H2 库、上传文件）已 gitignore，必须保留。删除 H2 库或上传文件前必须确认。
- 异常分支按异常类型判断，禁止用错误消息文本匹配做控制流。
- 不得使用破坏性 Git 操作（`push --force`、`reset --hard`、`clean -f`）绕过问题；先定位根因。
- 禁止跳过 hooks（`--no-verify`）。

## 工程约定

- 提交信息中文，前缀 `feat:` / `fix:` / `test:` / `docs:` / `refactor:`；一句话说清「为什么」。
- 分支从 `main` 切出，PR 一律以 `main` 为 base；push 前核对 `git log origin/main..HEAD` 实际范围。
- 新增数据库结构：只改 `payroll-common/src/main/resources/db/migration/common/V{n}__*.sql` 这一份 Flyway 迁移，H2（`MODE=MySQL`）与 MySQL 共用；不得再写内联 H2 DDL。SQL 需两种方言都可执行。
- 新增管理接口：在权限表登记权限码（如 `usage:read`），controller 加 `@PreAuthorize`，并补权限测试。
- 模型调用链路上的横切能力（记录、限流、缓存）走中间件（`MiddlewareBase`），不要散落在业务代码。
- 前端新增页面：路由 + `AppLayout` 导航项 + 单测，图表优先无第三方依赖的 SVG 组件。
- 注释只写「为什么」，不写「做了什么」；中文注释与现有代码风格一致。

## 完成定义（DoD）

1. `mvn -o -q -DskipTests package` 通过。
2. 相关单测通过；改动涉及共享逻辑时跑全量 `mvn -o test`。
3. 前端改动：`npm run build` 通过，`npm run test` 通过，并在 nginx 8880 实际页面走通主路径。
4. 涉及运行期/内部接口：在 Run 模式下真实发一轮对话，确认事件与落库（如 `llm_usage`）有数据。
5. 汇报只写已验证结果；未验证的部分明确说「未验证，原因是 X」。

## 评审输出

发现问题按 P0 / P1 / P2 分级，每条给出 `文件:行号` 证据与修复建议；先给结论再给细节。详细规则见 `.qoder/skills/review-p0p1p2/SKILL.md`。
