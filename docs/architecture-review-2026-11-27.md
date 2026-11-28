# 架构与产品复审（产品经理 × 架构师视角）— 2026-11-27

- 复审日期：2026-11-27
- 复审范围：7 个 Maven 模块（protocol / common / parser / agent / backend / sandbox / tests）、前端 `frontend/src`、构建与部署（pom / docker / CI / application*.yml）
- 复审方法：静态读码。5 个模块并行取证 + 关键结论人工复核；**未执行构建与测试**（本机 pwsh 沙箱 ACL 异常，`SetNamedSecurityInfoW` 失败），未做动态验证。凡「会算错/会坏」的结论均为读码推断，须按 [`architecture-remediation-2026-11-27.md`](architecture-remediation-2026-11-27.md) §8 的验收用例复现确认后才算定论。
- 证据行号：基于 2026-11-27 工作区快照。批量改动后需重新核对。
- 与既有文档的关系：本文件**不重复** `architecture-review.md`（2026-11-22）已闭环项与 `product-prd.md` 已登记问题，只写「仍暴露的」与「本次新增的」。差异清单见 §7。
- 增补（2026-11-28）：§8 为技能与智能体的业界标准达标判定（目标态判定，不是缺陷登记），编号 S1–S5 独立于 §0 的 P1。

---

## 0. 结论先行

**一句话**：工程纪律与「确定性核算 + 写确认 + 审批 + 审计」的底座是这个项目最值钱的部分；当前卡点不是功能数量，而是**三笔账**——算错的账、出界的账、跑不起来的账。

| 账 | 事实 | 代价 |
| --- | --- | --- |
| 算错的账 | 3 个会静默产生错误金额的缺陷（未知城市回落、停用员工复活、累计预扣税不可复现） | 钱发错，且事后无法解释 |
| 出界的账 | 薪酬 PII 原文进第三方模型；默认 token/口令进入官方生产拓扑；审计含明文 `apiKey` | 合规事故，不可回收 |
| 跑不起来的账 | 集群拓扑下 `/internal/kb/**` 返回 404；发送无幂等；全仓零事务边界 | 演示全绿、上线即坏 |

| 等级 | 编号 | 问题 | 判据 | 建议 |
| --- | --- | --- | --- | --- |
| P0-1 | 金额 | 城市政策未维护时静默套用「默认」政策 | `H2PayrollRepository.java:213-215` vs 契约 `PayrollPolicyProvider.java:5` | 失败关闭 + 城市字典校验 |
| P0-2 | 金额 | 停用员工任一编辑即复活（`active=TRUE`） | `H2PayrollRepository.java:286,307` | 仅 INSERT 置真 |
| P0-3 | 金额 | 累计预扣税不落库，按「当前政策」整年重放 | `PayrollEngine.java:64-80,210-217` | 出账固化 withholding 行 |
| P0-4 | 交付 | `node-role=web` 时 `/internal/kb/**` 被自身过滤器 404 | `NodeRoleFilter.java:27-35` + `KbSkillController.java:35` + `docker-compose.yml:178,215` | 内部前缀白名单 + web 角色集成测试 |
| P0-5 | 安全 | 守卫默认 `warn`；默认 token/口令随官方 prod 路径发布 | `application-common.yml:66`、`docker-compose.yml:36,46,48,51`、`docker-compose.prod.yml`（无 `GUARD_STRICT`） | prod 强制 fail + 默认值黑名单 |
| P1-1 | 金额 | 发送 check-then-act，可能全员工资条发两遍 | `PayrollRunService.java:195-208` | CAS 抢占 + 幂等键 |
| P1-2 | 一致性 | 全仓无 `@Transactional`，状态与事件/明细分别提交 | `@Transactional` grep 零命中；`ApprovalService.java:74-80,104-120` | 最小事务抽象 |
| P1-3 | 合规 | 姓名/薪资/专项附加扣除原文进模型上下文 | `PayrollTools.java:110-125` | 出站脱敏 + 供应商开关 |
| P1-4 | 合规 | 审计不可信：`apiKey` 明文、CSV 注入、XFF 可伪造、默认 180 天清理 | `AuditSanitizer.java:17-29`、`AuditController.java:204-216`、`AuditFilter.java:90-99`、`application-common.yml:252` | 见 §3.3 |
| P1-5 | 内控 | 无行级数据权限；敏感数据读取不审计 | `PayrollRepository.java:14`（注释自认） | 部门域 v1 |
| P1-6 | 安全 | 影子团队执行写工具时 `ActionGuard` 为 null 即放行 | `ShadowTeamService.java:241-258`、`PayrollTools.java:393-400` | 注入 deny-all guard |
| P1-7 | 可靠性 | 取消/超时只改标记不中断执行；无单轮总预算 | `ChatSessionManager.java:931-945`、`PayrollChatService.java:2176-2277` | 预算下传 + 取消传播 |
| P1-8 | 可维护 | 216 个 `PAYROLL_*`、288 处 `@Value`、零 `@Validated`、同键两套默认值 | `application-common.yml`、`PayrollCoreConfiguration.java` | 配置对象化 + 启动校验 |
| P1-9 | 质量门 | CI 只跑 H2 + 桩；e2e 不进 CI；668 用例集中单模块 | `.github/workflows/ci.yml:11-41` | 加 MySQL/Redis service |
| P1-10 | 前端 | 两代前端并存（两套请求层/提示层/确认框）；`ChatView` 3200 行零组件测试；路由守卫不判权限 | `kb/stores/auth.js:13-15`、`router/index.js:152-167` | 统一三层 + `useChatStream` |

