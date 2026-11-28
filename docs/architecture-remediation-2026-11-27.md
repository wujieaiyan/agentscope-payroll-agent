# 可交付整改规格（2026-11-27）

- 依据：[`architecture-review-2026-11-27.md`](architecture-review-2026-11-27.md)
- 性质：可执行规格。按工作流（W0–W10）拆分，每个工作流含改动清单、步骤、验收、回滚。
- 估算口径：人日，含自测，不含评审等待；单人 + AI 协作节奏。
- 状态：**D1–D6 未拍板**（见 §3）。未拍板项按「推荐默认」先做不依赖决策的部分（W0 / W2 / W6），依赖决策的工作流等拍板后启动。
- 与既有规格的关系：本文接续 `architecture-remediation-spec.md`（M0–M8，2026-11-22）与 `capacity-resilience-remediation-spec.md`（C0–C6）。M/C 系列未完成项不在此重复；本文只处理 2026-11-27 复审新增的 14 条。

---

## 1. 目标与非目标

### 目标（按优先级）

1. **止血（本周）**：三个"会算错钱"的缺陷 + 一个"生产必坏"的拓扑缺陷 + 默认凭据，全部关闭。
2. **可交付（1 个月）**：发放不可重复、状态与事件原子、税额可复现、PII 不出界、集群拓扑在 CI 可验证。
3. **可运营（1 季度）**：行级数据权限、编排收敛、配置可校验、审计可作证据。

### 非目标（本期明确不做）

- 不补齐发放域（银行代发文件、真实 SMTP、回单、员工侧工资条）——见 D1；
- 不做多租户；
- 不拆微服务、不动 Maven 模块划分；
- 不重写 `PayrollEngine` 的金额算法（只改税额状态来源与政策取数）；
- 不重建前端设计系统（只统一请求/提示/确认三层）；
- 不做全量 N+1 优化与 JSON 列规范化（记录在 §11）。

---

## 2. 约束与红线

- 金额计算仍由 `PayrollEngine` 唯一负责；任何整改不得让模型参与金额。
- 新表只写一份 Flyway 迁移（`payroll-common/src/main/resources/db/migration/common/V{n}__*.sql`），H2（`MODE=MySQL`）与 MySQL 共用，SQL 需双方言可执行。
- **迁移版本号按合并先后取下一个可用号，不按本文的 W 编号排序**。本文出现的 `V{n}` 一律是占位符：W2（邮件幂等）排期早于 W1（税额表），若按 W 编号写死版本号会造成低版本后到、Flyway 乱序失败。取号时先 `ls db/migration/common | sort -V` 看当前最大值。
- 新管理接口必须带权限码 + 审计 + 测试。
- 异常分支按类型判断，**禁止用错误消息文本匹配做控制流**。
- 不得把外部调用（ES / Nacos / 沙箱 / 邮件 / Excel 生成）放进数据库事务。
- 不得用 `synchronized` / 进程内锁解决跨节点幂等（`cluster` 模式必须走 DB 条件更新或 Redis）。
- 每个工作流合并前：全量测试绿 + 文档回写 + 在 `node-role=web` 与 `all` 两种形态各起一次。
- **禁止的"临时修法"**（评审时直接打回）：
  - 用 try-catch 吞掉「城市未维护」再套默认值；
  - 在 `NodeRoleFilter` 里写 `if ("/internal/kb".equals(path))` 单路径硬编码（必须是前缀清单）；
  - 为幂等在 Controller 加 `synchronized` 或进程内 `ConcurrentHashMap`；
  - 为"补事务"把邮件/ES 调用包进 `TransactionTemplate`；
  - 用"后续版本再改"的注释替代本次修复。

---

## 3. 决策前置（D1–D6）

| 编号 | 决策 | 选项 | 推荐 | 影响面 |
| --- | --- | --- | --- | --- |
| D1 | 产品边界：是否补齐发放域 | A 收窄为核算/复核工具 / B 补发放域 | **A**，把"不碰资金通道"写进 README 非目标与 PRD | B 需 30+ 人日且依赖外部银行接口，不属于本规格 |
| D2 | PII 出界策略 | A 默认 redacted / B 默认 local-only / C 不限制 | **A**：默认脱敏，第三方模型需显式 `allow` 且落审计 | 决定 W5 默认值与启动校验强度 |
| D3 | 城市政策缺省行为 | A 失败关闭 / B 保留回落但强告警 | **A**，但先跑一次盘点作业（`--audit-cities`）再切 | 决定是否引入破坏性行为变更 |
| D4 | 税额状态载体 | A 新表固化 / B 从历史明细推导 | **A**：`employee_tax_withholding` | 决定 W1 工作量（8–10 人日）与回填风险 |
| D5 | 事务抽象 | A `TransactionTemplate` / B 手写 Connection UoW | **A**，前提是先给 `PayrollDatabase` 暴露 `DataSource` | 决定 W3 改动面与既有 Store 兼容策略 |
| D6 | 行级权限模型 | A 部门域 / B 部门 + 项目域 | **A**：部门域 + 本人可读自己 | 决定 W4 与多部门共库时机 |

