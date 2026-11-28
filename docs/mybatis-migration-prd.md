# 数据库访问层迁移 MyBatis 设计方案（PRD）

> 状态：v1.1（2026-11-27，架构评审回写）。未改码。D2 / D4 / D6 / D10 仍待拍板；新增的 D13 / D14 与 §5.0 为 P0 施工前置，不可裁剪。
> 关联：[`architecture.md`](architecture.md) §5、[`mysql-redis-deploy.md`](mysql-redis-deploy.md)、[`deployment-matrix.md`](deployment-matrix.md)、[`capacity-resilience-remediation-spec.md`](capacity-resilience-remediation-spec.md)
> 一句话：把 32 个仓储类、246 条手写 SQL 从「每处自己 `prepareStatement` + 手工提交」收敛到 MyBatis，同时保持 Flyway 单一 schema 源、H2/MySQL 双引擎、现有端口与测试语义不变。

## 0. 决策一览

| 编号 | 决策点 | 推荐 | 备选 | 状态 |
|---|---|---|---|---|
| D1 | 接入形态 | `mybatis-spring-boot-starter` 3.0.5 + XML Mapper | 官方 starter 之外手写 `SqlSessionFactory`；MyBatis-Plus | 推荐 |
| D2 | 连接与引导归属 | `PayrollDatabase` 仍是唯一连接/迁移入口，新增**只读 `DataSource` 适配器**给它挂到 Spring；Flyway 留在原处；排除 Boot 的 DataSource/Flyway/SqlInit 自动配置 | 把连接池配置整体迁给 Spring Boot；或让 Boot 接管 Flyway | **需拍板** |
| D3 | 事务 | 引入 Spring `DataSourceTransactionManager`；事务分三层：① 跨 store 级联只在服务层；② **store 方法级**用于 27 个 store 内多语句事务体（2 个跨 store 级联上移服务层）；③ 单语句不加。显式 `isolation=READ_COMMITTED`。详见 §5.0.1 / §5.0.3 | 全部不加事务（与现状等价）；或每个 Mapper 方法都加 | 推荐 |
| D4 | 方言策略 | 统一写 MySQL 方言，H2（`MODE=MySQL`）作为执行器；用双引擎测试兜底；`_databaseId` 只作逃生口 | 保留 `dialect(h2Sql, mysqlSql)` 双份 SQL | **需拍板** |
| D5 | Mapper 组织 | XML + 显式 `resultMap`（记录类型用 `<constructor>`）；接口放 `*.db.mapper`；`SkillStore` 等端口不变 | 注解 SQL；或 Mapper 直接返回领域记录由自动映射推断 | 推荐 |
| D6 | 自增主键回填 | P0 用审计表实测定稿：**只允许 `useGeneratedKeys` + 主键载体参数**；兜底仅限 `<selectKey order="AFTER">`（同 Statement 同连接）；**禁止跨 Mapper 调用 `SELECT LAST_INSERT_ID()`**。upsert 场景单独实测。详见 §5.0.4 | 改成客户端生成 ID（雪花/UUID） | **需拍板**（影响 25 处写接口） |
| D7 | SQL 安全 | 只允许 `#{}`；`${}` 走白名单枚举 + 守卫测试；SQL 日志默认 INFO，凭证 Mapper 强制 WARN | 全量离线审查 | 推荐 |
| D8 | 缓存 | 关闭二级缓存；`local-cache-scope=STATEMENT`；不引入 PageHelper / MyBatis-Plus 分页 | 开启一级会话缓存 | 推荐 |
| D9 | 类型映射 | JDBC 默认：`LocalDateTime`/`BigDecimal`/`byte[]`/`Boolean`；JSON 列按字符串读写；`jdbcTypeForNull=NULL`；不依赖下划线自动映射 | 自定义 TypeHandler 套件 | 推荐 |
| D10 | 迁移与回滚 | 分批按端口替换实现（试点也直接替换），每批全量测试绿后合入；**不做运行期双实现**；回滚 = revert 该批提交 | 全部仓储加 `jdbc/mybatis` 运行期开关 | **需拍板** |
| D11 | 批处理与性能 | `BATCH` 执行器只用于已迁的批量写（用量落库、薪酬结果批量写）；种子逻辑保持手写；慢 SQL（>500ms）结构化日志；Hikari/H2 连接策略不变 | 全局 BATCH 执行器 | 推荐 |
| D12 | 依赖与离线构建 | 一次性联网 `mvn dependency:go-offline` 拉齐 MyBatis；版本在根 pom 锁定（**同时锁 `org.mybatis:mybatis` 与 `mybatis-spring` 核心版本**）；CI 缓存 `~/.m2`；之后 `mvn -o` 必须可跑 | 每次联网构建 | 推荐 |
| D13 | 异常契约 | MyBatis-Spring 把 `SQLException` 翻译为 `DataAccessException`；现有 8 处 SQLState `23` 分支全部改为**按异常类型**判定（`DataIntegrityViolationException` / `DuplicateKeyException`），禁止 message 匹配。详见 §5.0.2 | 保留 `catch (SQLException)` | **P0 前置** |
| D14 | 测试装配 | store 构造参数改为 Mapper 接口；测试用 `...db.support.PayrollMappers.forDatabase(PayrollDatabase)` 统一装配（103 处直接构造）；ArchUnit 白名单扩为 `..db.mapper..` + `..db.support..`。详见 §5.0.5 | 每个测试文件内自建 `SqlSessionFactory` | **P0 前置** |

## 1. 背景与现状证据

项目当前**没有 ORM，也没有 Spring JDBC/JdbcTemplate**：所有持久化都是「拿 `Connection` + 拼 SQL + 自己绑定参数 + 自己决定提交」。这不是零散写法，而是一套自研的引导层，迁移的难点在引导层而不在 SQL 本身。

### 1.1 现状事实