---

## 1. 现状画像（数字）

| 指标 | 数值 | 来源 |
| --- | --- | --- |
| Maven 模块 | 7（含 `payroll-parser`） | `pom.xml:22-30` |
| 文档 | `docs/` 50+ 篇；README 1,295 行 | `docs/*.md`、`README.md` |
| 数据库迁移 | V1–V24 单目录，H2/MySQL 共用 | `db/migration/common` |
| 表 | 50+ 张，**零外键** | `grep FOREIGN KEY` 零命中 |
| 测试 | 668 用例，0 失败 0 跳过，全部在 `payroll-tests` | 本机全量测试日志（`logs/`，未入库） |
| 前端 | 20 个菜单页 + 对话页；`ChatView.vue` 3200 行 | `router/index.js:38-142` |
| 控制面 | `PayrollChatService.java` 3087 行（上次复审 2991 行） | 文件尾行 |
| 配置 | `application-common.yml` 单文件 216 个 `PAYROLL_*`；Java 侧 288 处 `@Value` | 统计 |

判断：**规模已经不是「示例项目」，但抽象层次还停在「示例项目」**——共享层是第二个应用，控制面是单个巨型类，配置是扁平字典。这与 `architecture-review.md` §1 的结论一致，且两项指标（控制面行数、配置项数）在 5 天内仍在增长。

---

## 2. 做得对、应保留

1. **金额全部走 `BigDecimal` + `Money.round`**，日薪/时薪保留 10 位中间精度再收敛；未发现 `double` 参与金额。
2. **迁移只增不改**：24 个脚本无 `DROP/MODIFY/TRUNCATE`，H2 以 `MODE=MySQL` 共用一套 DDL。
3. **SQL 全参数化**，`PreparedStatement` 占位符，未发现拼接注入；连接/语句/结果集均 try-with-resources。
4. **沙箱隔离是真的**：`List<String>` + `ProcessBuilder` 无 shell、路径白名单逐段校验、`--network none --read-only --cap-drop ALL --user 65534`。
5. **内部 HMAC 实现正确**：常量时间比较 + 时间窗 + 空 token fail-closed（缺 nonce，见 P2）。
6. **审批指纹 + 409 契约**在提交/审批/出账三处一致校验，"审批后改考勤"会被拦住。
7. **写确认 + 审计 + 审批三件套**齐全，是薪酬场景刚需而非装饰。
8. **文档纪律**：规格先行的习惯罕见，50 篇文档与代码同步演进。

这些是「不能为了重构而重构」的部分，任何整改都必须保留它们。

---

## 3. P0：正确性与交付拓扑

### 3.1 金额正确性

#### P0-1 未知城市静默套用「默认」政策

- 契约：`payroll-common/src/main/java/io/agentscope/examples/payroll/service/PayrollPolicyProvider.java:5`
  「未维护的城市必须失败关闭」。
- 实现：`payroll-common/.../db/H2PayrollRepository.java:213-215` 查不到就 `readPolicy("默认")` 并返回；
  `PayrollEngine.java:213-215` 的「未维护请先维护」错误分支因此只在「默认」也缺失时才可达。
- 放大器：README「城市政策」一节把回落写成预期行为，**文档把危险行为合法化了**，评审时无人会当成缺陷。
- 后果：员工 `city` 写成「北京市」「苏州」等未维护值 → 用默认 7500/37500 基数与默认比例算社保公积金，全程不报错、不告警、无风险提示。
- 这是「三笔账」里最隐蔽的一笔：它不抛异常，只算错钱。
- 修复方向：删除回落；员工保存时做城市字典校验；无法解析时失败关闭并在批次里显式列出失败员工。

#### P0-2 停用员工被「编辑」即复活

- 证据：`H2PayrollRepository.java:278-309`，H2 分支 `MERGE ... VALUES (..., TRUE, ?)`（`:286`），
  MySQL 分支 `ON DUPLICATE KEY UPDATE ... active = TRUE`（`:307`）。
- 根因：`Employee` 记录没有 `active` 字段（`domain/Employee.java`），保存语义只能是「整行覆盖」，无法表达「保持停用」。
- 后果：对离职员工做任何一次编辑（含 Excel 批量导入）都会把他放回 `employees()` 查询结果，下一轮全员批次给他算钱并出工资条。
- 修复方向：`active` 只在 INSERT 置真；UPDATE 保留原值；离职改为独立状态机；出账前二次校验在职状态。

#### P0-3 累计预扣税不落库，按「当前政策」整年重放

- 证据：`PayrollEngine.java:64-80` 每次核算从 1 月 `TaxState.zero()` 逐月递推，`TaxState` 只存在于内存；
  历史月份调用的政策来自 `policyFor(city)`（`:210-217`），即**此刻**的 `city_policies` 行；
  `V1__payroll_core.sql:134-138` 的 `city_policies` 只有 `city` 主键 + 整份 JSON，无版本/生效期；全库无 withholding 类表。