---

## 4. 工作流总览

| 编号 | 工作流 | 对应问题 | 产出 | 估算 | 依赖 | 阶段 |
| --- | --- | --- | --- | --- | --- | --- |
| W0 | 止血包 | P0-1/2/4/5 + P2 文案 | 5 个独立小 PR | 5 | — | 第 1 周 |
| W1 | 税额状态与政策版本 | P0-3 | 两张新表 + 引擎改造 + 回填 + 双跑 | 10 | D4 | 第 3–4 周 |
| W2 | 发放幂等与状态机 | P1-1 | 条件更新 + 唯一键 + 补偿 | 4 | — | 第 2 周 |
| W3 | 事务边界 | P1-2 | `DataSource` + 3 处事务 | 7 | D5 | 第 4–5 周 |
| W4 | 行级数据权限 | P1-5 | `DataScope` + 读取审计 | 9 | D6 | 第 6–8 周 |
| W5 | PII 出界治理 | P1-3 | 出站脱敏 + 供应商开关 + 日志降级 | 6 | D2 | 第 5 周 |
| W6 | 集群拓扑可验证 | P0-4 防回归 + P1-9 | CI services + 角色集成测试 + e2e job | 6 | W0 | 第 2–3 周 |
| W7 | 编排收敛 | P1-7 + P1-6 | `RouteResolver` + `TurnBudget` + 取消 + shadow guard | 12 | W6 | 第 9–12 周 |
| W8 | 配置治理 | P1-8 | 配置对象 + 校验 + 快照 | 6 | — | 第 6 周 |
| W9 | 可观测与审计补齐 | P1-4 + P2 沙箱审计 | 5 条 SLI + traceId + 留存策略 | 7 | W3 | 第 7–9 周 |
| W10 | 前端收敛 | P1-10 | 统一三层 + `useChatStream` + 路由权限 | 10 | — | 第 8–11 周 |

合计约 **82 人日**：单人约 4 个月，两人并行约 2 个月。W0 / W2 / W6 不依赖决策，可立即开始。

---

## 5. 详细设计

### W0 止血包（5 人日，5 个独立 PR，各自可单独发布）

#### W0-1 城市政策失败关闭

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `payroll-common/.../db/H2PayrollRepository.java:208-217` | 删除「查不到回落默认」分支，`policyFor` 返回 `null`；README 的"回落"描述同步删除 |
| `payroll-common/.../service/PayrollEngine.java:210-217` | 保留失败关闭分支；错误消息附上「可用城市」列表（来自 `city_policies`） |
| `payroll-common/.../service/ExcelImportService.java` | 导入时校验 `city` 在册，逐行失败（复用现有行级错误机制） |
| `payroll-backend/.../web/PayrollWebController.java` | 员工新建/编辑同样校验，返回 400 带可用城市 |
| `docs/architecture-review-2026-11-27.md`、`README.md` | 回写行为变更 |

**步骤**

1. 新增只读盘点：`PayrollApplication --audit-cities`，输出「库中员工 city 不在 `city_policies` 的行数与明细」，退出码 1 表示有缺口。
2. 加配置 `payroll.policy.strict: true`（默认 true；`false` 时恢复旧回落，仅保留一个版本作回退）。
3. 按「先盘点、补政策、再切 strict」上线：盘点为空才允许在客户环境开 strict。
4. 删 README 与代码注释里"未维护城市回落到默认"的表述。

**验收**

- `PayrollEngineTest`：城市未维护 → 抛 `PayrollException`，消息含可用城市。
- `H2PayrollRepositoryTest`：`policyFor("不存在市")` 返回 null。
- `ExcelImportServiceTest`：非法 city 行报错且不影响其他行。
- 端到端：批次预览里有失败员工，失败原因可读。

**回滚**：`PAYROLL_POLICY_STRICT=false` 即恢复旧行为，无需回滚代码。

#### W0-2 停用员工不再复活

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `payroll-common/.../db/H2PayrollRepository.java:278-309` | INSERT 分支保留 `TRUE`；UPDATE / `ON DUPLICATE` 分支**删除** `active = TRUE` |
| `payroll-common/.../service/ExcelImportService.java:207` | 同上，导入不改变 `active` |
| `payroll-backend/.../web/PayrollWebController.java` | 新增显式 `POST /api/employees/{id}/active`（`{"active":true}`）承载启用/停用，权限沿用 `employee:write` |
| `payroll-common/.../domain/Employee.java` | 增加 `@JsonProperty("active") boolean active`（仅读取展示用，写入仍走独立接口） |
| `payroll-common/.../service/PayrollRunService.java` | 出账前对每人二次校验在职状态，已停用列入失败 |