| 事实 | 证据 |
|---|---|
| 自研数据库引导：URL 归一化（H2 强制 `MODE=MySQL`）、Flyway 编程式迁移、旧库回放后基线到 V21、MySQL 才建 Hikari 池、H2 直连单写者 | [PayrollDatabase.java:62](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/PayrollDatabase.java#L62)、[:81](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/PayrollDatabase.java#L81)、[:229](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/PayrollDatabase.java#L229) |
| 连接入口只有一个方法 `connection()`；没有暴露 `DataSource` | [PayrollDatabase.java:181](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/PayrollDatabase.java#L181) |
| 方言选择器 `dialect(h2Sql, mysqlSql)` 在仓储内被调用 | [PayrollDatabase.java:194](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/PayrollDatabase.java#L194)、[JobLeaseStore.java:120](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JobLeaseStore.java#L120)、[H2PayrollRepository.java:255](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2PayrollRepository.java#L255) |
| 43 个主源码文件依赖 `PayrollDatabase`（common 30 / backend 11 / agent 2） | `grep -rl "PayrollDatabase"` 实测 |
| 主源码 246 条 SQL 字面量，405 处 `prepareStatement/createStatement` | `grep` 实测（2026-11-27） |
| 事务全靠手写：**13 个 store 文件 / 23 处 `setAutoCommit(false)`**（拆开统一事务辅助后共 29 个多语句方法，其中 2 个跨 store 级联；另加引导种子 1 处、一次性迁移工具 `H2ToMysqlMigration` 1 处）、19 处 `rollback()`、0 处 `@Transactional` | [H2ChatTurnStore.java:62](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatTurnStore.java#L62)、[JdbcSkillStore.java:1577](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1577)、`grep setAutoCommit` 实测（2026-11-27） |
| 事务由 `Connection` 参数跨类携带：`H2ChatStore.delete` 把同一连接传给另外 3 个 store 的 `static deleteForSession`，级联 7 张表 | [H2ChatStore.java:169-171](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L169)、[H2ChatStore.java:868-870](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L868)、[ChatAttachmentStore.java:284](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/ChatAttachmentStore.java#L284) |
| 连接复用比：**338 次 `connection()` / 405 条语句 ≈ 1.20 语句每连接**（common 236/297 + backend 102/108） | `grep "database.connection()"` 与 `prepareStatement|createStatement` 实测 |
| 约束冲突按 **SQLState 前缀 `23`** 分支的共 8 处（7 处 `getSQLState()` + 1 处 `catch SQLIntegrityConstraintViolationException`） | [JobLeaseStore.java:126](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JobLeaseStore.java#L126)、[H2ChatStore.java:511](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L511)、[H2ChatTurnStore.java:277](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatTurnStore.java#L277)、[H2UserProfileStore.java:761](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2UserProfileStore.java#L761)、[JdbcAgentDefinitionRepository.java:312](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JdbcAgentDefinitionRepository.java#L312)、[ModelCredentialStore.java:104](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/ModelCredentialStore.java#L104)、[JdbcSkillStore.java:2145](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L2145) |
| 租约用「UPDATE 过期行 → 未命中则 INSERT → SQLState `23` 视为未抢到」，且时间表达式取库时钟 | [JobLeaseStore.java:28](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JobLeaseStore.java#L28) |
| 25 处 `RETURN_GENERATED_KEYS`，对应 32 张自增表（共 53 处建表语句） | [H2AuditStore.java:38](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2AuditStore.java#L38)、[H2ChatStore.java:64](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L64) |
| 仓储类共 32 个：common `db` 20 个（另 2 个为引导/迁移工具）+ skill 3 个 + backend kb 8 个 + retention 1 个 | [PayrollCoreConfiguration.java:985](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/config/PayrollCoreConfiguration.java#L985) 等 |
| 仓储 Bean 全部以 `PayrollDatabase` 构造注入 | [PayrollCoreConfiguration.java:157](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/config/PayrollCoreConfiguration.java#L157)、[:473](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/config/PayrollCoreConfiguration.java#L473)、[:729](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/config/PayrollCoreConfiguration.java#L729) |
| 66 个测试文件依赖 `PayrollDatabase` / `inMemory(...)`；**测试侧 103 处直接 `new XxxStore(database)`，分布在 77 个文件**，无共享 fixture | [ArchitectureRulesTest.java](file:///D:/workspace/agentscope-payroll-agent/payroll-tests/src/test/java/io/agentscope/examples/payroll/architecture/ArchitectureRulesTest.java)、[JobLeaseStoreTest.java:40](file:///D:/workspace/agentscope-payroll-agent/payroll-tests/src/test/java/io/agentscope/examples/payroll/jobs/JobLeaseStoreTest.java#L40)、`grep "new \w+(Store|Repository)\(database"` 实测 |
| 架构文档明确要求「SQL 需双方言可执行」 | [architecture.md:88](file:///D:/workspace/agentscope-payroll-agent/docs/architecture.md#L88) |
| 依赖清单没有任何 MyBatis / spring-jdbc / spring-tx；本机 `~/.m2` 无 `org/mybatis` | [payroll-common/pom.xml](file:///D:/workspace/agentscope-payroll-agent/payroll-common/pom.xml)、`ls ~/.m2/repository/org/mybatis` 失败 |
| Flyway V1–V24 共 24 个迁移，H2 与 MySQL 共用 | `payroll-common/src/main/resources/db/migration/common/` |

### 1.2 方言探针（本次实测）

用 H2 2.3.232 `MODE=MySQL` 直接执行（不经过应用），结论：**大部分「双方言」差异其实不存在**。

| 语句 | 结果 |
|---|---|
| `TIMESTAMPADD(SECOND, n, CURRENT_TIMESTAMP)` | 通过（MySQL 原生，H2 兼容模式也支持） |
| `SELECT 1 FROM DUAL` | 通过 |
| `INSERT ... ON DUPLICATE KEY UPDATE v='x'` | 通过 |
| `INSERT ... ON DUPLICATE KEY UPDATE v=VALUES(v)` | 通过（MySQL 8.0.20 起已弃用，新代码不用） |
| `INSERT IGNORE` / `REPLACE INTO` | 通过 |
| `MERGE INTO ... KEY(id)` | 通过（H2 原生，迁移后不再需要） |
| `LIMIT ? OFFSET ?` | 通过 |
| `JSON` 列、`TIMESTAMP ... ON UPDATE CURRENT_TIMESTAMP` | 通过 |
| `SELECT LAST_INSERT_ID()` | 通过（同库 `IDENTITY()` 在本机 H2 2.3.232 的 MySQL 模式下不可用） |

因此 D4 的主路线是「统一 MySQL 方言」，不是「双份 SQL 搬运」。MySQL 侧仍需在 P0 末尾跑一遍真实库闸门（见 §5.2）。

## 2. 目标与非目标

**目标**

1. 新代码只写一份 SQL，按 Mapper 组织，参数一律 `#{}` 绑定，不再手写 `ResultSet` 取值。
2. schema 唯一来源保持 Flyway（`db/migration/common`），MyBatis **不建表、不改表、不做 DDL**。
3. 现有端口（`SkillStore`、`LlmUsageStore`、仓储类公开方法）与业务行为不变，66 个 DB 测试**断言**不改；构造装配按 §5.0.5 统一调整。
4. 事务边界显式化：多语句写单元要么整体成功，要么整体回滚；不把事务包到模型调用、SSE 推送、HTTP 调用外面。
5. H2（本地/测试）与 MySQL（部署）执行同一份 SQL，双引擎验证有自动化兜底。

**非目标**

- 不换数据库、不换连接池、不引入分库分表/读写分离。
- 不改 REST 契约、前端、提示词、智能体编排。
- 不迁移 `H2ToMysqlMigration`（一次性数据搬运工具，保留手写 JDBC 更直观）。
- 不改种子数据逻辑（`PayrollDatabase` 内 `seed*` 方法 P0–P4 期间保持原样，P4 再评估是否简化方言分支）。
- 不引入 MyBatis-Plus、PageHelper、JPA。

## 3. 使用场景

| 编号 | 角色 | 场景 | 现在的痛点 |
|---|---|---|---|
| S1 | 后端开发 | 新增一张表 + 一组查询 | 复制一个 `H2XxxStore` 的样板：拼 SQL、绑参、手动映射、手动 try-with-resources，200 行起步 |
| S2 | 后端开发 | 加一个可选过滤条件 | 拼 `StringBuilder where` + `List<Object> params`，容易漏空格/漏参数 |
| S3 | 后端开发 | 两个写操作必须同成败（如轮次创建 + 消息落库） | 自己 `setAutoCommit(false)`/`commit`/`rollback`，异常路径易漏 |
| S4 | 运维/测试 | 本地 H2 通过、部署 MySQL 报错 | 方言靠人肉记忆 `DATEADD`/`TIMESTAMPADD`，仓储层只有 6 处显式分支（另有种子 SQL），其余靠「碰巧兼容」 |
| S5 | 安全/审计 | 排查一条 SQL 的参数 | 没有统一 SQL 日志；手工日志里可能带凭证字段 |
| S6 | 架构 | 限制「业务层不许直接碰 JDBC」 | 目前只能靠评审，没有可执行边界 |

## 4. 方案对比

| 方案 | 做法 | 改造量 | 优点 | 代价 / 风险 |
|---|---|---|---|---|
| **A 维持手写 JDBC** | 现状 | 0 | 零风险、零依赖 | S1–S6 全部保留；246 条 SQL 的维护成本随表增长继续上升 |
| **B MyBatis + 保留自研引导**（推荐） | starter 接入；`PayrollDatabase` 只多暴露一个 `DataSource` 适配器；仓储逐个改成 Mapper | 24–28 人日，分 5 批 | SQL 与代码分离、动态 SQL 可控、事务交给 Spring、改造可分批回滚；引导层/Flyway/连接池零改动 | 需要一次性引入依赖；XML 数量多；事务语义要从「手写」切换到「声明式」，且 29 个多语句事务体需先登记（§5.0.1） |
| **C MyBatis-Plus** | 在 B 之上加 BaseMapper/Wrapper | 22–27 人日 | 单表 CRUD 更省 | 项目 SQL 多为多表/聚合/动态过滤，Wrapper 表达力不足反而混两套；版本与 Spring Boot 3.5 的适配风险额外承担 |
| **D Spring Data JDBC / JPA** | 实体映射 + Repository | 30+ 人日 | 声明式 | 现有 SQL 大量手写优化（`LIMIT/OFFSET`、upsert、批量删除），迁移等于重写查询计划；H2/MySQL 差异被 ORM 掩盖更难排查 |

**推荐 B。** 理由：项目已经有清晰的「端口 + 实现」结构（`SkillStore`、`LlmUsageStore` 等），MyBatis 只需替换实现层；而 `PayrollDatabase` 承担了 Flyway/H2 单写者/池化三件不能丢的能力，重写引导层的收益远小于风险。

## 5. 推荐方案

### 5.0 事务与异常契约（P0 施工前置）

> 本节由 2026-11-27 架构评审回写。五小节都是**阻断项**：不落地就进 P1，第一批（`JobLeaseStore` / `DevTaskStore`）会出现行为回归。

#### 5.0.1 事务分三层，不是两层

原 D3 假设「手写事务只在服务层多语句单元」，实测不成立：**13 个 store 里有 23 处 `setAutoCommit(false)`**，另有 1 处跨 store 级联**根本没开事务**（`H2ChatStore.clear`）；再把 `JdbcSkillStore` 用统一 `inTransaction` 承载的 6 个事务体拆开，实际是 **29 个多语句方法**（27 个 L2 + 2 个 L1）。`PayrollDatabase.initialize()` 的种子事务与 `H2ToMysqlMigration` 各 1 处属于引导层/一次性工具，不进 MyBatis，不登记。

| 层 | 适用 | 标注位置 | 判据 |
|---|---|---|---|
| **L1 跨 store 级联** | 一个逻辑删除横跨多个 store | 服务层 `@Transactional` | 只有 `H2ChatStore.delete` / `clear` |
| **L2 store 方法级** | 23 处 `setAutoCommit(false)` 拆出的 27 个事务体 | store 公开方法 | 一个方法内多条语句必须同成败 |
| **L3 单语句** | 其余全部 | 不标注 | Mapper 一条语句，autocommit |

**L1 的两个样本**：`H2ChatStore.delete` 与 `clear` 都把同一个 `Connection` 传给 `H2ActionProposalStore` / `H2ChatTurnStore` / `ChatAttachmentStore` 的 `static deleteForSession`，再叠加工具追溯、消息、团队数据和会话本身（[delete:169-171](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L169)、[clear:868-870](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L868)）。这是「事务由参数携带」，MyBatis 无法表达，必须**上移为 `ChatDataService.@Transactional`，由它注入 4 个 Mapper**；4 个 `deleteForSession` 改成 Mapper 方法，`static + Connection` 签名删除。这是全项目仅有的「store 公开方法搬迁」，在 P2 单独一个提交，先补测试再改。

**`clear` 的现状是缺陷，不是等价基线**：它没有 `setAutoCommit(false)`，级联中的每条 `DELETE` 各自提交（[:866](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L866)），中途失败会留下半清空的会话。上移到 L1 后语义由「部分成功」变为「全成全败」，这是**有意的行为变更**，必须由专门的失败注入用例断言，并写进交接文档。

**事务登记表（29 行 = 27 L2 + 2 L1，P0 首日产物）**：

| # | 批次 | store | 方法（行） | 事务内容 | 迁移落点 |
|---|---|---|---|---|---|
| 1 | P1 | `H2ChatFeedbackStore` | `upsert`（[38](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatFeedbackStore.java#L38)） | 先查存在性 → INSERT / UPDATE | L2；查询无 `FOR UPDATE`，结论仍是「写后可读」 |
| 2 | P1 | `DevTaskStore` | `replacePlan`（[64](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/DevTaskStore.java#L64)） | `DELETE` 全量 → `executeBatch` 插入 | L2；**删了插不进 = 计划被清空** |
| 3 | P1 | `DevArtifactStore` | `save`（[92](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/DevArtifactStore.java#L92)） | `nextVersion` SELECT → INSERT（自增主键） | L2 |
| 4 | P2 | `H2ChatTurnStore` | `createWithUserMessage`（[62](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatTurnStore.java#L62)） | INSERT 用户消息 → INSERT 轮次 → UPDATE 会话时间 | L2 |
| 5 | P2 | `H2ChatTurnStore` | `nextFencing`（[197](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatTurnStore.java#L197)） | SELECT 最大值 → UPDATE/INSERT 计数器 → SELECT 回读；`finally` 还原 autoCommit | L2；行锁必须跨语句持有 |
| 6 | P2 | `H2ChatStore` | `appendAssistant`（[413](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L413)） | INSERT 消息 → 提案 / 追溯 / 引用 | L2 |
| 7 | P2 | `H2ChatStore` | `appendTraces`（[576](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L576)） | 循环 INSERT 追溯 | L2 |
| 8 | P2 | `H2ChatStore` | `delete`（[167](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L167)） | 3 个 `deleteForSession` + 工具追溯 / 消息 / 团队 / 会话 | **L1 上移 `ChatDataService`** |
| 9 | P2 | `H2ChatStore` | `clear`（[866](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L866)） | 与 `delete` 同构，**现状无事务** | **L1 上移**；行为由「部分成功」改为「全成全败」 |
| 10 | P2 | `ChatAttachmentStore` | `bind`（[248](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/ChatAttachmentStore.java#L248)） | 循环条件 UPDATE（`status=READY` → 已绑定） | L2 |
| 11 | P2 | `H2AgentTeamStore` | `markPrimary`（[138](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2AgentTeamStore.java#L138)） | SELECT 运行 → 清同消息主标记 → 置本次主标记 | L2 |
| 12 | P2 | `H2PayrollRunStore` | `createDraft`（[49](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2PayrollRunStore.java#L49)） | INSERT 批次（自增）→ 明细 → 失败行 | L2 |
| 13 | P2 | `H2PayrollRunStore` | `recordEmailResults`（[230](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2PayrollRunStore.java#L230)） | 明细 UPDATE 批量 → `email_deliveries` INSERT 批量 | L2 |
| 14 | P2 | `H2ApprovalStore` | `createFlow`（[122](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ApprovalStore.java#L122)） | INSERT 链（自增）→ 原子替换节点 | L2 |
| 15 | P2 | `H2ApprovalStore` | `updateFlow`（[156](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ApprovalStore.java#L156)） | UPDATE 链 → 原子替换节点 | L2 |
| 16 | P2 | `H2ApprovalStore` | `deleteFlow`（[184](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ApprovalStore.java#L184)） | DELETE 节点 → DELETE 链（0 行抛业务异常） | L2 |
| 17 | P2 | `H2AdminRepository` | `updateDepartment`（[236](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2AdminRepository.java#L236)） | UPDATE 员工部门名 → UPDATE `departments` | L2 |
| 18 | P2 | `H2UserProfileStore` | `upsert`（[145](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2UserProfileStore.java#L145)） | upsert 条目 → 回读 | L2 |
| 19 | P2 | `H2UserProfileStore` | `purge`（[204](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2UserProfileStore.java#L204)） | DELETE 条目 → DELETE 抽取记录 | L2 |
| 20 | P2 | `H2UserProfileStore` | `complete`（[480](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2UserProfileStore.java#L480)） | 循环 upsert 条目 → 置抽取记录 DONE | L2 |
| 21 | P2 | `JdbcAgentDefinitionRepository` | `save`（[91](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JdbcAgentDefinitionRepository.java#L91)） | `lockRevisions` → INSERT / UPDATE | L2 |
| 22 | P2 | `JdbcAgentDefinitionRepository` | `delete`（[126](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JdbcAgentDefinitionRepository.java#L126)） | `lockRevisions` → DELETE | L2 |
| 23 | P2 | `JdbcAgentDefinitionRepository` | `importIfEmpty`（[168](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JdbcAgentDefinitionRepository.java#L168)） | `lockRevisions` → 循环 INSERT | L2 |
| 24 | P4 | `JdbcSkillStore` | `rollback`（[1053](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1053)） | 锁行 → 追加版本 → 重建清单 | L2 |
| 25 | P4 | `JdbcSkillStore` | `replaceFiles`（[1126](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1126)） | 锁行 → 重建清单与 blob 指针 | L2 |
| 26 | P4 | `JdbcSkillStore` | `deleteFile`（[1159](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1159)） | 锁行 → 删清单行 → 刷新定义 | L2 |
| 27 | P4 | `JdbcSkillStore` | `writeFile`（[1196](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1196)） | 锁行 → 删旧行 → 写新行 → 刷新定义 | L2 |
| 28 | P4 | `JdbcSkillStore` | `delete`（[1303](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1303)） | 删清单 → 删版本 → 删定义 | L2 |
| 29 | P4 | `JdbcSkillStore` | `write`（[1354](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1354)，私有） | 版本行 + 清单 + 派生列；入口 `create`(:938/:944)、`update`(:969/:980/:991/:1007)、`importProject`(:1224/:1230)、`importProjectForMigration`(:1244) | L2，但**注解不能落在私有方法上**，见下 |

**`JdbcSkillStore` 是唯一需要先拆再标的一行**：事务体里调用的 `writeResources` 会执行 `blobStore.put`（[JdbcSkillStore.java:1803](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1803)），即**当前文件 IO 就在事务内**，回滚后再用 `cleanupBlobs` 补偿。这与 §5.0.3「事务内只允许 DB 调用」冲突；而 `@Transactional` 加在私有 `write` / `importProjectInternal` 上又不会经过代理。P4 开工前必须先拆成「公开入口先落盘 → 独立的 `@Transactional` 组件只登记指针」，拆完后的公开方法清单会多于本表的 6 行，届时修订本表与白名单测试。

**登记表落成测试常量，不从文档里读**：新增测试侧 `TransactionRegistry`（每行带 `batch` 与 `migrated` 标志），`TransactionWhitelistTest` 只比对 `migrated=true` 的行。P0 阶段集合为空——试点两个仓储都是 L3，且**此时给 store 加注解是错的**：Spring 事务绑定的是 `PayrollDatabaseDataSource` 的连接，而 store 还在用 `PayrollDatabase.connection()`（H2 走 `DriverManager` 另开连接），注解不生效，还会白占一条连接。注解必须与各仓储的 Mapper 改造同提交。

**白名单测试**（替代「靠评审」）：扫描 `@Transactional` 注解，方法与上表**逐条相等**即通过；多一个（比如有人顺手给 Mapper 加）或少一个都失败。同时静态检查：L1/L2 方法体内不得出现 `ExecutorService` / SSE 发送 / HTTP 客户端 / 文件 IO。

**注意**：`JobLeaseStore` 今天**没有**事务（`tryAcquire` 的 UPDATE 与 INSERT 各自 autocommit），迁移后保持 L3，语义等价——这点由 P1 首项验证。

#### 5.0.2 异常契约：按类型，不按 SQLState

`SqlSessionTemplate` 通过 `MyBatisExceptionTranslator` 把 `SQLException` 翻成 `DataAccessException`。现有 8 处 SQLState `23` 分支必须同步改写，否则 `catch (SQLException)` 连编译都过不去：

| 位置 | 现语义 | 迁移后判定 | 行为变化 |
|---|---|---|---|
| [JobLeaseStore.java:126](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JobLeaseStore.java#L126) | 主键冲突 = 未抢到，返回 `false` | `DataIntegrityViolationException` → `false` | 无（必须保持） |
| [H2ChatStore.java:511](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatStore.java#L511) | 终态消息幂等 | `DataIntegrityViolationException` | 有效 |
| [H2ChatTurnStore.java:277](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2ChatTurnStore.java#L277) | 轮次幂等 | 同上 | 有效 |
| [H2UserProfileStore.java:761](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/H2UserProfileStore.java#L761) | 画像 upsert | 同上 | 有效 |
| [JdbcAgentDefinitionRepository.java:312](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/JdbcAgentDefinitionRepository.java#L312) | 定义 upsert | 同上 | 有效 |
| [JdbcSkillStore.java:2145](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L2145) | 技能冲突 | 同上 | 有效 |
| [ModelCredentialStore.java:104](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/ModelCredentialStore.java#L104) | `catch (SQLIntegrityConstraintViolationException)` 后转 UPDATE | `DataIntegrityViolationException` | 有效 |

两条硬规定：

1. **用 `DataIntegrityViolationException`，不用 `DuplicateKeyException` 单独兜底。** 现代码是 `23xxx` 全类（含 `23502` not-null）。只 catch `DuplicateKeyException` 会把原本被吞掉的非空 bug 抛出来——这是修复，但是**语义变更**，必须在上表登记并在对应测试里断言语义。
2. **必须有 `MapperExceptionMappingTest`**：在 H2 与 MySQL 上各触发一次重复键，断言**具体异常类型**。`SQLErrorCodeSQLExceptionTranslator` 对 H2 2.3.232 的错误码映射若未命中会退化成 `UncategorizedSQLException`，不能靠推断。

禁止 `e.getMessage().contains(...)` 之类的判定；消息文本跨引擎不稳定。

**实测结果（P0-8，2026-11-28，H2 2.3 内存库）**：`MapperExceptionMappingTest` 用测试夹具 `LeaseProbeMapper`（XML 放在 `classpath:constraint-probe/`，不进 `MapperSqlGuardTest` 的生产 Mapper 扫描）向 `job_lease` 插入两次同名 `job_name`，第二次抛 `DuplicateKeyException`，且 `assertInstanceOf(DataIntegrityViolationException.class)` 通过——即 H2 上重复键落在 `DataIntegrityViolationException` 分支，业务侧按上表 catch 父类型即可。

**MySQL 实测（P0-10，2026-11-28）**：同一探针在 MySQL 8.0 + connector 9.5 上重复执行，`[MYSQL-GATE] duplicateKey=org.springframework.dao.DuplicateKeyException`，`assertInstanceOf(DataIntegrityViolationException.class)` 同样通过。**两引擎行为一致，类型映射表可直接作为业务判定依据**；闸门细节见 §5.2.2。

#### 5.0.3 隔离级别与锁

- 显式 `@Transactional(isolation = Isolation.READ_COMMITTED)`。MySQL InnoDB 默认 REPEATABLE READ、H2 默认 READ COMMITTED；不写 isolation 时同一方法内二次读在两引擎看到不同快照，双引擎测试会**掩盖**差异。
- `job_lease` 只有 `PRIMARY KEY(job_name)`，`expires_at` 无索引（[V23__perf_indexes_and_retention.sql:27](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/resources/db/migration/common/V23__perf_indexes_and_retention.sql#L27)）。租约走 PK 等值锁，行数少，风险可控；但**任何把租约 UPDATE 包进长事务的写法都会延长单行 X 锁**，明确禁止。
- H2 文件库是单写者：一个写事务持锁到提交，期间其他写入排队。L1/L2 事务内只允许 DB 调用，目标 <50ms；`default-statement-timeout=30`（秒，仅约束单语句）不覆盖事务持锁时长，需在 P0 观测 `LOCK_TIMEOUT` 与排队情况。
- 修正 §5.2 的表述：适配器**自身**不改 autoCommit；进入事务路径后 `DataSourceTransactionManager.doBegin` 会 `setAutoCommit(false)`，提交后还原。

#### 5.0.4 主键回填的作用域

D6 的模板收紧为：

1. 首选 `<insert useGeneratedKeys="true" keyProperty="id">`；
2. 兜底只允许 `<selectKey order="AFTER">`（与 INSERT 同一 `Statement`/连接）；
3. **禁止**把 `SELECT LAST_INSERT_ID()` 做成独立 Mapper 方法——无事务时每次调用取不同连接，H2 返回 `0`、MySQL 可能返回别的会话值；
4. upsert 场景（`ModelCredentialStore` 的 insert-or-update、`JdbcSkillStore` blob upsert）单独实测结论（P0-10，2026-11-28）：MySQL 8.0 + connector 9.5 在 `ON DUPLICATE KEY UPDATE` 的**更新分支**会返回既有行 id（`departments` 探针 `updateBranchRows=2 updateBranchKey=9`），但这是驱动/服务端行为而非 SQL 标准，**模板不依赖它**；两个真实 upsert 目标 `model_credential`（PK `credential_id` VARCHAR）与 `skill_blob`（PK `sha256` CHAR(64)）都是业务主键，id 由调用方给定，与自增回填无关。详见 §5.2.2。
5. **构造器注入对象的 `javaType` 必须用基本类型别名 `_long` / `_int` / `_boolean`。** `AuditEntry`、`LlmUsageRecord` 是 record，MyBatis 走 `DefaultResultSetHandler.createParameterizedResultObject` → `DefaultObjectFactory.instantiateClass`，用 `getDeclaredConstructor(精确类型)` 匹配；`javaType="long"` 经别名解析成 `java.lang.Long`，匹配不到 `(long, ...)` 构造器，直接抛 `ReflectionException: ... NoSuchMethodException: <init>(java.lang.Long, ...)`。试点实测：`LlmUsageStoreTest` 4 个用例全红，改成 `_long`/`_int`/`_boolean` 后全绿。包装类型字段（如 `Long latencyMs`）仍用普通别名。
6. 主键回填实测（H2 + MySQL）：试点两表 `audit_log`、`llm_usage` 用 `useGeneratedKeys="true" keyProperty="id"` 在两引擎均正常回填（MySQL 闸门 `auditId=3150`、`usageId=1161`）；载体是可变的 `AuditInsert` / `LlmUsageInsert`，不是 record。upsert 更新分支结论见上一条。

#### 5.0.5 测试装配

现状：**103 处 `new XxxStore(database)`，分布在 77 个测试文件**，无共享 fixture。store 改成注入 Mapper 后这些全部编译失败——「66 个测试不改断言」不等于「不改构造代码」，工作量必须计入。

方案：

- store 构造参数从 `PayrollDatabase` 改为 Mapper 接口（store 仍不 import `org.apache.ibatis`，边界不破）；
- 新增 `...db.support.PayrollMappers.forDatabase(PayrollDatabase)`，内部建 `SqlSessionFactory` + `SqlSessionTemplate`，测试用 `PayrollMappers.forDatabase(db).chatStore()` 之类取装配好的 store；
- ArchUnit 的 ibatis 白名单从单一 `..db.mapper..` 扩为 `..db.mapper..` + `..db.support..`（显式两包，其他包仍禁止）；
- 77 个测试文件的改动是机械替换，但要与对应批次同提交，避免「半迁移」状态。

### 5.1 总体结构

```
Controller / Service / Agent
        │  （只依赖端口与领域对象，不 import org.apache.ibatis）
        ▼
SkillStore / LlmUsageStore / H2XxxStore（保留类名与公开方法）
        │
        ▼
*.db.mapper.XxxMapper（接口）  ←→  resources/mapper/<module>/XxxMapper.xml
        │
        ▼
Spring DataSourceTransactionManager
        │
        ▼
PayrollDatabaseDataSource（新增，只读适配器）
        │
        ▼
PayrollDatabase.connection()  ── MySQL: Hikari 池 ── H2: DriverManager 单写者
        │
        └── Flyway（不变，启动时迁移 + 种子）
```

三条硬边界：

1. **只有 `*.db.mapper` 与 `*.db.support` 两个包可以 import `org.apache.ibatis.*`**（ArchUnit 强制）；`..service..` 与 `..web..` 不得依赖 `..db.mapper..`（防绕过 store 状态机）。
2. Mapper 方法 = 一条语句；跨语句一致性按 §5.0.1 的三层执行（L1 服务层 / L2 store 方法 / L3 不加），并用白名单测试锁定。
3. `PayrollDatabase` 仍是连接与迁移的唯一拥有者；MyBatis 只消费它给的连接。

### 5.2 P0：基础设施与试点（5 人日）

**依赖**（根 pom `dependencyManagement` 锁版本，common 引入）：

```xml
<dependency>
  <groupId>org.mybatis.spring.boot</groupId>
  <artifactId>mybatis-spring-boot-starter</artifactId>
  <version>3.0.5</version>
</dependency>
```

Boot 3.5 对应 starter 3.0.x（3.0.5 已在 Maven Central 核实）；4.x 面向 Boot 4，不要混用。starter 会带入 `spring-jdbc` / `spring-tx`（此前项目没有），这是引入 `@Transactional` 的前提。

**`PayrollDatabaseDataSource`**（新增，`payroll-common/.../db`）：

- `getConnection()` / `getConnection(user,pwd)` 直接委托 `PayrollDatabase.connection()`；
- `getLogWriter`/`getLoginTimeout` 等元数据方法返回无害默认值；`unwrap` 仅支持自身；
- **非事务路径**不改变 autoCommit：MySQL 归还 Hikari，H2 直接关闭，与现状一致；**事务路径**由 `DataSourceTransactionManager` 设置 `autoCommit=false` 并在提交后还原，适配器不干预。

**配置**（`application-common.yml`）：

```yaml
spring:
  autoconfigure:
    exclude:
      # 已有 4 项保留，新增：
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
      - org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
      - org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration
management:
  health:
    db:
      enabled: false   # /actuator/health 形状不变，readiness 仍由 PayrollReadinessHealthIndicator 判定
mybatis:
  mapper-locations: classpath*:mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: false   # 全部显式 resultMap，禁止隐式推断
    jdbc-type-for-null: 'NULL'
    local-cache-scope: statement          # 不开一级会话缓存，避免跨语句脏读
    default-statement-timeout: 30
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
```

> 注意：一旦容器里出现 `DataSource` Bean，Boot 的 `FlywayAutoConfiguration` 会尝试对默认位置迁移。必须排除，否则会和 `PayrollDatabase.migrate()` 抢同一张 `flyway_schema_history`。

**Java 装配**（`PayrollCoreConfiguration`）：`DataSource` Bean、`DataSourceTransactionManager` Bean、`@MapperScan` 扫描 `...db.mapper`（`...skill.mapper` 随 P4 追加）；backend 侧单独一个 `KbMybatisConfiguration` 扫描 `...kb.mapper`（`...retention.mapper` 随 P1 追加），避免 common 反向依赖 backend。Mapper XML 随各自模块放在 `src/main/resources/mapper/<module>/`，由 `classpath*:` 统一加载。

两条装配约束：

- **统一只用 `@MapperScan`，不要给接口加 `@Mapper`**：`MybatisAutoConfiguration` 会自动扫描 `@SpringBootApplication` 包下的 `@Mapper`，两者混用会重复注册、启动即失败。
- **启动断言 XML 全量加载**：`SqlSessionFactory.getConfiguration().getMappedStatements()` 的语句数必须等于预期值。XML 漏加载是静默的，只有调用时才炸。
- 测试侧统一走 `...db.support.PayrollMappers`（§5.0.5），不在测试里各自建 `SqlSessionFactory`。

**守卫与骨架**：

| 测试 | 作用 |
|---|---|
| `MapperSqlGuardTest` | 扫描 `resources/mapper/**` 与 Mapper 注解：出现 `${` 必须在白名单枚举内；出现 `<cache` 直接失败；`SELECT *` 直接失败；出现 `<association select=` / `<collection select=`（嵌套查询，N+1 源）直接失败 |
| `MapperXmlParseTest` | 启动 `SqlSessionFactory` 并解析全部 XML，防止 XML 语法/namespace 错误到运行期才暴露；同时断言 `MappedStatement` 数量与预期一致 |
| `MapperSqlParityTest` | 对每条 `MappedStatement` 取 `BoundSql`，在 H2（`MODE=MySQL`）执行一次；MySQL 闸门由部署矩阵脚本执行 |
| `MapperExceptionMappingTest` + `MySQLGateTest` | H2 由 `MapperExceptionMappingTest`、MySQL 由 `MySQLGateTest`（默认 assume-skip）各触发一次重复键，断言 `DataAccessException` 的**具体子类型**（§5.0.2）；两引擎结果见 §5.2.2 |
| `TransactionWhitelistTest` | 全项目 `@Transactional` 方法集合与 §5.0.1 登记表逐条相等；方法体内不得出现 Executor/SSE/HTTP/文件 IO |
| ArchUnit 新规则 | 非 `..db.mapper..` / `..db.support..` 包不得依赖 `org.apache.ibatis..`；`..db.mapper..` 不得依赖 `..service..`；`..service..` / `..web..` 不得依赖 `..db.mapper..` |
| resultMap 列覆盖（P2） | DOM 解析 XML 收集 `<id>/<result>/<arg column>`，与 H2 执行后的 `ResultSetMetaData` 列集合比对，防「漏写一列 = 静默 null」 |

**试点（同一提交内完成，带可回退性）**：`H2AuditStore`（4 条 SQL、无事务语义、有现成测试）+ `H2LlmUsageStore`（叶子表，由 `LlmUsageRecorder` 的单线程有界队列异步写入，天然无事务）。以这两个类验证 D6 主键回填、`LocalDateTime` 映射、`LIMIT/OFFSET` 分页和 Boot 自动配置排除是否干净。

- `H2AuditStore` 的写入失败被 `AuditService` 的 `catch (RuntimeException)` 吞掉（[AuditService.java:172](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/service/AuditService.java#L172)），`DataAccessException` 是运行时异常，因此**审计的失败行为不变**——这一点已在评审中核对，风险表按「已验证」记录。
- `H2LlmUsageStore` 走异步线程，正好验证「无事务、每调用一条连接」路径的连接获取次数。

**P0 收尾必须写回本文档**：D6 模板（含 upsert 结论，见 §5.0.4）、§5.0.2 的异常类型实测结果、以及三项基线指标（Mapper 调用次数、`connection()` 获取次数、语句 P95 耗时）。没有基线，「性能中性」不可证伪，后续批次不得合入。

**MySQL 闸门**：本机无 docker CLI，按 [`mysql-redis-deploy.md`](mysql-redis-deploy.md) 的 compose / 部门库执行一次「启动 + 全量测试 + 审计写入 + 分页查询」；未通过不得进入 P1。**已于 2026-11-28 对本机 MySQL 8.0 通过，证据见 §5.2.2。**

**P0 施工顺序（有依赖，不能并行乱序）**：

| 步 | 内容 | 完成判据 |
|---|---|---|
| P0-1 | 根 pom 锁 `mybatis-spring-boot-starter` / `org.mybatis:mybatis` / `mybatis-spring`；联网 `dependency:go-offline`；`mvn -o test` 记录迁移前基线（以当次实际输出为准，须全绿） | 断网后 `mvn -o test` 与基线一致 |
| P0-2 | `TransactionRegistry` 测试常量（29 行，`migrated=false`）+ `TransactionWhitelistTest` | 集合为空时绿；故意多加一个注解即失败 |
| P0-3 | `PayrollDatabaseDataSource` 适配器 + `connection()` 获取计数（供 1.2× 指标） | 适配器自测 + 计数在 /actuator 或日志可见 |
| P0-4 | `DataSourceTransactionManager` + 排除 Boot 的 4 项自动配置 + H2 启动冒烟 | H2 启动、Flyway 只跑一次、健康检查形状不变 |
| P0-5 | `@MapperScan` + `SqlSessionFactory`（XML 位置、`defaultStatementTimeout=30`、4 类敏感包 WARN）+ 启动断言 `MappedStatement` 数量 | 启动通过；数量断言生效 |
| P0-6 | 守卫骨架：`MapperXmlParseTest` / `MapperSqlGuardTest` / ArchUnit 白名单（此时 Mapper 仅试点） | 三条规则全绿，含故意违规的负例 |
| P0-7 | `PayrollMappers.forDatabase(...)` 测试装配工厂（先只装配试点仓储） | 试点测试改用它后断言不变 |
| P0-8 | 试点迁移 `H2AuditStore` + `H2LlmUsageStore`；`MapperExceptionMappingTest` 先在 H2 绿 | 原测试断言零改动通过 |
| P0-9 | 三项指标基线：Mapper 调用次数、`connection()` 获取次数、语句 P95 | 写入本文档，作为后续每批闸门 |
| P0-10 | MySQL 闸门 + 异常类型在 MySQL 上补断言 + D6 回填/upsert 结论写回 §5.2 | 闸门记录留档；P0 收尾评审 |

**P0 期间禁止给未迁移 store 加 `@Transactional`**：Spring 事务绑定的是适配器给出的连接，而这些 store 仍用 `PayrollDatabase.connection()` 自己开连接，注解不生效且会多占一条连接（§5.0.1 末段）。注解与 Mapper 改造必须同提交。

#### 5.2.1 P0-9 指标基线与复测方法

三项指标各有一个进程内计数器和同名 Micrometer 计量器（`DbMetrics` + `MybatisMetricsInterceptor`，后者作为 `@Component` 被 Boot 自动注册进 `SqlSessionFactory`，测试侧由 `PayrollMappers` 手动挂载同一插件）：

| 指标 | 进程内 API | Micrometer 计量器 | 说明 |
|---|---|---|---|
| Mapper 调用次数 | `DbMetrics.mapperCallCount(id)` / `mapperCallsByStatement()` | Counter `payroll.db.mapper.calls`，tag `statement=<短名>` → `payroll_db_mapper_calls_total{statement="AuditMapper.insert"}` | 每次 `Executor.query/update` 计一次；短名去掉 `io.agentscope.examples.payroll.db.mapper.` 前缀 |
| `connection()` 获取次数 | `DbMetrics.connectionAcquiredCount()` | Counter `payroll.db.connection.acquired` → `payroll_db_connection_acquired_total` | 计的是 `PayrollDatabase.connection()` 调用次数，即适配器真实取连接数 |
| 语句 P95 | `DbMetrics.statementP95Nanos(id)` | Timer `payroll.db.statement.duration`（publishPercentiles 0.5/0.95/0.99，tag `statement`）→ `payroll_db_statement_duration_seconds{statement="...",quantile="0.95"}` | 环绕 `Executor` 调用的纳秒耗时；进程内另留 2048 样本环形窗口，供无 Prometheus 注册表的纯 JUnit 下确定性取值 |

**基线（2026-11-28，JDK 17.0.12 / H2 2.3 内存库 / Windows 10；`ROUNDS=200`，预热 100 轮后 `DbMetrics.reset()`；连续三次取稳定值）**：

| 语句 | 调用次数 | P95 run1 | P95 run2 | P95 run3 |
|---|---|---|---|---|
| `AuditMapper.insert` | 200 | 487us | 621us | 637us |
| `AuditMapper.find` | 200 | 1510us | 1445us | 1472us |
| `LlmUsageMapper.insert` | 200 | 382us | 484us | 382us |
| `LlmUsageMapper.find` | 200 | 1184us | 1074us | 1025us |
| **合计** | **800** | — | — | — |

连接数：**800 次 `connection()` / 800 次 Mapper 调用，`connectionsPerCall=1.000`**——试点两仓储都在无事务的单语句路径上，一次 `SqlSessionTemplate` 调用开一个 session、取一条连接、用完即还。这条恒等式是后续批次「连接不放大」的参照：若某批把单语句包进事务或拆出二次查询，比值会立刻偏离。

**复测命令**（后续每批在合入前跑同一命令、同一台机器）：

```bash
mvn -o -B -pl payroll-tests -am test -Dtest=P0MetricsBaselineTest -Dsurefire.failIfNoSpecifiedTests=false
```

读 surefire 输出里的 `[P0-METRICS]` 行即可；测试本身断言每条语句调用次数恰为 `ROUNDS`、P95 有样本且 < 100ms、且 `connections == totalCalls`，所以装配退化会直接红。

**门槛（§5.7）**：每批与上表比较，`connection()` 获取次数 ≤ 1.2×，语句 P95 不劣化 20%。基线与环境强相关（JIT、磁盘、JVM 参数），只用于同机前后对比，不跨机器引用；换机需重跑并覆盖本节。

#### 5.2.2 P0-10 MySQL 闸门记录

**环境（2026-11-28）**：本机 MySQL 8.0（`127.0.0.1:3306`，库 `payroll`；连接参数的开发默认值见 [application-mysql.yml](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/resources/application-mysql.yml)，可用 `PAYROLL_DB_URL/USER/PASSWORD` 覆盖）；mysql-connector-j 9.5；JDK 17.0.12；Windows 10。本机无 docker CLI、无 mysql CLI，按 §5.2 的「compose 或部门库」二选一，直接对本机 MySQL 实例执行；未使用 root 账号，未在命令或日志中出现任何口令。

**子项一：启动闸门（mysql profile + 真实 fat jar）**

`payroll-backend/target/payroll-backend-exec.jar` 以 `SPRING_PROFILES_ACTIVE=mysql`、`PAYROLL_WEB_PORT=18099`、`PAYROLL_NODE_ID=gate-node` 启动：

| 证据 | 实测 |
|---|---|
| Flyway 落库 | `FlywayExecutor - Database: jdbc:mysql://127.0.0.1:3306/payroll?... (MySQL 8.0)`；闸门复跑校验 24 个迁移，`Current version of schema 'payroll': 24`、`Schema is up to date` |
| 启动耗时 | `Started PayrollWebApplication in 7.216 seconds` |
| 健康检查 | `GET /api/health` 2 秒内 200，`"db":"UP","redis":"SKIPPED"`（mysql profile 不启用 Redis 自动装配，与 §5.0.3 一致） |
| MyBatis 启动断言 | `MappedStatementVerifier - MyBatis 启动断言通过：6 条语句 [...]` |
| 日志噪声 | 仅预期 WARN（未配置 `PAYROLL_AGENT_RUNTIME_URLS/TOKEN`、未配置 `PAYROLL_SECRET_KEY`、TopologyGuard 单节点提示），无异常堆栈 |

**子项二：写路径 + 分页 + 异常类型 + D6/upsert（`MySQLGateTest`）**

默认跳过（CI/离线构建不依赖数据库），显式开启才对 MySQL 跑：

```bash
# 任选其一开启；已验证命令用环境变量（forked JVM 必然继承）
PAYROLL_GATE_MYSQL=true mvn -o -B -pl payroll-tests -am test \
  -Dtest=MySQLGateTest -Dsurefire.failIfNoSpecifiedTests=false
# 或
mvn -o -B -pl payroll-tests -am test -Dtest=MySQLGateTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dpayroll.gate.mysql=true
```

| 覆盖项 | 断言 | 实测（`[MYSQL-GATE]`，自适应主键每次运行递增，数值为当次样例） |
|---|---|---|
| 审计写入 + 分页 | 写 3 行后首行主键 >0；`page=1,size=2` 得 2 行且都属于本次唯一令牌；`count=3` | `auditId=3150`，分页 2/3、count=3 全绿 |
| 用量写入 + 读回 | 主键 >0；按 `lastHours` 窗口查回本行 | `usageId=1161` |
| 约束异常类型 | `job_lease` 同名二次插入抛 `DuplicateKeyException` 且是 `DataIntegrityViolationException` 子类 | `duplicateKey=org.springframework.dao.DuplicateKeyException` |
| D6 插入分支回填 | `departments` upsert 插入分支 `useGeneratedKeys` 回填 id | `insertBranchId=9` |
| upsert 更新分支 | 同名二次 upsert 生效，记录 affected rows 与调用方载体 id | `updateBranchRows=2 updateBranchKey=9` |

结果：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`（2.07s），BUILD SUCCESS。探针行（审计、用量按唯一令牌；租约、部门按唯一名称）在 finally 里清理，未遗留脏数据。

**子项二补充：全量离线回归**——同日 `mvn -o -B test` 全 8 模块：`Tests run: 1586, Failures: 0, Errors: 0, Skipped: 7`（7 个 skip 含本闸门默认跳过），BUILD SUCCESS，2 分 38 秒。

**子项三：跨引擎结论（回填 §5.0.2 与 §5.0.4）**

1. **异常契约在 MySQL 成立**：重复键同样落到 `DuplicateKeyException`（`DataIntegrityViolationException` 子类），与 H2 一致。§5.0.2 的类型映射表可直接用于业务判定，8 处 SQLState `23` 分支改造无需按引擎分叉。
2. **D6 主键回填在 MySQL 成立**：`audit_log`、`llm_usage` 的 `useGeneratedKeys` 在 MySQL 同样回填 `id`；载体必须是可变对象（`AuditInsert` / `LlmUsageInsert`），record 无法回填。
3. **upsert 更新分支**：`departments` 探针显示 MySQL 8.0 + connector 9.5 在 `ON DUPLICATE KEY UPDATE` 的更新分支返回**既有行 id**（`updateBranchKey=7`，与插入分支一致），affected rows=2。但这是驱动/服务端行为，不是 SQL 标准，**迁移代码不得依赖**；且两个真实 upsert 目标（`model_credential` 以 `credential_id` VARCHAR 作 PK、`skill_blob` 以 `sha256` CHAR(64) 作 PK）都是**业务主键**，id 由调用方给定，与自增回填无关。`departments` 探针仅作通用行为留档。

**结论**：§5.2 的 MySQL 闸门通过（启动 + 全量测试 + 审计写入 + 分页查询均绿），具备进入 P1 的条件。`MySQLGateTest` 默认 assume-skip，无 MySQL 的 CI 不会红；有 MySQL 的环境按上表命令复跑即可。

### 5.3 P1：低风险叶子仓储（5 人日，约 60 条 SQL）

> 人日从 3 上调到 5：本批**并非全是单语句**。`DevTaskStore`（`replacePlan` 先 `DELETE` 再批量插入）、`DevArtifactStore`、`H2ChatFeedbackStore`（`upsert` 先查后写）今天都自带事务，迁移前必须按 §5.0.1 先落 L2 边界，否则 `replacePlan` 失败会把计划清空。

| 顺序 | 仓储 | 说明 |
|---|---|---|
| 1 | `JobLeaseStore` | 最先迁：租约语义靠 SQL 原子性，且今天**没有事务**（UPDATE/INSERT 各自 autocommit），正好验证「L3 单语句不加事务」等价；接管/续租/释放三态测试已存在。`isConstraintViolation` 按 §5.0.2 改为类型判定 |
| 2 | `ModelCredentialStore` | 凭证表；`insert-or-update` 依赖异常类型判定（§5.0.2）；同时落地 D7 的日志降级（该 Mapper 包 logger 固定 WARN） |
| 3 | `H2ChatFeedbackStore`、`H2ActionProposalStore` | 反馈与提案；前者 `upsert` 是 L2，先加注解再迁 |
| 4 | `JdbcSkillDataStateStore`、`JdbcSkillCreationOriginStore` | 技能附属表，SQL 短 |
| 5 | `DevTaskStore`、`DevArtifactStore`、`DevDeliveryStore`、`DevApprovalStore` | 研发域四表。**不是「仅测试使用、风险低」**：`DevTaskStore.replacePlan` 与 `DevArtifactStore` 都是 L2 事务单元，字段多，边界值要单独覆盖 |
| 6 | `RetentionStore` | 18 条 SQL 全是删除/统计，适合验证批量语句与超时设置 |

验收：每个仓储保留原类名与公开方法，内部改为 Mapper 调用；原测试**断言**一行不改（构造装配按 §5.0.5 改）；新增 `XxxMapperTest` 覆盖 H2 边界值（空结果、`LIMIT 0`、时间精度）；L2 方法在 `TransactionWhitelistTest` 中登记。

### 5.4 P2：对话 / 轮次 / 团队 / 薪酬 / 管理（6–8 人日，约 90 条 SQL）

| 仓储 | 风险点 | 处理 |
|---|---|---|
| `H2ChatTurnStore` | 手工事务 + 租约 + 幂等插入 | `beginTurn` 保持 **L2（store 方法级）**，不迁服务层——迁到服务会把租约获取和幂等插入拉进同一事务；租约仍走 `JobLeaseStore` 的 L3，不进事务；冲突判定按 §5.0.2 |
| `H2ChatStore` | 4 处自增主键、**3 处事务**、**1 处跨 store 级联删除**、会话/消息分页、JSON 列 | 主键按 D6 模板；`appendAssistant`(:413) 与 `appendTraces`(:576) 为 L2；`delete`(:167) / `clear`(:866) 为 **L1，上移 `ChatDataService`**（§5.0.1；`clear` 现状无事务）；消息读取保持 L3，禁止把 SSE 推送放事务内 |
| `ChatAttachmentStore` | 状态机 + 自增主键 + 文件路径 + **L2 事务**（[:248](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/db/ChatAttachmentStore.java#L248)） | 状态更新用带 `WHERE status=?` 的条件更新，返回值判断影响行数；状态机 + 附件行的多语句保持在 L2，文件 IO 留在事务外 |
| `H2AgentTeamStore` | 2 处自增主键、团队运行聚合查询 | 聚合 SQL 原样迁入 XML，不改查询计划 |
| `H2PayrollRepository` | 4 处方言（upsert/时间表达式）、薪酬结果批量写 | 方言按 D4 收敛；批量写用专用 BATCH `SqlSessionTemplate` |
| `H2PayrollRunStore` / `H2ApprovalStore` / `H2AdminRepository` | 自增主键、审批链多语句 | 审批链创建 + 节点写入是天然事务单元，先补 `@Transactional` 再迁 |
| `H2UserProfileStore` | 15 条 SQL、JSON、抽取结果合并 | 合并逻辑留在服务层，Mapper 只做 CRUD |
| `JdbcAgentDefinitionRepository` | 定义整份 JSON 读写，`source=db` 时的权威副本 | 单表 CRUD，读路径不变；与 Nacos 通知通道解耦 |

规则：**任何 `@Transactional` 方法内不得出现模型调用、SSE 发送、HTTP 请求、文件 IO**；事务方法只包 DB 调用，且尽量在 50ms 内完成（H2 文件库是单写者，长事务会阻塞其他写入）。由 `TransactionWhitelistTest` 静态检查，不靠评审。

**异步写者必须留在事务外**：`LlmUsageRecorder` 的单线程有界队列（[LlmUsageRecorder.java:23-24](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/usage/LlmUsageRecorder.java#L23)）、`KbContextPrefetcher` 的预取线程池（[:126](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/service/KbContextPrefetcher.java#L126)）、`TurnEventFanout` 的 drain 执行（[:686](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/service/TurnEventFanout.java#L686)）都在别的线程上，**不会加入调用方事务**；不要试图用 `TransactionSynchronization` 把它们"接回"事务，否则队列失败的语义会变成业务失败。

### 5.5 P3：知识库（4–5 人日，约 55 条 SQL）

范围：`KbChunkStore`、`KbConfigStore`、`KbDirectoryStore`、`KbDocumentStore`、`KbMemberStore`、`KbSeriesStore`、`KbTaskStore`、`KbTagStore`（backend 模块）。

注意：

- KB 有不少「先查后写」的幂等逻辑，迁移时以**条件更新 + 影响行数**替代「查了再判」，减少竞态。
- KB 任务表字段多、更新频繁，Mapper 只暴露语义方法（`markRunning`、`markFailed`），不暴露通用 `update`，避免把状态机打散。
- 分页统一 `LIMIT ? OFFSET ?`；不引入 PageHelper，避免多一条 count 查询改变慢查询画像。

### 5.6 P4：技能仓储与收尾（4–5 人日，约 17 条 SQL）

- `JdbcSkillStore`：17 条 SQL（全项目单文件最多）+ 手工事务（[JdbcSkillStore.java:1577](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1577)）是最后一块，因为涉及版本表、blob 指针、revision 递增和回滚。按 §5.0.1 保持 **L2**，但**不能直接把注解加到现有公开方法上**：当前 `writeResources` 在事务体内执行 `blobStore.put`（[:1803](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1803)），回滚后再用 `cleanupBlobs` 补偿，也就是文件 IO 今天就在事务里。P4 必须先拆成「公开入口先落盘 → 独立 `@Transactional` 组件只登记指针」，否则注解会把文件 IO 一并包进事务，违反 §5.0.3。
- 技能 blob upsert 目前用 `dialect(UPSERT_BLOB_H2, UPSERT_BLOB_MYSQL)`（[:1846](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java#L1846)），按 D4 收敛为 `ON DUPLICATE KEY UPDATE`。
- 收尾：删除仓储层所有 `database.dialect(...)` 调用；`PayrollDatabase.dialect()` 仅保留给内部种子 SQL（P4 评估后再删）；同步更新 [`architecture.md`](architecture.md) §5（「SQL 需双方言可执行」改为「统一 MySQL 方言 + H2 兼容执行 + 双引擎测试」）。

### 5.7 每批的验收与回滚

| 检查 | 要求 |
|---|---|
| 全量测试 | `mvn -o test` 通过数不低于迁移前基线，0 失败 |
| 原测试 | 被迁仓储的既有测试**断言不改**通过（构造装配按 §5.0.5 调整） |
| 新测试 | 每个 Mapper 至少覆盖：空结果、边界分页、可空列、时间精度 |
| 事务 | `TransactionWhitelistTest` 与 §5.0.1 登记表逐条相等；L2 方法事务回滚有专用用例（失败注入后断言行数） |
| 异常 | 涉及约束冲突的仓储有类型断言用例；`MapperExceptionMappingTest` 在 H2/MySQL 均绿 |
| 指标 | 与 P0 基线比较：`connection()` 获取次数不得高于 1.2×；语句 P95 不得劣化 20% |
| 双引擎 | H2 自动执行；MySQL 闸门（compose/部门库）每批一次 |
| 启动 | H2 与 `mysql` profile 都能启动；`MappedStatement` 数量断言通过；`/actuator/health` 组件与迁移前一致 |
| 回滚 | 单批 revert 即可；不为迁移引入不可逆数据变更（本方案无 DDL） |

## 6. 安全与合规

| 项 | 做法 |
|---|---|
| SQL 注入 | Mapper 只允许 `#{}`；`${}` 仅限 `OrderBy`/表名白名单枚举，`MapperSqlGuardTest` 强制 |
| 凭证泄露 | `ModelCredentialStore` 对应 Mapper 的 logger 固定 WARN；禁止 `StdOutImpl`；`application-common.yml` 默认 `logging.level.io.agentscope.examples.payroll.db.mapper=info` |
| 敏感参数入日志 | MyBatis 在 DEBUG 打印**全量参数**。除凭证外，`audit_log`（before/after JSON）、`chat_messages`（正文）、`user_profile`（画像 JSON）同样敏感：凭证 / 审计 / 聊天的 Mapper namespace logger 固定 WARN，画像 Mapper 同理；包级默认 `info`，配置中不得出现这些包的 `debug` |
| 日志体积 | 生产不开 DEBUG；本地调试用 `--logging.level....=debug` 临时打开，不写进配置文件 |
| 审计 | `H2AuditStore` 迁 MyBatis 后仍由 `AuditService` 统一写入，审计动作与字段不变；`AuditService` 捕获 `RuntimeException`（[AuditService.java:172](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/service/AuditService.java#L172)），`DataAccessException` 仍被吞掉，**失败行为不变**（已核对）；迁移批次本身在交接文档记录 |
| 迁移脚本 | MyBatis 不执行 DDL；新表仍只允许新增 `payroll-common/src/main/resources/db/migration/common/V{n}__*.sql` |
| 权限 | 不新增外部接口；`@MapperScan` 不扫描非本项目的包 |

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| **事务边界静默消失**（29 个多语句事务体；`DevTaskStore.replacePlan` 先删后插；`H2ChatStore.delete` / `clear` 跨 4 个 store 级联，其中 `clear` 今天连事务都没有） | 失败时数据残缺：计划被清空、会话删除一半、消息无追溯 | §5.0.1 三层 + 29 行登记表 + `TransactionWhitelistTest`；两个级联上移服务层 |
| **异常翻译打断 8 处 SQLState `23` 分支** | 租约"未抢到"变成抛异常；幂等插入变成报错 | §5.0.2 类型映射表 + `MapperExceptionMappingTest`（H2/MySQL 双引擎断具体类型） |
| **测试装配 103 处直接构造** | 「测试不改」是假象，77 个文件需机械改造 | §5.0.5 `PayrollMappers` 工厂 + `..db.support..` 白名单；工作量计入 P0 |
| Boot 自动配置副作用（Flyway 双跑、健康检查形状变化） | 启动失败或迁移冲突 | P0 显式排除 4 项自动配置 + 启动冒烟；`management.health.db.enabled=false`；启动断言 `MappedStatement` 数量 |
| XML 漏加载 / 与 `@Mapper` 重复注册 | 漏加载静默到调用才炸；重复注册启动即失败 | 只用 `@MapperScan`，不写 `@Mapper`；`MapperXmlParseTest` 断言语句数 |
| 事务代理失效（同类自调用） | 该回滚的没回滚 | L2 只标在 store 公开方法且 store 必须是 Spring Bean；`TransactionWhitelistTest` 校验清单；静态检查 `this.` 调用带注解方法 |
| H2 单写者 + 长事务 | 本地/测试偶发锁等待，写入排队 | 事务只包 DB 调用（静态检查）；`default-statement-timeout=30`；目标 <50ms；P0 观测 `LOCK_TIMEOUT` |
| 连接获取次数上升（当前 1.20 语句/连接） | H2 文件库频繁开关连接 | 多语句**读**路径用 `@Transactional(readOnly=true)` 共连接；基线指标设 1.2× 上限 |
| 方言判断失真 | 本地过、MySQL 挂 | D4 双引擎测试 + 每批 MySQL 闸门；`_databaseId` 仅作逃生口 |
| 自增主键回填不可行 / upsert 分支返回 0 | 25 处写接口返工 | §5.0.4 收紧模板：只用 `useGeneratedKeys` + `<selectKey>`；upsert 单独实测 |
| XML 大量复制导致风格漂移 | 后续维护成本上升 | 统一模板（resultMap + `<sql>` 片段 + `<if>` 过滤）+ 评审清单；每批 ≤60 条 SQL |
| resultMap 漏列 | 显式映射下静默 null | P2 加列覆盖测试（XML 列集合 vs `ResultSetMetaData`） |
| 依赖离线不可用 / 核心版本漂移 | `mvn -o` 失败、CVE 未知 | 一次性 `dependency:go-offline`；根 pom 同时锁 starter、`org.mybatis:mybatis`、`mybatis-spring`；联网窗口核对 CVE |
| MyBatis 日志泄露参数 | 凭证/审计/聊天/画像数据入日志 | 4 类 namespace 固定 WARN；yml 写死 `info`；守卫测试扫描 `<cache>`/`${}`/嵌套 select |

## 8. 工作量与排期

| 批次 | 内容 | 人日 | 可独立合入 |
|---|---|---|---|
| P0 | 依赖、DataSource 适配器、事务管理器、Mapper 扫描、**§5.0 五项契约 + 守卫测试 + 测试装配工厂**、审计+用量试点、指标基线、MySQL 闸门 | 5 | 是 |
| P1 | 11 个低风险仓储（含 3 个 L2 事务单元的前置改造） | 5 | 是（按仓储拆 2–3 个 PR） |
| P2 | 对话/轮次/团队/薪酬/管理 10 个仓储（含 `H2ChatStore.delete` 级联上移） | 6–8 | 是（按域拆 3 个 PR） |
| P3 | KB 8 个仓储 | 4–5 | 是 |
| P4 | 技能仓储、方言收尾、文档同步 | 4–5 | 是 |
| 合计 | | **24–28 人日** | |

前置：P0 开工前需要一次联网依赖拉取（本机 `~/.m2` 无 MyBatis）、一个可用的 MySQL 验证环境，以及 **§5.0.1 的 29 行事务登记表补全**（P0 第一天的产物，未完成不得动仓储代码）。

## 9. DoD

- [ ] `mybatis-spring-boot-starter` 版本在根 pom 锁定（含 `org.mybatis:mybatis`、`mybatis-spring`），`mvn -o test` 可离线执行。
- [ ] `PayrollDatabaseDataSource` 通过 `connection()` 委托；非事务路径不改 autoCommit，事务路径由 `DataSourceTransactionManager` 管理。
- [ ] Boot 的 DataSource / DataSourceTransactionManager / Flyway / SqlInit 自动配置已排除，H2 与 `mysql` profile 均能启动；`MappedStatement` 数量启动断言通过。
- [ ] §5.0.1 的 29 行事务登记表补全并落成测试侧 `TransactionRegistry`；已迁仓储的 L2 方法全部带 `@Transactional`，`H2ChatStore.delete` / `clear` 已上移服务层（`clear` 的「全成全败」有失败注入用例）；`TransactionWhitelistTest` 绿。
- [ ] §5.0.2 的 8 处约束冲突判定全部改为异常类型；`MapperExceptionMappingTest` 在 H2/MySQL 均断言到具体子类型。
- [ ] 显式 `isolation=READ_COMMITTED`；`@Transactional` 方法体内无 Executor/SSE/HTTP/文件 IO（静态检查通过）。
- [ ] D6 模板落地：只用 `useGeneratedKeys` + `<selectKey>`，无跨 Mapper 的 `LAST_INSERT_ID()`；upsert 结论已写回。
- [ ] 测试装配走 `PayrollMappers`，ArchUnit 白名单为 `..db.mapper..` + `..db.support..`；`..service..` / `..web..` 不依赖 `..db.mapper..`。
- [ ] 32 个仓储全部改为 Mapper 实现；公开方法签名与语义不变，`database.dialect(...)` 在仓储层清零。
- [ ] `MapperSqlGuardTest`、`MapperXmlParseTest`、`MapperSqlParityTest`、ArchUnit 新规则全绿。
- [ ] 66 个 DB 测试**断言**不改通过；每个被迁仓储有对应 Mapper 边界测试。
- [ ] 指标基线（连接获取次数 ≤1.2×、语句 P95 ≤1.2×）在每批合入前复测。
- [ ] MySQL 闸门每批执行并留记录；H2/MySQL 同一 SQL 行为一致（D4）。
- [ ] [`architecture.md`](architecture.md) §5 与 [`mysql-redis-deploy.md`](mysql-redis-deploy.md) 的持久化说明已同步。
- [ ] 交接文档记录：D6 主键回填最终模板、29 行事务登记表、`clear` 语义变更、JdbcSkillStore 的 blob/事务拆分、各批回滚方式、已知差异（如有）。