- 后果：补录 3 月考勤、年中调社保比例、改绩效系数，都会让「4 月累计已预扣税额」变化，而 4 月工资条与税务申报已按旧数出账。`payroll_details.result_json` 冻结的是历史结果，但智能体回答、预览、复核走的是重算值 —— **同一员工同一月份存在两个税额**，且两个都"有据可查"。
- 关键认知：累计预扣法里的「累计已预扣税额」是**既成事实**，不能由重算推导。这是模型无法用提示词兜住的一类错误。
- 修复方向：新增 `employee_tax_withholding`，出账时固化；已出账月份只读；政策表加 `effective_from` 与版本行。

### 3.2 交付拓扑

#### P0-4 `node-role=web` 时 `/internal/kb/**` 返回 404（集群下知识库工具全挂）

- 证据链（三处互斥）：
  - `payroll-common/.../web/NodeRoleFilter.java:27-35`：`"web".equals(role) && internal` → 直接 404，**无任何前缀豁免**；
  - `payroll-backend/.../kb/KbSkillController.java:35`：知识库内部接口恰好挂在 `/internal/kb`；
  - `docker/docker-compose.yml:178,199`：runtime 的 `PAYROLL_WEB_NODE_URLS` 指向 `http://web-1:8080,http://web-2:8080`；`:215,249`：web 节点 `PAYROLL_NODE_ROLE: web`。
- 后果：按官方 compose 起集群后，runtime 的 `kb_search` / 知识库写工具对 Web 的**每一次调用都被 Web 自己 404** —— 知识库这个旗舰能力在集群形态下整体失效。测试跑 `node-role=all`（`KbWebTest` 等），所以 CI 永远绿。
- 这是本次复审最典型的「生产必坏、CI 必过」缺陷，也是 P1-9（质量门只覆盖 H2 + 桩）的直接产物。
- 修复方向：`NodeRoleFilter` 以「内部协议路径清单」而不是「`/internal/` 整段」做判定（或把 KB 内部接口迁到独立前缀并纳入 HMAC 白名单）；补一条 `PAYROLL_NODE_ROLE=web` 的真实 HTTP 集成测试。

#### P0-5 默认凭据进了官方生产路径，守卫又只告警

- 证据：
  - `docker/docker-compose.yml:46,48,51` 三个 `local-*-token-change-me`；`:36` `admin123`；`:28-29` `payroll/payroll`；
  - `docker/docker-compose.prod.yml` 全文只强制 `PAYROLL_SECRET_KEY`（`:22`），**未强制三个内部 token，也未移除 MySQL/Redis 的宿主端口映射**（`:87-88` 的 `13306:3306`、`:102-103` 的 `16379:6379` 仍在基础 compose 里）；
  - `payroll-common/src/main/resources/application-common.yml:66` 守卫默认 `warn`；`TopologyGuard.java:180-184` 只判 `isBlank()`，**不识别默认值**。
- 后果：按文档部署即得一套公开密钥 + 无密码 Redis（`application-redis.yml:12` 默认空）。任何能访问容器网络的一方可用仓库公开的 token 伪造 `X-Payroll-Internal` 调 `/internal/turns`、沙箱（可起 docker 容器）、parser；Redis 可被直接读写会话、轮次租约与事件流，绕过全部应用层鉴权。
- 另有两处同族问题：`nginx.conf:38-43` 把 `/actuator/*` 反代到公网（`WebConfig.java:26` 的拦截器只挂 `/api/**`）；`AuthInterceptor.java:37` 一个 `PAYROLL_AUTH_ENABLED=false` 即可关掉全部鉴权，且无人校验。
- 修复方向：守卫加「已知默认值黑名单」（token/管理员口令/数据库口令），命中即 error；prod 覆盖把三个 token 改 `${VAR:?}`；MySQL/Redis 用 `ports: []`；actuator 只放行探针路径。

### 3.3 合规四件（并入 P1）

审计目前不能作为薪酬合规证据使用，四个独立缺陷：

| 项 | 证据 | 后果 |
| --- | --- | --- |
| `apiKey` 明文落库 | `AuditSanitizer.java:17-29` 敏感名单无 `apikey`；`AuditFilter.java:66-72` 整段请求体落 `payload_json` | 写接口在 `record()` 前失败（如 400）时，`{"apiKey":"sk-..."}` 明文进审计表，随 `GET /api/audit` 与 CSV 导出扩散 |
| CSV 公式注入 | `AuditController.java:204-216` 未转义 `= + - @` 开头；`actorName` 来自登录输入（`AuthController.java:53-70`） | 未认证者用一个特殊用户名失败登录一次，管理员导出审计打开 Excel 即触发公式 |
| IP 可伪造 | `AuditFilter.java:90-99` 取 `X-Forwarded-For` 首段，不校验可信代理 | 溯源字段不可信，越权尝试可伪造内网 IP |
| 留存口径三条且都缺法定依据 | `application-common.yml:252` 审计 180 天清理；`RetentionConfiguration.java:37` 聊天/追溯 365 天；工资数据永久 | 「金额还在、计算依据没了」；工资台账（2 年+）、个税（5 年）、会计档案（10–30 年）义务无对应策略 |

---

## 4. P1：结构与演进

### 4.1 一致性：全仓零事务边界