**步骤**

1. 先加独立启停接口（此时行为不变，仍可被覆盖）。
2. 再改两条 upsert 的 UPDATE 分支。
3. 最后加出账前校验。
4. 三步各自可发布，避免一次改三处。

**验收**

- 单测：停用 → 编辑 → 仍为停用；停用 → Excel 导入 → 仍为停用；显式启用 → 恢复。
- 单测：已停用员工出现在批次里时，出账前被拦为失败项并给出原因。
- 回归：`PayrollAdminWebTest`、`ExcelImportServiceTest` 全绿。

**回滚**：纯 SQL 行为改动，回退代码即可；无数据迁移。

#### W0-3 集群下 KB 内部接口可达

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `payroll-common/.../web/NodeRoleFilter.java:27-35` | 引入 `INTERNAL_PATH_ALLOWLIST`（前缀集合，当前仅 `/internal/kb/`），web 角色命中白名单放行；注释写明「KB 内部接口由 HMAC 保护，Web 必须暴露」 |
| `payroll-tests/.../NodeRoleFilterTest.java` | 补"web + /internal/kb/check 放行 / web + /internal/turns 404" |
| `payroll-tests/.../`（新增 `NodeRoleWebKbIT.java`） | `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `payroll.node-role=web`，签名请求 `/internal/kb/check` 期望 200 |

**步骤**

1. 先写失败的集成测试（红）。
2. 加前缀白名单（绿）。
3. 确认 `/internal/turns`、`/internal/mcp` 等在 web 角色仍 404。
4. 在 `deployment-matrix.md` 记录「web 角色必须暴露 `/internal/kb/**`」及原因。

**验收**

- `NodeRoleWebKbIT` 绿；`NodeRoleFilterTest` 绿。
- 手工：`docker compose -f docker/docker-compose.yml -f docker/docker-compose.prod.yml up` 后，从 runtime 容器 `curl` web 的 `/internal/kb/check`（无签名）应 401 而非 404。
- 反向验证：临时把白名单清空，`NodeRoleWebKbIT` 必须变红（证明用例有效）。

**回滚**：删除白名单条目即可，但会导致 KB 在集群下不可用——只允许在故障止损时使用。

#### W0-4 守卫与默认凭据

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `payroll-common/.../config/TopologyGuard.java:180-184` | 新增「已知默认值黑名单」（`local-demo-token-change-me`、`local-parse-token-change-me`、`local-sandbox-token-change-me`、`admin123`、`payroll/payroll`），命中 `errors` 而非仅 `isBlank()` |
| `TopologyGuard` 新增校验 | `payroll.auth.enabled=false` 在 `production` 下为 error |
| `payroll-common/src/main/resources/application-prod.yml` | `payroll.guard.strict: fail` |
| `docker/docker-compose.prod.yml` | 三 token 改 `${VAR:?}`；`mysql`/`redis` 追加 `ports: []` |
| `docker/nginx.conf:38-43` | `/actuator/` 改为 `deny all`，只保留 `= /livez`、`= /readyz` 与 `/actuator/health/liveness|readiness` |
| `frontend/src/views/LoginView.vue:50` | 删除默认口令文案；改为「首次登录请使用管理员分配的账号」 |
| `payroll-common/.../support/AuditSanitizer.java:17-29` | 敏感名单改 contains 匹配，补 `apikey/accesskey/secretkey/credential/authorization/x-api-key` |
| `payroll-backend/.../web/ModelConnectionAdminController.java` | `payload_json` 对该路由整体不落库 |

**步骤**

1. 守卫先加黑名单（此时 `strict=warn`，只多打印错误项，不阻断）。
2. 一个观察周期后，`application-prod.yml` 切 `strict: fail`。
3. compose / nginx / 前端文案 / 脱敏四处同批发。
4. 发布说明中列出「升级后 prod 必须提供 4 个变量」的迁移指引。

**验收**

- `TopologyGuardTest`：默认 token → errors 非空；`strict=fail` → 启动抛异常。
- `docker compose config` 在缺变量时报错退出。
- `curl http://<web>:8080/actuator/prometheus` 期望 403/404；`/livez` 期望 200。
- `AuditServiceTest`：请求体含 `apiKey` 时落库值为 `***`。

**回滚**：`PAYROLL_GUARD_STRICT=warn` 临时降档；compose 变量用旧值。**注意**：黑名单本身不要回滚。

#### W0-5 P2 两处半小时修复

- `frontend/src/views/admin/TasksView.vue:37` → 直接 `push('/payroll/runs')` 并在 `RunsView` 读 `route.query.run` 打开详情抽屉。
- `frontend/src/router/index.js:134` 的静态 redirect 改为函数式，保留 query。
- 顺手修三处文档漂移：模块数（7）、默认 DB（`mysql`）、`/health` → `/api/health`。

---

### W1 税额状态与政策版本（10 人日）

**问题**：累计预扣税的「累计已预扣」由重算推导，且历史月份用当前政策 → 同一月份可算出两个税额。

**改动清单**

| 文件/对象 | 动作 |
| --- | --- |
| `V{n}__tax_withholding_and_policy_version.sql` | 新增 `employee_tax_withholding`（PK `(employee_id, period)`）与 `payroll_policy_version`（PK `(city, effective_from)`） |
| `payroll-common/.../service/PayrollEngine.java:64-86` | `TaxState` 改为：若期间已出账 → 从 withholding 装载；否则按「版本表取政策」逐月递推 |
| `payroll-common/.../db/H2PayrollRunStore.java` | `finalize` 同事务写入 withholding（PK 冲突即已固化，不覆盖） |
| `payroll-common/.../db/H2PayrollRepository.java:253-276` | 政策写入：先落 `payroll_policy_version` 新行，再更新 `city_policies` 当前指针 |
| `payroll-backend/.../web/PayrollWebController.java:244-253` | 政策保存接口接受可选 `effectiveFrom`；缺省为当月 1 日 |
| 一次性回填作业 | 从 `payroll_details.result_json` 反推历史 withholding 行，只处理已 `FINALIZED`/`SENT` 批次 |

**步骤**

1. DDL 先上（两表 + 索引），代码不动（可安全重放）。
2. 政策版本表写入 + 读取切换：`policyFor(city, period)` 重载，旧签名保留一个版本。
3. 回填作业：dry-run 输出「可回填 / 冲突 / 无法解析」三类计数；确认后执行。
4. **双跑期**：`payroll.tax.state.mode=shadow` —— 新旧算法同时算，差异写 `payroll.tax.replay.diff` 指标与告警日志，但不改结果。
5. 无差异后切 `mode=authoritative`，`PayrollEngine` 只走新路径。

**验收**

- `PayrollEngineTaxStateTest`：改 3 月考勤后，4 月的「累计已预扣」不变；未出账月份仍按重算。
- `PayrollPolicyVersionTest`：同一城市两个生效版本，按期间取到正确版本。
- `TaxBackfillTest`：回填结果与明细里的 `tax` 字段逐人一致（允许 0.01 差异，需显式列出）。
- 指标：shadow 期 `payroll.tax.replay.diff` 收敛到 0。

**回滚**：`mode=shadow` 或 `legacy`；两表留存不删（回填数据可复用）。

**风险**：回填出错会污染税额基数。必须 dry-run + 逐人比对 + 保留回填前的快照表。

---

### W2 发放幂等与状态机（4 人日）

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `payroll-common/.../db/H2PayrollRunStore.java:206-216` | `markStatus` 改为条件更新：`UPDATE ... SET status=? WHERE run_id=? AND status=?`，返回受影响行数 |
| `payroll-common/.../service/PayrollRunService.java:195-208` | 先 `FINALIZED → SENDING` 抢占（失败即 409/幂等返回），再发送，再 `SENDING → SENT`；异常时 `SENDING → FINALIZED` 并记 `send_attempts` |
| `V{n}__email_delivery_idempotency.sql` | `email_deliveries` 加唯一键 `(run_id, employee_id)`；`payroll_runs` 加 `send_attempts INT DEFAULT 0` |
| `payroll-backend/.../web/PayrollWebController.java:498-510` | 审计事件区分 `send.ok` / `send.conflict` / `send.failed` |

**验收**

- 并发测试：两线程同时 `send`，仅一个 200，另一个 409；`email_deliveries` 无重复行。
- 故障注入：`markStatus(SENT)` 抛异常后重试，只补发 FAILED 项，成功项不重发。
- 回归：`PayrollRunServiceTest` 全绿。

**回滚**：条件更新本身是增强，回退代码即可；唯一键保留（历史数据需先去重）。

---

### W3 事务边界（7 人日）

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `payroll-common/.../db/PayrollDatabaseDataSource.java` | **已存在但未装配**（目前只被 `PayrollDatabaseDataSourceTest` 使用）。把它接到 `DataSourceTransactionManager` + `TransactionTemplate`（`PROPAGATION_REQUIRED`，超时 10s） |
| `ApprovalService.java:74-120` | 四处状态迁移 + `recordEvent` 收进同一事务 |
| `PayrollRunService.java:178-187` | `updateResult` 批量更新 + `markFinalized` 同事务；Excel 生成与文件写移到事务之后 |
| `H2PayrollRunStore` 的逐条 `markStatus` | 改为 `batchUpdate`（同一连接） |
| `KbSeriesService.java:305-323` | DB 删除收进事务；ES 与文件清理移到提交后，失败只记待办 |

**步骤**

1. 先装配 `PayrollDatabaseDataSource` 与事务模板（适配器已写好并有用例，缺的只是接线；这一步不改业务代码）。
2. 逐服务迁移，顺序：审批 → 出账 → 知识库删除。
3. 每迁一个，配一条「中途抛异常断言无部分写入」的测试。

**验收**

- `ApprovalServiceTransactionTest`：在 `recordEvent` 前注入异常，断言状态未变且无事件行。
- `PayrollRunFinalizeTransactionTest`：第 2 个员工更新时抛异常，断言整批明细未变。
- 回归：全量 `mvn -o test` 绿（重点 `Chat*WebTest`、`KbWebTest`）。

**回滚**：`payroll.tx.enabled=false` 时退回无事务路径（保留一个版本）。

**风险**：`PayrollDatabase` 自管连接与 Spring 事务共存期可能出现「两个连接」——必须先确认事务内所有 Store 都用同一 `DataSource`，否则事务是假的。这一点在验收里用「连接数指标不增长」验证。

---

### W4 行级数据权限 v1（9 人日）

**设计**：权限分两层——功能权限（现有 `@RequirePermission` 不变）+ 数据域（新增 `DataScope`）。

- `payroll.data-scope.mode=all|dept`（默认 `dept`；单机演示可 `all`）。
- `AuthUser` 增加 `departmentId`；`DataScope` 由 `AuthContext` 派生：`ALL` / `DEPT(departmentIds)` / `SELF(employeeId)`。
- Service 层统一入口：`EmployeeQueryScope.apply(scope)`，禁止在 Controller 里手写 `WHERE`。

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `payroll-common/.../domain/AuthUser.java` | 增加 `departmentId` |
| `payroll-common/.../service/DataScope.java`（新增） | `ALL/DEPT/SELF` + 部门集合 |
| `H2PayrollRepository`、`H2AdminRepository` | 列表/详情查询接受 scope；跨域访问抛 `PayrollForbiddenException`（403） |
| `PayrollWebController`、`AdminController` | 员工、考核、考勤、批次、待办全部接 scope |
| `AuditService` | 新增 `sensitive.read` 事件（按用户+资源+条数聚合，限流采样） |
| `application-common.yml` | `payroll.data-scope.mode`、`payroll.data-scope.read-audit-sample-rate` |

**验收**

- `DataScopeTest`：HR-A（部门 A）列员工只见 A；跨部门详情 403；`ADMIN` 见全部。
- `SensitiveReadAuditTest`：读取 100 人明细产生 1 条聚合审计，含条数与范围。
- 回归：`PayrollAuthWebTest`、`PayrollAdminWebTest` 全绿。

**回滚**：`mode=all` 一键回退（默认改为 `dept` 属于行为变更，需在发布说明中写明）。

---

### W5 PII 出界治理（6 人日）

**设计**：三道闸——出站脱敏、供应商开关、日志降级。

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `payroll-common/.../agent/PayrollTools.java:110-125` | 工具结果按策略裁剪：`bank_account_masked` 移除；`special_additional_deduction` 在 `redacted` 下只给总额；`name` 保留（回答必需） |
| 新增 `payroll-common/.../agent/ModelEgressPolicy.java` | `local-only` / `redacted` / `allow`；`local-only` 时校验 baseUrl 为回环或私网，否则启动失败 |
| `payroll-agent/.../agent/model/OpenAiCompatibleChatModel.java` | 请求前过一遍策略（兜底，防新工具绕过） |
| `payroll-common/.../config/PayrollCoreConfiguration.java` | 注册策略 bean + 启动日志打印生效档位 |
| `PayrollChatService.java:772-780,1494-1498,2236-2245` | 金额/员工标识降为 DEBUG 或只打数量与 traceId |
| `README.md` + `deployment-matrix.md` | 写明三档语义与默认值 |

**验收**

- `ModelEgressPolicyTest`：`redacted` 下工具结果 JSON 不含银行卡、不含扣除明细项；`local-only` + 公网 URL → 启动失败。
- 日志断言：INFO 级不含金额数字（用日志捕获器）。
- 端到端：默认配置下一轮核算对话仍可正常回答（脱敏不破坏工具契约）。

**回滚**：`PAYROLL_MODEL_PII_POLICY=allow` 恢复原行为（不建议长期）。

---

### W6 集群拓扑可验证（6 人日）

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `.github/workflows/ci.yml` | 加 `services: mysql:8.0`（健康检查 + 账号）、`redis:7`；main 分支设 `PAYROLL_TEST_MYSQL_*` 跑 `integration` 组 |
| 新增 job `e2e` | `npm ci` → `npm run build` → `npx playwright test`（`webServer` 改 `vite preview` 或 nginx 托管 `dist`） |
| `frontend/playwright.config.js:4-6,20-25` | 浏览器路径改为仅本地 `.env` 生效（CI 用默认缓存） |
| 新增 `NodeRoleMatrixIT` | 参数化 `node-role=all|web|runtime` × `/internal/kb/check`、`/internal/turns`、`/api/health` 的期望状态码 |
| `docs/deployment-matrix.md` | 记录 CI 覆盖的角色矩阵与未覆盖项 |

**验收**

- 故意把 `NodeRoleFilter` 白名单删掉 → CI 必红（证明门禁有效）。
- `DdlSyntaxTest` 在 CI 真实 MySQL 上执行（不再软跳过）。
- e2e 在 CI 产出截图/录像 artifacts。

**回滚**：新增 job 可单独禁用，不影响既有三 job。

---

### W7 编排收敛（12 人日）

**改动清单**

| 文件 | 动作 |
| --- | --- |
| 新增 `payroll-agent/.../service/RouteResolver.java` | 纯函数：输入「问题 / 画像 / KB 判定 / 流程可用性 / 上一轮角色 / team 开关」→ `RoutePlan{path, agent, reason, fallbackFrom}`；禁止 IO |
| `PayrollChatService.java:792-1171` | 删除 `plannedPath` 的 5 处覆写，改为一次决策；`run()` 只负责执行与事件 |
| 新增 `TurnBudget.java` | 单轮总预算（默认 300s），向下传；各阶段取 `min(阶段超时, 剩余预算)` |
| `AgentFlowExecutor.java:194-206,444-457` | `future.get()` 改 `get(remaining, MS)`，超时取消节点 |
| `ChatSessionManager.java:931-945,1289-1307` | `cancelInternal` 中断执行线程；`PayrollChatService` 注入取消信号，`takeUntilOther` |
| `ShadowTeamService.java:241-258` | 必须显式传 `ActionGuard`；`null` 时注入 `ActionGuard.readOnly()`（写工具直接拒绝） |
| `PayrollTools.java:393-400`、`KbTools.java:400-409` | `guard == null` 改为 fail-closed（与 `SkillRunTools` 一致） |

**步骤**

1. **先建快照测试**：用现有 20 个典型问题跑 `RouteResolver`，固化期望路径（此时是"提取现状"，允许不完美）。
2. 抽 `RouteResolver`（纯提取，不改行为），快照必须完全一致。
3. 再改行为（团队默认 `OFF`、路由未生效不触发 team gate），快照按预期更新并评审。
4. 加 `TurnBudget` 与取消传播（可独立发布）。
5. 最后修 shadow guard（安全修复，可提前单独发）。

**验收**

- `RouteResolverTest`：20 例快照；`turnBudget` 超预算返回明确终态事件。
- `CancelPropagationTest`：取消后 1s 内执行线程结束，`llm_usage` 落一行 `cancelled`。
- `ShadowGuardTest`：`SHADOW` 模式下写工具返回拒绝，且不产生提案。
- 回归：`Chat*WebTest`、`KbWebTest`、`AgentFlowExecutorTest` 全绿。

**回滚**：`payroll.routing.resolver=legacy` 保留旧分派一个版本。

**风险**：这是本规格最大的一次重构。没有快照测试不得动手。

---

### W8 配置治理（6 人日）

**改动清单**

| 文件 | 动作 |
| --- | --- |
| 新增 `payroll-common/.../config/{ChatProperties,ModelProperties,SkillProperties,JobProperties}.java` | `@ConfigurationProperties` + `@Validated`，按域收敛 3–5 个类 |
| `PayrollCoreConfiguration.java`（109 处 `@Value`） | 逐步改为构造注入配置对象；删除 Java 侧默认值，只留 yml 一份 |
| `LocalDiskSkillBlobStore.java:58-63` | `System.getenv` 改属性注入，与 `PayrollCoreConfiguration` 同一键 |
| 新增启动快照 | 启动日志打印生效配置（脱敏）+ 写入 `/actuator/info` |

**步骤**

1. 先建配置类与校验，`@Value` 保留（双读），启动断言两处值一致。
2. 逐域替换，每域配一条「非法值启动失败」测试。
3. 最后删 Java 默认值与 `System.getenv`。

**验收**

- `ConfigurationValidationTest`：`payroll.team.mode=XXX` → 启动失败并指出键名。
- 一致性断言：yml 与 Java 默认值不一致的 4 处（`memory.enabled`、`team.mode`、`shadow-sample-rate`、OCR dpi）消除。
- `PAYROLL_SKILL_BLOB_ROOT` 只有一条读取路径。

---

### W9 可观测与审计补齐（7 人日）

**改动清单**

| 文件 | 动作 |
| --- | --- |
| 新增 `ops/prometheus/alerts.yml` | 5 条 SLI：轮次成功率、首字 P95、429 率、KB 检索超时率、`readyz` 状态 |
| `payroll-common/.../support/AuditContext.java:19-23` | `traceId` 写入 MDC；`AgentRuntimeClient` 透传到内部调用头 |
| `logback.xml:4-15` | 加 `%X{traceId}` 与 JSON encoder（可切换） |
| `payroll-sandbox/.../SandboxService.java:27-64` | 落 `sandbox_execution`（`V{n}`），含 `script_sha256`、退出码、耗时、truncated |
| `AuditController.java:204-216` | CSV 转义 `= + - @` 前缀 |
| `AuditFilter.java:90-99` | 仅当对端在受信代理网段才采信 XFF |
| `application-common.yml:252` + `RetentionConfiguration.java:37` | 留存策略改为「审计 5 年、工资永久、聊天追溯与工资同生命周期」，默认值对齐法定义务；改动需审计 |

**验收**

- `AuditExportTest`：`=cmd|` 开头内容被前缀转义。
- `SandboxAuditTest`：一次执行产生一条记录，含 sha 与退出码。
- 日志：同一轮对话在 Web 与 runtime 两侧日志可用同一 `traceId` grep 到。

---

### W10 前端收敛（10 人日）

**改动清单**

| 文件 | 动作 |
| --- | --- |
| `frontend/src/kb/api/http.js:33-56` | 删除，改用 `frontend/src/api/http.js`（统一 `ApiError` + toast） |
| `frontend/src/kb/stores/auth.js:13-15` | 删除 `role_code === 'ADMIN'` 短路，统一 `can()` |
| 新增 `frontend/src/components/Can.vue` + `useAsyncList` | 权限渲染与 `loading/empty/error` 三态复用 |
| `EmployeesView.vue:172`、`RunsView.vue:313-319` | 写操作按权限 `v-if`；导入抽 `useExcelImport` + `ImportReport` |
| `router/index.js:152-167` | 守卫加 `to.meta.menu?.permission` 校验 + 403 页；`/chat` 补 `chat:use` |
| `ChatView.vue`（3200 行） | 抽 `useChatStream`（连接/续传/游标）、`useSessionMessages`；先补 `handleEvent` / `resumeLoop` 纯函数单测，再拆组件 |
| `ChatView.vue:681-687,2098-2103` | 会话列表失败不再自动新建会话，渲染错误态 + 重试 |
| `ChatView.vue:1625,1649-1662` | 任何未建轮的发送失败都回填草稿与附件 |

**验收**

- 单测覆盖：`handleEvent`、`resumeLoop`、会话切换竞态、`can()`。
- `npm run build` + `npm run test` 绿；e2e 进 CI 后 `skills-smoke` 绿。
- 手工：VIEWER 直接访问 `/platform/users` 看到 403 页而不是空壳。

---

## 6. 配置清单（新增/调整）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `payroll.policy.strict` | `true` | 城市政策缺失是否失败关闭（W0-1，回退开关） |
| `payroll.tax.state.mode` | `shadow` | `legacy` / `shadow` / `authoritative`（W1） |
| `payroll.tx.enabled` | `true` | 事务路径开关（W3，回退用） |
| `payroll.data-scope.mode` | `dept` | `all` / `dept`（W4） |
| `payroll.data-scope.read-audit-sample-rate` | `0.1` | 敏感读取审计采样率（W4） |
| `payroll.model.pii-policy` | `redacted` | `local-only` / `redacted` / `allow`（W5） |
| `payroll.routing.resolver` | `resolver` | `legacy` 回退开关（W7） |
| `payroll.turn.budget-seconds` | `300` | 单轮总预算（W7） |
| `payroll.guard.strict` | prod 为 `fail` | 由 `application-prod.yml` 覆盖（W0-4） |
| `payroll.audit.retention-days` | `1825` | 改为 5 年（W9） |

---

## 7. 兼容、灰度与回滚

| 工作流 | 灰度方式 | 回滚 | 数据不可逆点 |
| --- | --- | --- | --- |
| W0-1 | `strict=false → true` 两段上线 | 配置回退 | 无 |
| W0-2 | 三个 PR 分段 | 代码回退 | 无 |
| W0-3 | 直接发 | 删白名单（会致 KB 不可用） | 无 |
| W0-4 | 先黑名单后 `fail` | `strict=warn` | 无 |
| W1 | `legacy → shadow → authoritative` | 配置回退 | **回填**：保留回填前快照表 |
| W2 | 直接发 | 代码回退 | 唯一键需先清历史重复 |
| W3 | 按服务逐个迁 | `tx.enabled=false` | 无 |
| W4 | `all → dept` 两段 | `mode=all` | 无 |
| W5 | `allow → redacted` | 配置回退 | 无 |
| W7 | 快照先建，行为后改 | `resolver=legacy` | 无 |

**统一要求**：每个工作流的首个 PR 必须包含「如何在生产验证」的三步操作，写进 PR 描述。

---

## 8. 测试与验收（DoD）

每个工作流合并前必须满足：

1. `mvn -o -q -DskipTests package` 通过。
2. 相关单测 + 全量 `mvn -o test` 绿。
3. 新增行为有对应的**失败用例**（先红后绿），且能证明"把修复删掉会变红"。
4. `node-role=web` 与 `all` 两种形态各起一次，主路径走通。
5. 涉及金额的工作流（W0-1/2、W1、W2）必须给出「同员工同期间，修复前后金额对比」的记录。
6. 文档回写：`architecture-review-2026-11-27.md` 的对应行标记完成，`README` / `deployment-matrix` 同步。
7. 汇报只写已验证结果；未验证项明确写「未验证，原因是 X」。

**本规格的验收基线（全部完成才算「可交付」）**

- [ ] 三个金额缺陷有回归用例，删修复即红
- [ ] 集群 compose 下 KB 检索可用（真实 HTTP，非 mock）
- [ ] 同一批次并发发送只投递一次
- [ ] 审批状态与事件在同事务内
- [ ] prod 缺任一密钥/token 启动即失败
- [ ] 默认配置下工具结果不含银行卡与扣除明细
- [ ] CI 跑真实 MySQL/Redis，且角色矩阵覆盖 web

---

## 9. 排期

| 周 | 内容 | 人日 |
| --- | --- | --- |
| 第 1 周 | W0 全部（5 个 PR）+ 拍板 D1–D6 | 5 |
| 第 2 周 | W2 + W6 前半（CI services） | 6 |
| 第 3 周 | W6 后半（e2e job、角色矩阵）+ W1 DDL/回填 dry-run | 6 |
| 第 4 周 | W1 双跑期 + W3 事务模板 | 8 |
| 第 5 周 | W1 切 authoritative + W5 | 7 |
| 第 6 周 | W4 前置（`DataSource` 复用）+ W8 | 8 |
| 第 7–8 周 | W4 全量 + W9 前半 | 10 |
| 第 9–11 周 | W7（快照 → 提取 → 改行为）+ W9 后半 | 14 |
| 第 12–13 周 | W10 前半（统一请求/提示/确认 + 路由权限） | 6 |
| 第 14–16 周 | W10 后半（`useChatStream` + 单测） | 12 |

依赖关系：W1/W3 互不阻塞但同改 `PayrollEngine` 周边，建议串行；W4 依赖 W3 的 `DataSource`；W7 依赖 W6 的回归网。

---

## 10. 风险

| 风险 | 概率 | 影响 | 应对 |
| --- | --- | --- | --- |
| W0-1 失败关闭导致既有客户数据算不出工资 | 高 | 业务中断 | 先跑 `--audit-cities` 盘点；`strict` 两段上线 |
| W1 回填污染税额 | 中 | 金额永久错 | dry-run + 逐人比对 + 回填前快照 + `shadow` 期指标 |
| W3 事务是"假事务"（Store 各用各的连接） | 中 | 以为修好了 | 验收用连接数指标断言；事务内禁止跨 Store 新连接 |
| W7 重构引入路由行为漂移 | 高 | 用户可见的答案变化 | 快照测试先建；行为改动单独 PR + 评审 |
| W4 行级权限误伤（部门字符串与 `department_id` 不一致） | 中 | HR 看不到自己的员工 | 先加部门一致性巡检；`mode=all` 兜底 |
| 整改期间继续加功能 | 高 | 规格失效 | 冻结新页面/新场景包；PR 模板加"是否属于 W 系列"必填 |

---

## 11. 未覆盖与后续

本规格**故意不做**，留给下一轮：

- 发放域（银行代发、真实 SMTP、回单、员工侧工资条）——取决于 D1；
- `payroll_details` 金额列化与 `result_json` 拆分（对账/统计需要）——建议与 W1 同批评审，但独立立项；
- 外键与孤儿巡检；
- 审批指纹改为「金额口径集合」哈希；
- `payroll-common` 的对外 API 面清单与包内分层（`architecture-remediation-spec.md` M1 未完成部分）；
- 知识库列表的 SQL 分页与 N+1（当前全表加载）；
- 前端设计系统重建与可访问性规范（当前只统一三层）。
