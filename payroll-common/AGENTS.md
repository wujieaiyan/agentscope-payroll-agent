# payroll-common

## 职责

共享内核：领域模型 + 持久化 + 平台能力（技能/模型/用量/记忆/画像/团队）+ Web 原语。`payroll-agent`（数据面）与 `payroll-backend`（控制面）都只依赖它，**不互相依赖**。

这个模块是两个进程的共同地基，也是本仓库最大的一个模块（数百个类）。改它 = 双端回归。

## 依赖方向（ArchUnit 强制，违反测试会红）

- `domain` 不依赖 `service` / `web` / `skill` / `db`；
- `kb` 不反向依赖对话；
- 平台包（`skill` / `model` / `usage` / `memory` / `profile` / `team` / `kb` / `health` / `support`）不依赖薪酬业务类型与 `PayrollEngine` / `PayrollTools` / `PayrollAgentFactory`；
- 只有 `payroll-backend` 的 `web` / `kb` 包能放 Controller（注意：`payroll-common` 里的 `web` 包是**原语**，不是 Controller 层）。

## 包地图

| 包 | 内容 | 改这里之前要知道 |
| --- | --- | --- |
| `domain` | 记录类型、枚举、异常、`Money` | 金额只用 `BigDecimal`；舍入只走 `Money.round`（SCALE=2, HALF_UP） |
| `repository` | 存储接口 + JSON 实现（演示/CLI） | `JsonPayrollRepository` 从 classpath 读 `src/main/resources/data/*.json` |
| `db` | JDBC 存储、`PayrollDatabase`、Flyway 引导、`H2ToMysqlMigration` | 自管连接，**无 Spring 事务**；H2 单写者无池 |
| `service` | 核算、批次、审批、审计、会话、配额、导出/导入 | `PayrollEngine` 是金额唯一出口；`ChatSessionManager` 管轮次生命周期 |
| `web` | `ApiExceptionHandler`、`ChatSse`、`RequirePermission`、`NodeRoleFilter`、`ApiResponse` | 原语放这里，Controller 放 backend |
| `agent` | 工具集：`PayrollTools`、`KbTools`、`KbQueryTools`、`SkillReadTools`、`SkillRunTools`、`QuestionTools`、`ConfirmationTools`、`McpToolRegistrar`、`ActionGuard`、`ToolTraceRecorder`、`ToolSafety` | 新增工具必须标 `ToolSafety`，写操作必须过 `ActionGuard` |
| `skill` | 技能仓库双源（Nacos / MySQL+blob）、导入、版本回滚、质量门槛 | 两条读取路径，改动要两边都过 |
| `model` | 模型连接、加密凭据、`ModelOutboundPolicy`（SSRF 出网策略）、连接测试 | 出网策略管的是"能不能连"，**不是**"能不能发 PII" |
| `memory` | 向量记忆、语义缓存、嵌入熔断 | 索引重建先 `clearAll()`，非原子 |
| `usage` | `llm_usage` 记录与聚合 | 超时/取消的轮次不落行（已知缺口） |
| `profile` | 用户画像与偏好 | 抽取走独立模型调用 |
| `team` | 动态团队协调（SHADOW/ACTIVE） | 执行在 agent 模块，这里只放协调与配置 |
| `devteam` | 研发场景包（冻结，无 REST/UI） | 有 22 个文件直接依赖薪酬域，暂不可移植 |
| `kb` / `kb/parse` | 知识库领域类型 + 解析客户端协议 | 解析执行在 `payroll-parser` |
| `health` | readiness 探针、runtime 探活、Redis 探针 | 只探 db/redis，不探 runtime/ES |
| `jobs` | 任务租约（多副本互斥） | 清理任务、保留任务、启动统计都靠它 |
| `config` | `PayrollCoreConfiguration`（巨型装配）、`TopologyGuard`、`DeploymentMode` | 单文件 100+ 处 `@Value`；新增键同时进 yml |
| `support` | `Permissions`、`JsonSupport`、`AuditSanitizer`、`AuditContext` | 权限码只在这里登记 |

## 装配入口

- 配置基座：`src/main/resources/application-common.yml`（profile：`mysql` / `redis` / `prod` 叠加）。
- Bean 装配：`config/PayrollCoreConfiguration.java`。新服务大多要在这里显式声明。
- 启动校验：`config/TopologyGuard.java`（默认 `warn`，prod 应 `fail`）。

## 常见改动路径

| 要做的事 | 动哪里 |
| --- | --- |
| 加一个模型可调用的工具 | `agent/` 新类 + `@Tool` + `ToolSafety` 标注 + 在 `PayrollAgentFactory` 的白名单里挂载 |
| 加一张表 | `db/migration/common/V{n}__*.sql` + `db/` 下 Store + `config/` 注册 |
| 加一种薪酬计算项 | `domain/Money` 口径 + `service/PayrollEngine` + `service/ExcelExporter` + 审批指纹影响面 |
| 加权限码 | `support/Permissions` → 角色种子 → `@RequirePermission` → 权限测试 |
| 加配置项 | `application-common.yml` 一处默认值 + `@Value` 注入；不要在 Java 里再写一套默认值 |
| 改审计脱敏 | `support/AuditSanitizer`（当前是**精确匹配**，`apiKey` 漏网） |

## 禁止事项

1. 金额计算禁止引入 `double`、禁止手写 `setScale`，一律 `Money`。
2. 禁止在 `web` 包加 Controller；禁止在 `domain` 引入 Spring。
3. 禁止用 `PayrollDatabase` 之外的方式开连接（会绕过指标与方言适配）。
4. 禁止把外部调用（ES/Nacos/沙箱/邮件）包进 DB 操作序列里假装"事务"。
5. 禁止用错误消息文本做控制流分支（用异常类型）。

## 坑

- **`PayrollDatabase` 自管连接，全仓没有 `TransactionTemplate`**。`PayrollDatabaseDataSource` 已写好适配器但**没有装配**，所以「写了 `@Transactional` 就原子」不成立——改多表写入前先读 `docs/architecture-remediation-2026-11-27.md` W3。
- `PayrollPolicyProvider` 的注释承诺「未维护城市失败关闭」，但 `H2PayrollRepository.policyFor` 会静默回落「默认」策略（已知 P0-1，整改中）。**不要以为这里已经安全。**
- H2 是单写者直连（无池），批量核算会反复建连；MySQL 才有 Hikari 池。
- 审计脱敏名单是字段名精确匹配，`apiKey` / `accessKey` 不在名单内。

## 验证

```bash
mvn -o -pl payroll-common -am -DskipTests package
mvn -o -pl payroll-tests -am test -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false
```

改共享逻辑后跑全量 `mvn -o test`；涉及运行期的一律按 `.qoder/skills/payroll-verify/SKILL.md` 走真实对话验证。