- 证据：`@Transactional` / `TransactionTemplate` / `PlatformTransactionManager` 在 `payroll-common/src/main/java` **grep 零命中**。各 Store 自管连接、自动提交。
- 已经备好零件但没接线：`db/PayrollDatabaseDataSource.java` 是一个可用的 Spring `DataSource` 适配器，目前只被它自己的测试引用，没有任何 Bean 装配它。
- 具体断点：`ApprovalService.java:74-80`（`markSubmitted` 后单独 `recordEvent`）、`:104-120`（`markApproved`/`advanceStep` 后单独 `recordEvent`）、`PayrollRunService.java:178-187`（逐员工 `updateResult` → 写 Excel → `markFinalized`）。
- 后果：崩溃后出现「批次已 `PENDING_APPROVAL` 但没有 SUBMIT 事件」（追溯链断）或「前 N 个员工明细被新结果覆盖、批次仍是 `APPROVED`」（需人工比对）。
- 状态 CAS 本身写得对（`WHERE status='APPROVED'`），缺的是**与伴写操作的原子性**。
- 注意：不要为了"补事务"把外部调用（ES/Nacos/沙箱/邮件）包进事务。正确顺序是「DB 事务提交 → 再执行外部副作用」。

### 4.2 编排复杂度仍在增长

- 一条消息 6 类终端（语义缓存 / TEAM_ACTIVE / FLOW / AGENT / GRAPH / PIPELINE）；最坏串「路由 → 团队意图 → 团队计划」3 次 LLM 才进入干活节点。
- `PayrollChatService.java:819-888` 中 `plannedPath` 被反复覆写，`needKb` / `executorEnabled` 在 5 处交叉影响决策。
- **无单轮总预算**：单阶段 150s、`AgentFlowExecutor` 的 `future.get()` 无超时、团队 ACTIVE 无 deadline，而 SHADOW 分支反而实现了 300s（说明预算概念存在，只是没落实到用户可见链路）。
- graph 与 legacy 两套「等价」链路已经行为不一致（提案后是否继续走校验/核算）。

### 4.3 影子团队 fail-open

- `ShadowTeamService.java:241-258` 把真实 listener 交给执行链；`PayrollChatService.java:1913-1916` 取 `roundGuard`，为 null 时不写入上下文；`PayrollTools.java:393-400` / `KbTools.java:400-409` 在 `guard == null` 时**直接执行**。
- 对照 `SkillRunTools.java:159-164` 是 fail-closed —— 说明这是遗漏而非设计。
- 后果：`PAYROLL_TEAM_MODE=SHADOW`（规格要求先跑影子）下，后台以用户无感方式执行知识库写工具与核算工具，`always-confirm-tools` 与审计被绕过；`TeamIntent` 的写操作判定只是关键词匹配。

### 4.4 取消与超时不中断执行

- `ChatSessionManager.java:931-945,1289-1307` 的取消/看门狗只改标记；`ChatSse.java:218-228` 的 `cancelled()` 只用于丢弃增量；`PayrollChatService.java:2176-2277` 全程无取消检查。
- 后果：用户点「停止」后模型仍跑满阶段（最多 150s×N），占 `runtimeTurnExecutor` 的 8 个线程、token 照常计费、租约继续续期，只在落库时丢弃。连续取消可打满线程池，其他用户收 429。

### 4.5 PII 出界

- `PayrollTools.java:110-125` 把姓名、固定薪资、专项附加扣除、脱敏卡号整包返回模型；README 把 DashScope 在线模型列为推荐路径。
- 模型侧无出站脱敏、无「仅本地模型」开关、无委托处理留痕。
- 注意别被名字误导：`model/ModelOutboundPolicy.java` 管的是 **SSRF**（默认拒绝私网/回环，只放行本机 Ollama 端口），与「能不能把 PII 发给公网模型」无关——它放行的恰恰是公网地址。
- 附加风险：`PayrollChatService.java:772-780,1494-1498,2236-2245` 把问题原文、计算输出前 400 字、工具金额与员工标识写进 INFO 日志。

### 4.6 行级数据权限缺失

- `PayrollRepository.java:14` 注释自认「生产实现应…完成数据权限控制与脱敏」，即尚未实现。
- 现状：`employee:read` / `payroll:read` 可看全员工资，无部门/批次/员工范围；审计只覆盖写，**读敏感数据不审计**。
- 这是薪酬内控的底线要求，不解决就无法多部门共库。

### 4.7 配置治理失控

- `application-common.yml` 单文件 216 个 `PAYROLL_*`；Java 侧 288 处 `@Value`（`PayrollCoreConfiguration.java` 单文件 109 处）；全仓零 `@Validated`；仅 2 个 `@ConfigurationProperties`。
- 同一键两套默认值：`memory.enabled`（yml `true` vs Java `false`）、`team.mode`（yml `ACTIVE` vs Java `OFF`）、`shadow-sample-rate`（`1.0` vs `0.15`）。生效值取决于装配路径，评审与排障无法从单点确认。
- `PAYROLL_SKILL_BLOB_ROOT` 有两条独立读取路径（`LocalDiskSkillBlobStore` 的 `System.getenv` 与 `PayrollCoreConfiguration` 的属性注入），多进程部署会出现两份技能字节。

### 4.8 质量门只覆盖 H2 + 桩

- `.github/workflows/ci.yml:11-41`：无 `services:`（MySQL/Redis/ES），PR 档 `-DexcludedGroups=integration,e2e,eval`；e2e 不在 CI，且 `playwright.config.js:20-25` 跑 vite dev 而非构建产物；浏览器路径硬编码 `D:/playwright-browsers`。
- 668 个用例全部在 `payroll-tests`，其余 6 个模块零单测。
- 后果见 P0-4：真实 MySQL/Redis 语义（Lua 配额、Stream 续流、租约/fencing）与真实节点角色从未被 CI 验证。

### 4.9 两代前端并存

- 旧管理台（手写 `<table>` + 自研 toast + `api/http.js`）与新技能/KB 控制台（Element Plus + `components/ui/*` + 焦点管理）同时在线：两套请求层、两套提示层、两套确认框（`window.confirm` / `ElMessageBox` / 自研 `ConfirmDialog`）。
- 无 Pinia，`ChatView.vue` 3200 行内联 SSE 状态机，组件级测试为零。
- `kb/stores/auth.js:13-15` 用 `role_code === 'ADMIN'` 提权，与 `docs/frontend-ia.md` 自己写的「不写死角色名」相反。
- 路由守卫只判登录不判权限（`router/index.js:152-167`），HR 直接输入 `/platform/users` 可进页面，随后每个接口 403 只弹 toast。

---

## 5. P2：可延后但需记录

| 项 | 问题 | 证据 |
| --- | --- | --- |
| 沙箱零审计 | 唯一「可执行任意脚本 + 挂薪酬数据」的通道没有执行记录 | `grep -i audit payroll-sandbox` 零命中 |
| 审批指纹脆弱 | 指纹绑定 `PayrollResult` 完整 JSON，字段增减即改变指纹 → 升级后在途批次全部 409 | `ApprovalService.java:179-196` |
| 明细金额塞 JSON | `payroll_details` 无金额列，对账/统计须全表反序列化；无 `schema_version` | `V1__payroll_core.sql:172-184` |
| 零外键 | 删除靠手写级联（删会话要手工删 7 张表） | `H2ChatStore.java:165-199` |
| 内部接口可重放 | 只有 120s 时间窗，无 nonce/幂等键 | `InternalAuth.java:34-58` |
| 语义缓存跨用户 | 缓存 scope 不含 userId，开启后同部门同权限用户互相命中答复 | `PayrollChatService.java:942-954` |
| 登录页印默认口令 | 文案随 `dist` 永久发布 | `LoginView.vue:50` |
| 待办跳转断链 | `/runs?run=` 被静态 redirect 丢掉 query，审批人定位不到批次 | `TasksView.vue:37` + `router/index.js:134` |
| 文档漂移 | pom 7 模块 vs `architecture.md` 6 模块；`AGENTS.md` 说默认 H2，两模块 yml 默认 `mysql`；`/health` 实为 `/api/health` | `pom.xml:22-30`、`docs/architecture.md:34-41`、`AGENTS.md:55,77` |

---

## 6. 产品经理视角

### 6.1 价值闭环在最后一步断开，而且是数据模型层的断

邮件只做 dry-run（`DryRunEmailGateway.java:13,44`）是已知项；更关键的是员工表只存 `bank_account_masked`（`PayrollDatabase.java:445`），**即使明天接上银行代发也没有完整账号可用**。缺银行代发文件、回单/退票、员工侧工资条、个税与社保申报导出。

判断：这不是「接个 SMTP」，而是产品定义问题。要么明确收窄为**「核算与复核工具，不碰发放」**（诚实、可交付），要么补齐发放域（工时长）。现状是两边都沾一点，导致演示很完整、上线没人敢用。

### 6.2 合规已是产品风险，不再是技术风险

PII 出界、审计不可信、留存口径三条 —— 详见 §3.3 / §4.5。这三个问题决定「能不能进客户的安全评审」，而安全评审通常发生在试用第二周。

### 6.3 缺行级数据权限，薪酬内控无底线

见 §4.6。一个 HR 只能看自己负责的部门，是这个品类的基本要求，也是多部门共库的前置条件。

### 6.4 缺价值度量，等于无法证明续期理由

用量看板只回答「花了多少 token」，不回答「值不值」。缺：核算一次通过率、审批驳回率、问答命中率/误答率、人工替代率、单轮成本。产品已到「要预算」的阶段，没有这些数字就只能靠演示讲。

### 6.5 首屏与第一个链接恰好都是坏的

登录页印着默认口令（`LoginView.vue:50`）；「我的待办 → 查看」跳不到具体批次（`TasksView.vue:37`）。这两处不影响内核，但决定首次评估的印象分，修复成本各约半小时。

---

## 7. 与既有评审的关系

### 7.1 已闭环（本次不再提）

`architecture-review.md` 的 P0-2（IA 未按双主线分层）已落地：`frontend/src/router/workspaces.js` 已按 platform / payroll / governance 归属，`router/index.js:51-141` 已带 workspace 与权限；P0-4（无质量门）已落地：`ci.yml` 三 job + 测试分组。

### 7.2 已知未闭环（本次仅补充新证据，不重复结论）

`architecture-review.md`：P0-1 共享内核（只完成 ArchUnit 规则，未完成机制）、P0-3 Nacos 当配置库、P1-1 组合爆炸、P1-2 控制面大泥球、P1-3 KB 反向依赖、P1-4 模型连接、P1-5 无 SLO。
`product-prd.md` §1.4：dry-run 邮件、无行级权限、政策无版本、无 SLO、`ChatView` 膨胀、无聚合首页、devteam 无入口、交付环境门槛。

### 7.3 本次新增（14 条，不在任何既有文档中）

P0-1 城市静默回落、P0-2 停用员工复活、P0-3 累计预扣税重放、P0-4 集群下 KB 内部接口 404、P0-5 默认凭据进生产路径 + 守卫只告警、P1-1 发送无幂等、P1-2 零事务、P1-6 影子团队 fail-open、P1-7 取消不中断 + 无总预算、P2 审批指纹脆弱、P2 沙箱零审计、P2 语义缓存跨用户、P1-9 质量门只覆盖 H2 + 桩（既有文档只写了「CI 已建」）、P1-4 审计四件缺陷。

其中 **P0-1 / P0-2 / P0-3 / P0-4 属于「会算错钱或生产必坏」**，建议作为本周唯一优先级。

---

## 8. 技能与智能体：业界标准达标判定（2026-11-28 增补）

**本节性质**：目标态判定，不是缺陷登记。回答的问题是「按技能/智能体治理的 5 项优化（S1–S5）改完后，是否达到业界标准」。基线取三者：Anthropic Agent Skills 规范（SKILL.md + frontmatter、渐进披露）、MCP 工具注解与命名约定、注册表治理通行做法（版本化 / 回滚 / 写时校验 / 发布闸门 / 可观测）。**S 编号独立于 §0 的 P1-1…P1-10**，两者不可混引。

### 8.1 待落地的 5 项优化（S1–S5）

| 编号 | 优化 | 现状 | 证据 |
| --- | --- | --- | --- |
| S1 | 工具绑定声明式化：allowlist 从 Java 迁入 `AgentDefinition`，写时校验 | **2026-11-28 已落地**：原为 `allowedTools` / `flowTools` 按 skillId 硬编码（`PayrollAgentFactory.java:866-892`） | `BuiltinToolCatalog.java`、`AgentDefinition.tools`、`AgentAdminController` 写时校验、`NacosAgentRegistry` 空声明保留 |
| S2 | 智能体定义与技能同构治理：版本表、回滚、写时校验 | **2026-11-28 已落地**：原为 `agent_definition` 就地覆盖无历史（`V21__agent_definition.sql:3-9`），技能已有 append-only 版本（`V22__skill_definition.sql`） | `V25__agent_definition_versions.sql`、`AgentDefinitionRepository.versions/rollback`、`NacosAgentRegistry.versions/rollback`、`AgentAdminController` 历史与回滚端点、`AgentsView.vue` 历史面板 |
| S3 | 统一技能加载契约，去掉预注入 / 动态加载双路径 | **2026-11-28 已落地**：原活跃智能体预注入正文且不传 repo，通用路径动态加载（`PayrollAgentFactory.java:747-823`、`:514-587`） | `PayrollAgentFactory.wireSkills/skillContract`；三处装配统一走预算内注入 + 目录，`createAgent` 一律传 scoped 仓库；`DynamicSkillWiringTest` |
| S4 | 发布前 eval 闸门 | **2026-11-28 已落地**：原仅路由类回归，无发版门禁（`KbRoutingEvalTest`） | `AgentPublishGate`（上线必须绑定技能、声明工具必须有技能激活入口）、`AgentAdminController` 保存前调用、`AgentPublishGateTest` 黄金用例 |
| S5 | 可观测对齐 OTel GenAI semantic conventions | **2026-11-28 已落地**：原仅自研 Micrometer 指标，无 span（`SkillMetrics.java`） | `GenAiTelemetry`（`gen_ai.operation.name/provider.name/request.model/usage.*/agent.*/conversation.id`，semconv 1.41）、`UsageRecordingMiddleware` 每次模型调用一个 CLIENT span、`GenAiTelemetryTest` |

推荐顺序：**S2 → S1 → S3 → S4 → S5**。先补治理，声明式绑定才有落点；加载契约统一后 eval 才测得准；可观测放最后，因为它是横切面。

**进度（2026-11-28）**：S1–S5 已全部落地。

- S1：`AgentDefinition` 新增 `List<String> tools`（空 = 未声明，回退原技能映射，存量定义零行为变化）；唯一工具目录 `BuiltinToolCatalog`（与 `PayrollTools` 的 `@Tool` 反射对齐，有漂移用例）；控制台写时校验未知工具，`PUT` 支持 `?clearTools=true`，旧表单空提交保留存量声明；运期声明优先、未注册工具只告警。前端表单已暴露 tools 多选（目录来自 `GET /api/admin/agents/meta` 的 `tools`），编辑时显式 `clearTools=true` 以支持清空。
- S2：与技能同构的 append-only `agent_version`（V25，不 ALTER `agent_definition`），每次保存追加版本、`current` 恒为最大 `version_no`；回滚追加一条复用目标 payload 的新版本再切定义行，历史行不改写；升级时把存量 payload 补成 v1「升级基线」，迁移以 `IF NOT EXISTS` / `NOT EXISTS` 幂等；删除定义同时删历史。回滚仅 DB 源支持（Nacos 源明确拒绝），写路径仍走目录 revision 409。历史与回滚端点为 `GET /api/admin/agents/{id}/versions`、`POST /api/admin/agents/{id}/rollback`，回滚写审计 `agent.rollback`。
- S3：装配统一为 `wireSkills` 一个入口——按上下文预算贪心预注入技能正文（放不下的转目录行），参考文件/脚本工具在同一处按需注册；三处装配（流水线、流程节点、活跃智能体）都向 `createAgent` 传 scoped 技能仓库，`load_skill_through_path` 降为「目录中未注入技能」的兜底入口。顺带修复：自定义角色此前没有 scoped 仓库，业务工具组永远没有激活入口。`active-agent-responsibility.md` 同步改为「正文已预注入优先遵循，仅在需要时加载目录中的技能，禁止编造技能 ID」。
- S4：`AgentPublishGate` 把运行期必然失败的组合挡在保存前——上线必须绑定技能（`boundSkills` 对空绑定抛错）、声明业务工具必须绑定技能（工具组靠技能激活）；字段格式/枚举/模型连接仍由控制台原校验负责。新建/编辑表单在「在线且未选技能」时禁用保存并给出原因，后端闸门兜底。`AgentPublishGateTest` 覆盖三个内置角色放行与四类拒绝，`AgentAdminControllerTest` 覆盖保存前拦截。
- S5：`UsageRecordingMiddleware` 每次模型调用开一个 CLIENT span，属性用 semconv 1.41 的 `gen_ai.operation.name=chat`、`gen_ai.provider.name`、`gen_ai.request.model`、`gen_ai.usage.input_tokens/output_tokens`、`gen_ai.agent.id/name`、`gen_ai.conversation.id`，另加 `payroll.llm.plane`；失败写 `StatusCode.ERROR` + exception 事件，成功保持 UNSET；取消/结束多路径用同一开关保证只结束一次。只引 `opentelemetry-api`，未安装 SDK 时 no-op，Micrometer 指标与 `llm_usage` 落库不变。
- 顺带修复：遗留 H2 库的结构补建原为每条 DDL 开关一次连接，文件库快速关闭/重开时最后若干条 DDL 可能未落盘（半迁移重放用例偶发 `Table "AUDIT_LOG" not found`）；现在整段补建复用一条连接。
- 已验证：全量 `mvn -o test` **1609 用例，0 失败 0 错误，7 跳过**（S1–S5 完成后复核）；前端 `npm run build` 与 `npm test`（44 用例）通过。

### 8.2 改完后各能力域达标判定

判定分两档：**内部企业平台**（多用户、单租户、自有技能）与**对外技能市场**（第三方供给、跨运行时复用）。下表「改完」列只计 S1–S5。

| 能力域 | 业界基线 | 现状（2026-11-28 复核） | 改完 S1–S5 | 残余 |
| --- | --- | --- | --- | --- |
| 技能包格式（frontmatter、hyphen-case、description≤1024） | Anthropic Agent Skills | 达标 | 达标 | — |
| 渐进披露与提示词预算 | 同上 | 达标（目录→正文→资源；超预算可见降级；2026-11-28 三处装配统一走 `wireSkills`）`SkillPromptBudget.java`、`SkillToolGroupActivationMiddleware.java:55-76` | 达标 | — |
| 技能 ID / 路径 / 体积安全 | 规范 + 供应链最小攻击面 | 达标 `SkillIds.java:18`、`SkillFilePackage.java:173-252` | 达标 | — |
| 技能版本化与回滚 | 注册表治理 | 达标（append-only 版本 + `current_version_id` + blob 去重）`V22__skill_definition.sql` | 达标 | — |
| 工具如何绑定到智能体 | 声明式（如 Claude Code `tools:`） | 达标（2026-11-28，S1 已落地） | **达标（S1）** | 见 8.3-2 |
| 智能体定义治理 | 与技能同构 | 达标（2026-11-28，S2 已落地） | **达标（S2）** | 存量迁移成本 |
| 发布前可校验性 | 引用完整性写时校验 | 达标（2026-11-28，S3） | **达标（S3/S4）** | — |
| 发版回归闸门 | eval gate | 机制达标（2026-11-28，S4：保存闸门 + 黄金用例） | **达标（S4）** | 见 8.3-5 |
| 可观测性 | OTel GenAI conventions | 达标（2026-11-28，S5：chat span + `gen_ai.*` 属性；未装配 SDK 时 no-op） | **达标（S5）** | exporter 与采样策略属部署侧，见 8.3 末段 |
| 写操作 HITL / 沙箱 | 最小权限 + 用户确认 | 达标：`SkillRunTools.java:159-165` guard 为空即拒绝，提案不直跑 | 达标 | — |

### 8.3 改完仍不达标的五类（不在 S1–S5 覆盖范围内）

1. **工具实现仍是进程内 Java，不是 MCP-native。** `toolkit()` 组装 + `role-tools` 分组（`PayrollAgentFactory.java:1273-1294`）。技能的「可移植」止于包格式，工具未经 MCP 暴露，跨运行时复用不成立。
2. **MCP 作用域是全局环境配置，不是 per-agent。** 三处均走 `McpServerConfig.fromEnvironment()`：`PayrollAgentConfiguration.java:96`、`PayrollAgentFactory.java:544,627,784`。S1 只覆盖内置工具 allowlist，MCP 维度最小权限仍缺。
3. **可见性 / 属主不是访问控制。** `visibility` 仅用于列表过滤（`AgentAdminController.java:158-159`）与自动组队排除（`PlanValidator.java:254`、`TeamCandidateSelector.java:117`）；`createdBy` 在 backend 仅写入与展示（如 `SkillAdminController.java:1296`）。负向证据：对 `payroll-backend/web` 与 `team` 两处 `visibility`/`createdBy` 全量检索，未见按属主限定对话/技能入口的判定。「PRIVATE」是展示语义，不是隔离语义。
4. **技能发布是覆盖式写入 + revision 乐观锁，无审批 / 双人复核 / 签名。** `JdbcSkillStore.java:851-867` 只有 provenance，无 approval 状态；`NacosSkillRegistry.java:406,513` 直接 publish。供应链可信（签名、来源证明、审批流）缺失，这是公开技能市场档的硬门槛。
5. **无业务多租户 / 行级隔离。** `NacosConfigClient.java:102` 的 `tenant` 是 Nacos namespace，不是业务租户。单租户内部平台无碍，多租户部署不成立。

另有两项属「清单边界」而非实现缺陷：P2 中未排期的**提示词版本化**若不改，「提示词可审计」一栏仍不达标；S4 的**eval 闸门质量**取决于黄金集覆盖面，架构只能保证「有闸门」，保证不了「闸门有意义」——那是产品承诺。S5 同理只解决「按 semconv 产出 span」，**exporter 端点、采样率与成本归因**属部署配置：当前仓库只引 `opentelemetry-api`（未装 SDK 时 no-op），要真正上报需在运行时叠加 SDK 与 OTLP exporter。

### 8.4 判据

- **只做 S1–S5**：核心规范达标、治理层达标，**供应链与租户隔离不达标**。可对外表述为「符合主流 Agent 平台架构实践」，不可表述为「符合技能市场标准」。
- **再加 P2 提示词版本化 + 技能发布双人复核 + MCP 作用域降到 per-agent**：**内部企业平台档完整达标**。
- **公开技能市场档**：还需 MCP 化工具、签名/来源、多租户三项，属三轮量级改造，不是一轮补齐。

---

## 9. 证据索引

| 结论 | 证据位置 |
| --- | --- |
| 城市回落 | `payroll-common/.../db/H2PayrollRepository.java:213-215`；契约 `.../service/PayrollPolicyProvider.java:5`；引擎错误分支 `.../service/PayrollEngine.java:213-215` |
| 停用员工复活 | `payroll-common/.../db/H2PayrollRepository.java:278-309`（`:286`、`:307`） |
| 累计税重放 | `payroll-common/.../service/PayrollEngine.java:64-80`；政策表 `db/migration/common/V1__payroll_core.sql:134-138` |
| 集群 KB 404 | `payroll-common/.../web/NodeRoleFilter.java:27-35`；`payroll-backend/.../kb/KbSkillController.java:35`；`docker/docker-compose.yml:178,199,215,249` |
| 默认凭据 | `docker/docker-compose.yml:28-29,36,46,48,51`；`docker/docker-compose.prod.yml:22`；`application-common.yml:66`；`TopologyGuard.java:180-184` |
| 发送无幂等 | `payroll-common/.../service/PayrollRunService.java:195-208` |
| 零事务 | `@Transactional` grep 零命中；`payroll-common/.../service/ApprovalService.java:74-80,104-120` |
| 影子 fail-open | `payroll-agent/.../team/ShadowTeamService.java:241-258`；`payroll-common/.../agent/PayrollTools.java:393-400` |
| 审计四件 | `.../support/AuditSanitizer.java:17-29`；`payroll-backend/.../web/AuditController.java:204-216`；`.../web/AuditFilter.java:66-72,90-99`；`application-common.yml:252` |
| 配置漂移 | `application-common.yml:255,267`；`PayrollCoreConfiguration.java`（`@Value` 默认值） |
| 质量门 | `.github/workflows/ci.yml:11-41`；`frontend/playwright.config.js:4-6,20-25` |
| 前端两代 | `frontend/src/kb/stores/auth.js:13-15`；`frontend/src/router/index.js:152-167` |

---

## 10. 待拍板

| 编号 | 决策 | 选项 | 推荐 | 影响 |
| --- | --- | --- | --- | --- |
| D1 | 产品边界 | 收窄为核算工具 / 补齐发放域 | **收窄**，先把"不碰发放"写进非目标 | 决定 §6.1 是否立项 |
| D2 | PII 策略 | local-only / redacted / allow | **默认 redacted，第三方需显式 allow 且落审计** | 决定 §4.5 实现形态 |
| D3 | 城市政策 | 失败关闭 / 保留回落但强告警 | **失败关闭** | 决定 P0-1 是否引入破坏性行为变更（历史数据可能直接算不出） |
| D4 | 税额状态 | 新表固化 / 从明细推导 | **新表固化** | 决定 P0-3 工作量（8–10 人日） |
| D5 | 事务抽象 | 手写 Connection UoW / 引入 `TransactionTemplate` | **`TransactionTemplate`**，但需先给 `PayrollDatabase` 加 Spring 托管入口 | 决定 P1-2 改动面 |
| D6 | 行级权限模型 | 部门域 / 项目域 | **部门域 + 本人可读自己** | 决定 P1-5 与多部门共库时机 |
