# 技能存储迁移 MySQL + 对象存储 · 实施规格

- 版本：v0.13（2026-11-24）
- 状态：**已确认并开工**。D1–D11 全部按推荐值生效（见 §22 首段）；**P0（P0-1…P0-8b）已全部实施并回写**，
  P1 里 P1-1、P1-2、P1-3 已落、P1-4/P1-5 未开工，实现与本节的偏差以「实现说明」小节回写本文。§11.3 的对账门禁与 §12.1 的投送演算是本方案两个「算不清就不能开工」的前置。**全部批次均未提交**，默认源仍是 `nacos`。
- v0.13 变更：**§19 P1-3（操作者 + 导入的行级令牌）已落**。先更正一处旧措辞：Git/ZIP 导入**从 P0-5 起就走 `SkillStore` 端口**，本批补的是端口上缺的两样——`expectedRevision` 与 `operator`。端口因此新增六个**默认重载**（`create(5)`/`update(7)`/`replaceFiles(4)`/`deleteFile(4)`/`rollback(4)`/`importProject(4)`，逐格委托，`NacosSkillRegistry` 第 N 次零改动），**本批同样不需要新迁移**：`skill_definition.created_by/updated_by`（`V22:25/27`）与 `skill_version.created_by`（`V22:42`）三列 P0-1 就建好了，一直空着。落库口径三条：空白操作者统一走 `operatorOrNull` 成 SQL NULL（假造 `"admin"` 会让迁移灌进来的 18 条读起来像管理员改的）、`created_by` 只在插入时写而 `updated_by` 每写必刷、回滚追加的那条版本行归**按下回滚的人**。三个不显然的连带后果：(1) `operator`/`changeNote`/`expectedRevision` 是三个相邻 `String`，**串位编译器发现不了**，防线是一条用例用五个互不相同的哨兵值把整条写链串完（`operatorWritesEveryProvenanceColumn`）；(2) Mockito 的 mock 会拦 `default` 方法，控制器一旦只调最宽签名，**11 处既有 stub/verify 全得迁**——这批测试的改动量在桩上而不在断言上；(3) 导入的 dryRun 分支既不 `markRevision` 也不记审计，否则「看一眼」会被写成一次成功的条件写入。另有一条 §13-6 的口径更正：草案里的新动作 `skill.import.db` **不会出现**——管理台导入沿用 `skill.import`（`after_meta.source=git|zip`），迁移导入器压根不依赖 `AuditService`；未落的动作只剩 `skill.blob.gc`。测试：`JdbcSkillStoreTest` 21 → **24**、`SkillAdminControllerTest` 30 → **33**、`SkillImportServiceTest` 12 → **13**，全量 `mvn -o test` **1398 → 1405 用例 0 失败 4 跳过**、`mvn -o -q -DskipTests package` 通过（§17 V-08）。**十一条变异验证**（P1–P11）见 §18 T-08c。回写位置：§8 末「P1-3 落地情况」5 条、§9.2 末「P1-3 落地补充」4 条（并把 P1-2 第 5 条的 `created_by` 欠账勾掉）、§9.3 末「P1-3 已落」、§11.2 第 6 条、§13-6、§17 V-08、§19 P1-3；顺带修掉四处因本批代码增长而漂移的行号引用。README 与 `docs/multi-agent-handoff.md` §34.3 第 9 项同步。**没做的事**：CLI 侧导入不带操作者（有意落 NULL）、没有「按操作者查技能」的接口、`delete()` 没有 operator 变体（定义行整行消失）、前端显示 `created_by`/`updated_by` 与两类 409 分开处理属 P1-4、真机没点过一次带 `revision` 的导入属 P1-5。
- v0.12 变更：**§19 P1-2（版本列表 / 回滚 / `change_note`）已落，并一起交付 §18 T-08 欠的「新端点 + ETag」**。`SkillStore` 端口新增 `versions(String)` 与 `rollback(String,int,String)` 两个**默认方法**——列表退化成空、回滚退化成显式报错（Nacos 源没有版本行，静默成功会让管理台以为生效），`NacosSkillRegistry` 仍零改动，**本批不需要新迁移**（`skill_version.change_note` 列 V22 早就建好）。`JdbcSkillStore` 侧：时间线单查询不取 `body_md`（打开历史不该等于下载全部历史），`current` 按 `current_version_id` 判定而不是取最大序号；回滚严格照 §9.2「追加版本行 + 复制 `skill_resource` 清单行 + 移指针」，历史 `body_md` 一字不改（测试直查两行旧正文证明），序号因此只增不减——「回到 v2」在库里是一条新的 v3。三处最容易被下轮改错的取舍：(1) 回滚前逐个 `blobStore.exists()` 验目标版本的字节，缺任何一个就拒绝回滚——指针一移，运行期那次整批装配会撞「技能资源缺失」（与 §11.3 第 4 条同形），检查放在 `nextVersionNo` 之前所以验不过**不会留下空版本行**；(2) `change_note` 的 200 字符门槛在事务开始前用 Java 校验（`requireChangeNote`）而不是交给库截断，且**空白表示不改而不是清空**（表单每次整体提交，只翻开关那次它是空的）；(3) 文件接口的 `ETag` 由控制器现算 `SkillManifests.contentHash(content)` 而不是读 `skill_resource.sha256`——两条读路径要给出同一个指纹，而 Nacos 源根本没有清单。测试：`SkillAdminControllerTest` 26 → **30 用例**、`JdbcSkillStoreTest` 16 → **21 用例**，全量 `mvn -o test` **1389 → 1398 用例 0 失败 4 跳过**、`mvn -o -q -DskipTests package` 通过（§17 V-08）。**十一条变异验证**（M1–M11，覆盖长度门槛/空说明/当前版判定/blob 体检/回滚说明/清单复制/时间线排序/`current` 判定/条件请求/回吐归一 id/审计 `fromVersionNo`）每条**恰好红 1 条用例**，见 §18 T-08b。回写位置：§8 末「P1-2 落地情况」5 条、§9.2 末「P1-2 落地补充」5 条、§9.3 末「P1-2 已落」4 条、§13-2/§13-6 各补一段。README 与 `docs/multi-agent-handoff.md` §34.3 第 8 项同步。**没做的事**：`created_by` 恒空串（store 不收操作者，接线随 P1-3）、清空一句说明没有接口、前端时间线抽屉属 P1-4、版本裁剪与孤儿 GC 属 P2-1（本批只保证「回滚需要的字节还在」，不保证「旧版本一定还在」）、**真机还没按过这两个端点**（只有 JUnit 证据，随 P1-5 的 V-01…V-06 一起做）。
- v0.11 变更：**§19 P1-1（管理面写路径剩下的那两件）已落**。四个写端点（`PUT /{id}`、`DELETE /{id}`、`PUT /{id}/files`、`DELETE /{id}/files/{*path}`）接上可选的 `?revision=` 并改走带 token 的重载，令牌同时 `AuditContext.markRevision()` 落进审计列（`AuditService` 早就把它写成独立字段 + `writeResult=write_ok`，本批只补 JSON 侧）；`SkillStore` 端口新增四个**默认重载**委托到无 token 的原方法，Nacos 源因此不必实现一组空重载（§8 末「P1-1 落地情况」第 1 条）。审计与响应快照统一补 `revision` / `versionNo` 两键（§13-6 由「待办」改成「已落」，键名 camelCase）。三处不显然的取舍：(1) `version_no` 从 `detail()` 那次单版本读取顺带取回（`SELECT_VERSION_BODY` 多带一列），目录查询 `ROW_COLUMNS` 保持无 join——A-01 量的正是那条 18 技能 / 均值 1.06ms 的单查询，为审计字段给它加连接不值；(2) `replaceFiles`/`deleteFile` 走 `rewriteDefinition` 时会把 `skill_definition.revision` 一起 +1（§9.2 版本只跟正文走，但行令牌跟所有写走），所以两个文件端点的**响应体必须回吐写后的新 token**，否则 P1-4 前端下一次 PUT 必 409；(3) 写后重读 `persisted()` 是 best-effort，读失败只丢一个审计字段，不能把已提交的写变成 5xx。测试：`SkillAdminControllerTest` 21 → **26 用例**（新增 409 透传、`markRevision`、审计回读失败、数据类审计带版本、写端点 `skill:manage` 五条）、`JdbcSkillStoreTest` 15 → **16 用例**；全量 `mvn -o test` **1383 → 1389 用例 0 失败 4 跳过**（§17 V-08）。四处变异验证各自让对应新用例立刻红（详见 §18 T-08）。§18 T-08 的 **ETag 一项没做**：它和 `versions()`/`rollback()` 是同一件事（都要先有可读的版本行），随 P1-2 交付。
- v0.10 变更：**P0-8b（2）那条 P1（blob 读失败级联成 18 条误诊）已修**。`SkillMigrationCheck` 的 `Buckets` 新增 `runtimeRead`，与 P0-6 修 `missingInDb` 级联时同形：`runtimeData` 是整批一次取回，一个 blob 读不到就整批抛，此时**不再逐技能比文件**（`fileMissing`/`fileExtra`/`fileHashMismatch`/`resourceHashMismatch` 四桶保持为空——没比成的事不给结论），只记一条 `读取技能资源失败，本轮未比对文件包（正文与执行开关仍已比对）：<原因>`；不依赖字节的 `bodyMismatch`/`executionMismatch` 照旧逐条比。逐条「库内运行期数据缺失（版本行未落库或被删）」从此只剩一种触发路径：整批读成功、单条版本行确实被并发删掉。配套三处：`checkedFiles` 语义从「开关开没开」改成「这一轮有没有真的逐文件比过」（关闭与整批读不回都是 false，后者原因在 `problems`），`PayrollApplication` 的 CLI 那行同步不再把原因单指成 `PAYROLL_SKILL_FILES_ENABLED`，`JdbcSkillStore` 的「技能资源缺失」文案带上 `<id>/<path>`（两条读路径同源）。测试：`SkillMigrationCheckTest` 13 → **15 用例**，变异验证摘掉短路后新用例立刻红在 `expected: <1> but was: <5>`。全量 `mvn -o test` 基线 1381 → **1383 用例 0 失败 4 跳过**（本轮未挂 `PAYROLL_TEST_MYSQL_*`，`DdlSyntaxTest` 的 MySQL 腿回到跳过；挂上为 3，§17 V-08）。§11.3 第 4/5 条、§18 T-16、§19 P0-8b、README 的差异类型说明与 `docs/multi-agent-handoff.md` §34.3 第 3 项同步。
- v0.9 变更：**V-12 拍板 (b)+(a) 并已实施、重测通过**。代码一处：`SkillManifest.catalogLine()`（`:57-66`）不再渲染正文字节数，目录行只剩 `- <id>：<描述>` 与有版本时的 `（v<版本>）`——那个数是 `strip()` 归一后的字符数，同一份内容在 Nacos 与库里差 1 个字，切库当天会让整份目录集体改字节，并把按字符数计算的注入截断点推走一行；`contentLength` 字段本身保留（管理台列表列与 `stats.contentBytes` 还在用，`SkillAdminController.java:767,798`）。判定口径同步改为「遮掉 `_nacos`/`_db` 源标签、正文按 `strip()` 归一后逐字符一致，超预算截断点允许漂移」，真机重测（18 条共有技能、生产默认预算）：目录段逐行一致 = true、归一后正文差异 1 条（`it-support`，本批 T-12 往库里 PUT 的正文，不是存储缺陷）、注入段前 4111/4134 字符一致后只剩截断点漂移 2 行 → **按新口径通过**，判定与量化过程见 §17 V-12 行。测试：`SkillPromptBudgetTest` 增 `catalogLineOmitsContentLength`（9 → 10 用例），全量 `mvn -o test` 基线 1380 → **1381 用例 0 失败 3 跳过**（§17 V-08）。§19 P0-8b 里那条「等这条拍完再改」的欠账收口，`docs/multi-agent-handoff.md` §34.3 第 2 项同步。
- v0.8 变更：**P0-8b（P0 剩下那串真机项）已执行并回写**。db 源第一次在真进程里跑起来，顺带抓到一个只有真容器才抓得到的 P0 装配缺陷：`dbSkillRepository` 与 `skillRepository` 同为 `AgentSkillRepository`，`AgentAdminController` 的 `ObjectProvider.getIfAvailable` 当场歧义、进程起不来（P0-1…P0-7 全在手工 new 对象，所以七批都没撞见）。修法是在组合仓库上加 `@Primary`（`PayrollCoreConfiguration.java:495`，理由写在该方法 javadoc），并补 `SkillDbSourceWiringTest` 3 用例锁死（§18 T-18：去掉 `@Primary` 立刻红，报错文本与真机同）。§17 A-01/A-02/A-04 填实测数、A-03 明写未验证与原因，V-01 记「没拿到退出码 0，差的是两条 22 字的 Nacos 正文而不是代码」，V-03 按「18 技能不构成判定」降级，V-07 给单次实测 10.139 s，V-12 **没通过**：草案最担心的目录顺序那一维实测通过（两边都是内置在前 + 其余字典序，真机 Nacos 2.1.1 的列表返回就是字典序，顺带补上 §18 T-13 缺的那半边证据），三个内置角色技能全量注入的那块遮掉 `_nacos`/`_db` 后缀后逐字节一致，但 18 技能那块差 2 行——目录行渲染了正文字节数（db 侧写入时 `strip()` 掉尾部换行，每条差 1 个字符），注入正文又按字符数截断，长度差 1 就把截断点整段推一行（成因与三条出路见 V-12 行）。§18 T-01 的 MySQL 腿实跑、T-11/T-12 回填、新增 T-18；§19 P0-8b 状态行收下两个新缺陷（`SkillMigrationCheck` 一个 blob 读不回会级联成 18 条「版本行缺失」的 P1 误诊；`--skill-migration-check` 必须与写入实例同 `PAYROLL_SKILL_BLOB_ROOT` 这条运维前置）。文档同步：README 新增「技能存储来源（Nacos / MySQL + 本地 blob）」一节（开关、V22 四表、blob 目录、导入、对账命令与退出码、`PAYROLL_TEST_MYSQL_*` 门禁、`/health` 键名），`docs/multi-agent-handoff.md` 新增 §34（结构、两个必知的装配坑、真机证据与五条未收口项）。
- v0.7 变更：**P0-8a（P0-8 的指标前置）已实现并回写**——`SkillMetrics` 的两个 Timer builder 补 `.publishPercentileHistogram()`（`:166`/`:178`，先例是 `TurnMetrics.java:118,136,169`），`skill.catalog.query.rows` Gauge 接上（`SkillMetrics.catalogQueried()` 绑 `Map<String, AtomicLong>`，`JdbcSkillStore.catalog()` 只在成功路径报行数、失败传负数保留上一次好值）。§10 末把「分位数读不出来」整段改写成落地口径 + **`SimpleMeterRegistry` 不实现分位数发布**这条测试陷阱（用它写出来的「直方图已开」是假绿灯，门槛验的是 Prometheus exposition，必须挂 `PrometheusMeterRegistry` 数 `_bucket{` 行）；§15 表格行、埋点清单（已报/未报重新分期）、P95 那条从「测不出来」改成「数据来源已就位、门槛值仍未判定」；§17 V-08 基线 1374 → **1377**；§18 T-04 计数更正、新增 T-17；§19 P0-8 拆成 P0-8a（完成）/P0-8b（纯真机清单，含本机 `PAYROLL_TEST_MYSQL_*` 未设这一条）。
- v0.6 变更：**P0-6 已实现并回写**——§11.1 新增「P0-6 落地情况」7 条（db 源四个 `@ConditionalOnProperty` bean 而 Nacos 源零改动、`import-from-nacos` 只对 db 生效、`payroll.skill.*` 的 Spring 键收口与 `SkillFileSettings` 四旋钮的例外、`SkillRepositories.shared()` 会自建连接池这条装配陷阱、`/health` 组件键 `nacos` → `skills`、轮询器第一次真在进程里跑、`SkillRepositories` 两条顺带修的过滤器/视图集合）；§11.2 新增 8 条（含**跨源目录顺序**这条 V-12 变量与 `executionEnabledOverride` 新规）；§11.3 新增 6 条（对账门定成「集成测试 + `--skill-migration-check` 命令行」，输出从四个数组扩到 14 字段，三条读路径 fail-closed）；§10 表格更正 + 删掉 `pollMillisFromEnvironment()`（第三条没人走的读法）；§6/§7 末/§8 末/§15/§16 的「随 P0-6 接线」全部收口；§17 V-08 基线改 1374、V-01/V-12 明确成真机项；§18 T-05 标完成、新增 T-16、T-13 更正「跨源逐 id 一致写不出来」的理由；§19 P0-4/P0-7 状态行更正、P0-8 收成纯真机清单。关联改名同步进 `docs/architecture-hardening-prd.md:808`。收尾另做了一轮**行号引用复核**（全量 54 处 `.java:NN` 逐条打开对）：草案 §2.2/§3 表里三处已随 P0-5/P0-6 的 bean 增删漂移，`PayrollCoreConfiguration.java:176,406-419` 标注为草案时点并给出当前装配点、`:372-395` 改 `:381-411`、retention 行改 `:319,959,983,1021`；`nacosSkillRegistry` bean 的注释原写「也是 §11.2 导入器的读端同源实现」不成立（导入器读端是 `SkillNacosSource`，§11.2 首段就列了不复用的两条理由），一并改掉。
- v0.5 变更：**P0-7 已实现并回写**——§10 末新增「P0-7 落地情况」（`SkillInvalidationPoller` 七处偏差：`fingerprint()` 命名、端口归属、`refresh()` vs `invalidate()`、滞后是混时钟的下界、失败轮不推进基线且吞异常保命、首轮只登记基线、装配随 P0-6）；§2.2-7 与 §7.3 末更正跨实例那一腿的现状（组件已交付、**没有进程在跑它**）；§8 末/§9.1 把 `aggregateRevision()` 统一改为 `fingerprint()` 并写清 `updated_at` 不进哈希；§15 指标名定为 `skill.invalidation.lag`（草案的 `_ms` 后缀不采用）、`skill.source.fallback.count{from,to}` 按实际常量更正、埋点清单推进到 P0-7 时点；§16 `poll-ms` 行标注「环境变量一侧已实现、Spring 一侧待接」；§17 V-11 拆出「四段各自锁住、无端到端」；§18 新增 T-15；§19 P0-7 状态行 + 全量 1352 用例。
- v0.4 变更：**P0-1…P0-5 与 P0-4a 已实现（均未提交），本轮集中回写**。新增 §7 末「P0-4 落地情况」（只读端口、目录快照退让、回源不兜旧正文等六处偏差）；更正 §7.1（不引入资源层 LRU 缓存，装配结果同条 LRU 按内容身份命中；降级计数三态标签）与 §7.2（**草案原文有误**：装配不按 `SkillFilePackage.isReadable()` 过滤，该方法只管 `skill_read`；`is_binary` 在 P0 结构上还不存在，P2-2 开启前必须补过滤）；§8 末的 `isValidId` 转发项标记已收完；§15 补「P0-4 时点的埋点清单」，把表里已报/未报分开写；§16 划掉两个不引入的配置键；§18 T-03/T-07 标完成、T-13 记两处有意不测；§19 P0-4 状态行。
- v0.3 变更：**P0-4a 已实现并回写**——§2.2-3（目录构建是「一次带回全部正文的翻页列表」，非 O(N) 次读取）、§2.2-4（`hasResources` 已被 `resourceHash` 替换）、§2.2-7（同进程失效已接通）、§2.2-9 与 §7.3（指纹快慢路径归一，缺陷已修）、§10（同进程失效标记为已实现）、§18 T-03/T-07、§19 P0-4a 状态。
- 关联规格：`docs/model-and-skill-scale-prd.md`（D7「技能目录真源」在此收敛）、`docs/skill-resources-and-sandbox-design.md`、`docs/skill-project-package-design.md`、`docs/architecture-review.md`（M5.1 智能体定义落库为直接先例）、`docs/agent-runtime-cache-spec.md`
- 范围模块：`payroll-common`（存储层、运行期仓库、管理注册表、Flyway）、`payroll-backend`（管理接口、健康检查）、`console`（技能页）、`payroll-sandbox`（投送协议）、`payroll-tests`

---

## 1. 背景与动机

技能（Skill）目前**完全存放在 Nacos 配置中心**，数据库里没有任何 `skill*` 表（V1–V21 全量核实）。这与智能体定义的状态一致，但智能体定义已在架构加固 M5.1 落库（`V21__agent_definition.sql`，表头注释即「数据库为权威，Nacos 退化为变更通知通道」），技能是配置面最后一块「以配置中心为持久层」的数据。

选择配置中心承载技能带来四类在生产环境会恶化的问题：事务与并发（§2.2）、规模与体积（§2.2）、可审计与可回滚（§2.2）、运维与备份一致性（§2.2）。本规格设计把技能迁到 **MySQL 存目录元数据 + 正文 + 版本**、**对象存储存文件包内容**的形态，并保持与 M5.1 完全同构的切换/导入/回滚语义，避免引入第二套迁移范式。

## 2. 现状（证据）

### 2.1 三条互不相同的面

| 面 | 入口 | 现状实现 | 迁移必须覆盖 |
| --- | --- | --- | --- |
| 运行期读 | `PayrollSkillCatalog` + harness `AgentSkillRepository` | `NacosSkillRepository`、`BuiltInSkillRepository`、`CompositeSkillRepository`，装配在 `SkillRepositories.java:54-67` | 目录、正文、文件包、指纹 |
| 管理面写 | `SkillAdminController`（`/api/admin/skills`，13 个端点） | 直接依赖**具体类** `NacosSkillRegistry`（`SkillAdminController.java:54,64,77`） | 全部写端点 + 导入 + 审计 |
| 工具调用/沙箱 | `SkillReadTools`、`SkillRunTools`、`SkillWorkspaceService` | 从烤进 `AgentSkill` 的快照取文件；init 时整包内联 JSON POST | 文件内容来源与体积上限 |

具体类耦合点（接口抽取的必要性证据）：`SkillAdminController.java:54`、`SkillImportService`、`PayrollCoreConfiguration.java:176,406-419`、`RuntimeHealthService.java:35,43`（健康检查也吃这个类）。**P0-5 已抽干，此处行号是草案时点**：三个调用方现依赖端口（`SkillAdminController.java:65` = `SkillStore`、`SkillImportService.java:46`、`RuntimeHealthService.java:44`），类外唯一还写具体实现的地方是装配点（`PayrollCoreConfiguration.java:449-452` 建 bean、`:466` 按 `SkillsSource` 二选一）。

### 2.2 Nacos 形态的硬约束

1. **无事务、无行级并发控制**。文件包写入是「读整包 → 改 → 整体 publish」，`NacosSkillRegistry.replaceFiles()` 与 `importProject()` 只能靠 `synchronized`（同 JVM）保护，跨实例并发保存会互相覆盖。`delete()` 是两次独立 `remove`，中途失败留下孤儿文件包（代码里已明确接受这个不一致，`NacosSkillRegistry.java:483-512` 注释「残留文件包在下次保存正文时重新绑定哈希」）。
2. **正文与文件包是两份配置，靠 `mdHash` 手工对齐**。`NacosSkillRepository.java:436-481`：文件包只在 `mdHash` 与正文匹配时才挂载，否则静默忽略。迁移期间/之后任何「正文改了、文件包没重绑」都会表现为**技能能用但资源全部消失**，且没有告警。
3. **目录构建一次拉全量正文**。`NacosSkillRepository.rebuildCatalog()`（`:219-264`）与 `NacosSkillRegistry.snapshot()`（`:586-609`）都只调**一次** `listResult("*" + DATA_ID_SUFFIX)`，翻页 200 条/页、上限 50 页（`NacosConfigClient.java:28,150-182`）——请求数是 O(1)，不是 O(N)。真正的代价在**响应体**：Nacos 的列表接口连 `content` 一起返回，所以目录构建的字节数 = 全部技能正文之和，技能数上千 × 平均几 KB 就是一次数 MB 的 HTTP + 全量 YAML frontmatter 解析。`SkillRepositories` 的目录快照 TTL 默认 60s（`NacosSkillSettings.java:67`），到点即重付这笔开销。
4. **`hasResources` 判定不可靠 → 已由 P0-4a 移除**。`NacosSkillRepository.java` 的 `enrich()` 只在正文已在缓存里时才回填 `hasResources`，冷启动目录里所有技能都显示「无资源」。现状：该布尔字段已从 `SkillManifest` 删除，替换为 `String resourceHash`（空串语义是「尚未加载」，不再谎报「无资源」）；`enrich()` 改为从正文 LRU 回填真实资源哈希，`PayrollAgentFactory.skillFingerprint()` 在读清单前先 `getSkill()` 预热，因此**进入指纹的值恒为真值**。剩余消费方只有展示层（管理台的文件数来自 `NacosSkillRegistry` 自己的快照，与本字段无关）。
5. **单配置项体积天花板**。文件包是**一个** Nacos 配置项里的内联 JSON（`SkillFilePackage.java:32,150-170`），当前配额 512KB/文件、4MB/包、200 文件（`SkillFileSettings.java:13-15`）。整个包被塞进一个配置项，读取时无法只取一个文件：`fileContent()`（`NacosSkillRegistry.java:298-316`）也要先解析整包。⚠️ 待核实项：Nacos 服务端 `maxConfigSize`/`encryptedDataKey` 类限制在本机部署版本上的实际取值——这决定 4MB 包能否稳定发布，属于「已存在风险」，不因迁移而消失，但迁移后彻底解除。
6. **不支持二进制**。`SkillFilePackage` 的 envelope 是 JSON 字符串字段，`SkillProjectParser.java:229-256` 明确跳过非 UTF-8/二进制文件。`assets/` 目录里的图片、字体目前落不进技能包。
7. **缓存不联动（同进程部分已由 P0-4a 修复）**。`SkillRepositories.resetShared()`（`:47-52`）**零调用方**；管理台保存正文/文件后只清自己的 `snapshot` 和 `fileCache`（`NacosSkillRegistry.java:319-356`），运行期 `NacosSkillRepository` 的目录 TTL 快照与正文 LRU 不感知；多实例部署下 A 节点发布、B 节点最长 60s + 正文 LRU 生命周期后才生效，且没有任何指标能观测到这个滞后。现状：新增 `PayrollSkillCatalog.invalidate()`（`CompositeSkillRepository` 逐源转发、`ScopedSkillRepository` 委托）与 `SkillRepositories.invalidateShared()`，`NacosSkillRegistry` 的 5 个写点（`save`/`replaceFiles`/`importProject` ×2/`delete`）统一走 `dropSnapshot()` 触发它——**同进程**管理写立即生效。跨实例这一腿 P0-7 交出组件（`SkillInvalidationPoller` + `skill.invalidations.count{trigger}` / `skill.invalidation.lag`），**P0-6 已把它装进进程**：`@Bean(destroyMethod="close")` + `payroll.skill.invalidation.poll-ms` 走 `@Value`，只挂 db 源（`PayrollCoreConfiguration.java:508`，见 §11.1 第 6 条）。Nacos 源不在这条覆盖范围内——端口是 db 源的 `SkillRuntimeReader`，它仍只靠目录 TTL 兜底（见 §10 表）。
8. **回滚与审计缺失**。Nacos 的历史版本是运维层能力，业务侧无「技能版本列表 / 回到上一版」接口；写审计只记动作与摘要，无法回答「上周三这个技能的正文是什么」。
9. **改文件包不会让已缓存的智能体失效（现存缺陷 → 已由 P0-4a 修复）**。原快路径指纹是 `id:source:contentHash:hasResources:execution`，资源只贡献一个**布尔**（`res`/空）：只改参考文件、正文不变时指纹不变 → `AgentCache` 不重建 → 模型继续读旧文件，直到条目被淘汰（30min 空闲 / 64 条 LRU / 15min 压力）。慢路径反而用了 `getResources().hashCode()`，两条路径语义不一致，还会在技能正文由冷转热（`hasResources` 从空变有）时凭空造出一次重建。现状（`PayrollAgentFactory.java:467-512`、`SkillManifests.resourceHash()`）：快慢路径合并为**单条**路径，资源贡献一个按路径排序聚合的确定性哈希；指纹先 `getSkill()` 预热再读清单，保证冷启动与热态算出同一个值。回归测试见 `AgentCacheTest.skillFingerprintCoversResourceContentAndPath` / `NacosSkillRepositoryTest.resourceHashTracksFilePackage`。
10. **资源是烤进 `AgentSkill` 的，不是懒加载**。`NacosSkillRepository.withResources()`（`:441-451`）把整包文件逐条 `addResource(path, content)` 塞进 `AgentSkill`，`SkillReadTools.java:22` 明确「资源来自 `AgentSkill#getResources()`（内存，不落盘）」。这带来两条硬约束：① 换存储后**装配一个技能仍要一次拿全所有文件**，不能改成按需读；② `getResources()` 是 `Map<String,String>`，**二进制内容结构上无法进入 LLM 可见的资源**（§13-3 的二进制只服务沙箱）。

### 2.3 可直接复用的既有先例

| 先例 | 位置 | 复用点 |
| --- | --- | --- |
| 智能体定义落库 | `V21__agent_definition.sql`、`JdbcAgentDefinitionRepository.java:35-314`、`NacosAgentRegistry.java` source 分支、`PayrollCoreConfiguration.java:381-411`（`agentDefinitionRepository` `:383`、`agentRegistry` 的 source 形参 `:395`、`agentDefinitionImportRunner` `:404-411`） | 单源开关 + 空表幂等导入 + 行级 `revision` + `FOR UPDATE` 聚合 revision + `PayrollConflictException` 409 + `SQLState 23*` 幂等 |
| 版本表 | `V2__knowledge_base.sql:35-46`（`kb_config_version`） | `version_id BIGINT AUTO_INCREMENT` + `version_no INT` + `change_note` + `created_by/created_at` |
| Blob 落盘 | `KbStorageService.java:32-98`（`./data/kb-uploads`、`<seriesId>/<uuid>.<ext>`、sha256、越界检查）、`ChatAttachmentService.java:203-223` | 本地磁盘适配器写法、`storage_key VARCHAR(512)`、sha256 校验 |
| 加密端口 | `ModelCredentialCipher.java:17-126` + `V15__model_credential.sql` | AES-256-GCM + AAD=业务 id + fail-closed（若 D8 选加密） |
| 内容指纹 | `ConfigRevision.java`（SHA-256，`ABSENT` 常量）、`SkillFilePackage.mdHash()` | 版本去重、blob 内容寻址 |
| 保留任务 | `PayrollCoreConfiguration.java:319,959,983,1021` 的 `ApplicationRunner` 清理/保留 job（草案写的 `:543,719,764,897` 已随本仓库 bean 增删漂移） | 版本裁剪 + 孤儿 blob GC 的调度形态 |
| 指标 | `SkillMetrics.java:22-30`（`skill.catalog.build.duration`、`skill.content.cache.hit_ratio` 等，已带 `source` tag） | 新源直接沿用同名指标做切换前后对比 |

**注意**：`KbStorageService` 与 `ChatAttachmentService` 两个 blob 仓库**都没有保留/清理任务**。技能包不能重复这个错误，GC 进入 P0 范围（§14）。

## 3. 目标 / 非目标 / 红线

### 3.1 目标

- G1：MySQL 成为技能的**唯一权威存储**（目录元数据、正文、版本、文件包清单），Nacos 退化为可选的发布/通知通道或直接退役。
- G2：文件包内容进对象存储语义层（先本地磁盘适配器，端口预留 S3/MinIO），解除「单配置项内联 JSON」的体积与并发限制。
- G3：目录构建从「一次列表带回全部正文」变成**一条不含正文的 SQL**；`SkillManifest.resourceHash` 由 `resource_hash` 列直填，不再依赖正文是否已在 LRU 里（P0-4a 已完成字段替换，DB 侧只缺列）。
- G4：技能正文与文件包**同事务**变更，彻底移除 `mdHash` 手工对齐这一类静默失效。
- G5：可回滚到任意历史版本；写操作有 `revision` 乐观并发与 409 语义；审计能定位到版本。
- G6：跨实例生效滞后**可观测且有上界**（§10 门槛）。
- G7：与 M5.1 同构的灰度路径：`payroll.skills.source=nacos|db` + 空表幂等导入 + 一键回退。

### 3.2 非目标

- N1：不改技能的**提示词注入策略**（预注入/目录模式由 `SkillPromptBudget` 决定，见 `model-and-skill-scale-prd.md` D5），只改数据来源。
- N2：不把技能 `data/` 大数据集纳入对象存储（保留 setup 生成语义，见 D9）。
- N3：不引入向量召回/语义检索（PRD D8 已推迟到 P2 之外）。
- N4：不改沙箱的隔离模型（只读 rootfs、tmpfs、1 CPU/1GB、uid 65534、cap-drop ALL 保持）。
- N5：不做技能市场/多租户配额/技能级 RBAC 细分（另案）。
- N6：不在本次引入 MyBatis；沿用 `PayrollDatabase.connection()` + 预编译语句（与 `JdbcAgentDefinitionRepository` 一致）。
- N7：**只迁技能**。流程定义（`NacosFlowRegistry`）、模型连接（`ModelConnectionRegistry`/`payroll-models.json`）、运行时设置（`NacosRuntimeSettings`，含 `activeAgent` 与写确认开关）继续留在 Nacos。切库后**不能宣称「配置面已全面落库」**，文档与 `/health` 都要按「技能已落库、其余仍在 Nacos」如实表述（M5.1 也有同样的遗留，见附录 A）。

### 3.3 红线

- R1：**不静默回落**。DB 读失败要显式报错并计入指标，不能悄悄退回 Nacos 或内置（对齐 M5.1 与 `MODEL_CREDENTIAL_UNREADABLE` 的 fail-closed 立场）。
- R2：**不丢存量**。导入前必须跑对账（§11.3），Nacos 配置在切库后至少保留一个发布周期，删除动作单独排期。
- R3：**不放宽路径白名单**。`SkillFilePackage.normalizePath()`（`:214-252`，≤200 字符、`^[A-Za-z0-9._-]+(/...)*$`、根文件白名单、只允许 `references/ scripts/ assets/`、`scripts/**` 只允许 `.py|.sh|.js`）逐条保留，二进制只允许进入 `assets/`（§13）。
- R4：**SQL 全部预编译**，`path`、`id` 一律作为参数，禁止拼接。
- R5：新增 DDL 只写 `payroll-common/src/main/resources/db/migration/common/V22__skill_definition.sql`，必须同时跑通 H2 `MODE=MySQL` 与 MySQL 8（AGENTS.md 约束）。
- R6：技能正文/文件包里可能含密钥，写接口必须过 secret 扫描（复用 dev-team 的补丁扫描器）后才落库，命中即 400。

## 4. 方案选型

### 4.1 候选

| 方案 | 描述 | 事务 | 规模 | 二进制 | 改动量 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| A 维持 Nacos | 补缓存失效 + 提高体积上限 | ✗ | ✗ | ✗ | 小 | 否决：§2.2 的 1/2/5/6 无法在配置中心模型内解决 |
| **B MySQL 元数据+正文，对象存储文件包** | 目录/正文/版本入 `skill_definition`/`skill_version`，文件内容 blob 化 | ✓ | ✓ | ✓ | 中 | **采纳** |
| C MySQL 存全部（含文件字节 LONGBLOB） | 不引入 blob 端口 | ✓ | 部分（库体积、备份膨胀） | ✓ | 中小 | 备选：`DevArtifactStore.java:64-67` 有先例，但技能包 4MB×N 会把业务库推到 GB 级，备份/主从延迟代价明确，否决 |
| D 直接上 S3/MinIO（含 SDK） | 现在就引入对象存储 SDK | ✓ | ✓ | ✓ | 中大 | 推迟到 P3：仓库当前无任何 S3 依赖，先把**端口**定清楚，介质可后置（D2） |

### 4.2 结论（D1–D4 已按推荐值确认，见 §22）

- **正文与版本留在 MySQL**：正文是文本、量级 KB、必须与元数据同事务、且缓存指纹（`skillFingerprint`）高频读取它，放对象存储只增加一次网络往返和一致性窗口。
- **文件包内容进对象存储**：per-file 一条元数据 + 内容寻址 blob，读单文件不再解析整包，天然支持二进制与大文件。
- **介质适配器化**：P0 落本地磁盘（与 KB 附件同构，零新依赖），P3 提供 S3/MinIO 适配器；业务代码只依赖 `SkillBlobStore` 端口。

## 5. 数据模型（`V22__skill_definition.sql`）

### 5.1 表与关系

```
skill_definition (1) ──< skill_version (N)          正文不可变版本链
      │  current_version_id →  skill_version.id
      └──< skill_resource (N) ──> skill_blob (1)     文件包 per-file → 内容寻址 blob
```

- `skill_definition`：**目录表**，列表/指纹只读它，一行一技能，含 `resource_count` 与 `total_bytes` 反范式列（修 G3/§2.2-4）。
- `skill_version`：**不可变正文快照**（D3）。写入即追加，`current_version_id` 指向生效版本，回滚只改指针 +  bump `revision`。
- `skill_resource`：某版本挂载的文件清单，`UNIQUE(version_id, path)`；版本维度绑定，取代 `mdHash` 字符串对齐。
- `skill_blob`：内容寻址 blob 目录，主键即 `sha256`，天然去重（同一文件跨版本只存一份）。

### 5.2 DDL（V22 已落地）

```sql
-- 技能存储落库（架构加固 M5.2）：数据库为权威，Nacos 退化为可选发布通道。
-- 与 V21 agent_definition 同构：revision 行级递增，写前重读比对（409）。
-- skill_id 列宽与 SkillIds 的 ID 规则（^[a-z][a-z0-9-]{1,63}$，上限 64）同源：
-- 超长的 Nacos dataId 本就过不了校验，列宽放宽只会让库里有读不到的行。
-- V22 在基线之上，是唯一会被 Flyway 真正执行的脚本：上一进程建到一半被杀时对象已在而迁移记录
-- 缺失，下次启动必须原样重放，所以建表带 IF NOT EXISTS。索引不各写一条 CREATE INDEX——MySQL 8 的
-- CREATE INDEX 不支持该子句（V1 头注已记，H2 放行而真库报错的语法只能靠约定挡住），
-- 折进建表体后索引随表一起幂等（V10 同构）。
CREATE TABLE IF NOT EXISTS skill_definition (
    skill_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL DEFAULT '',
    tags VARCHAR(512) NOT NULL DEFAULT '',
    skill_version VARCHAR(32) NOT NULL DEFAULT '',
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL DEFAULT 0,
    execution_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    content_hash CHAR(64) NOT NULL,
    content_length INT NOT NULL DEFAULT 0,
    resource_count INT NOT NULL DEFAULT 0,
    resource_bytes BIGINT NOT NULL DEFAULT 0,
    resource_hash CHAR(64) NOT NULL DEFAULT '',
    current_version_id BIGINT,
    revision BIGINT NOT NULL,
    created_by VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_skill_def_updated (updated_at, skill_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 正文按版本追加（D3a）：回滚只移动 skill_definition.current_version_id，不改写历史行。
CREATE TABLE IF NOT EXISTS skill_version (
    version_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    skill_id VARCHAR(64) NOT NULL,
    version_no INT NOT NULL,
    body_md LONGTEXT NOT NULL,
    meta_json TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    content_length INT NOT NULL DEFAULT 0,
    change_note VARCHAR(200) NOT NULL DEFAULT '',
    created_by VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_version_no (skill_id, version_no),
    KEY idx_skill_version_hash (content_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 文件清单挂在版本上，取代现网靠 mdHash 字符串对齐（结构上不可能错配）。
CREATE TABLE IF NOT EXISTS skill_resource (
    resource_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id BIGINT NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    path VARCHAR(200) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    media_type VARCHAR(64) NOT NULL DEFAULT 'text/plain',
    is_binary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_resource_path (version_id, path),
    KEY idx_skill_resource_skill (skill_id, path),
    KEY idx_skill_resource_blob (sha256)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- blob 目录只存元数据，字节在对象存储/本地盘（D2a、D6a）；主键即内容指纹，跨版本去重。
CREATE TABLE IF NOT EXISTS skill_blob (
    sha256 CHAR(64) PRIMARY KEY,
    storage_key VARCHAR(512) NOT NULL,
    size_bytes BIGINT NOT NULL,
    media_type VARCHAR(64) NOT NULL DEFAULT 'text/plain',
    storage_location VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

要点：
- **幂等靠 `CREATE TABLE IF NOT EXISTS`，索引折进建表体**（脚本头注即理由）：MySQL 的 DDL 不进事务，进程在 V22 中途被杀会留下「对象已建好、`flyway_schema_history` 却没有这一版」的库，下次启动原样重放必须能自洽——`SchemaMigrationTest.partiallyMigratedLegacyDatabaseReplaysIdempotently` 就是照这个形态建的库。V1–V21 被基线盖住、永不再执行，所以只有 V22 需要这层保护。索引不能各写一条 `CREATE UNIQUE INDEX IF NOT EXISTS`：该子句 MySQL 8 直接语法报错（V1 头注已记），H2 却放行。折进 `CREATE TABLE` 体（`UNIQUE KEY`/`KEY`，与 V10 同构）后，索引随表整体跳过，两种库都能重放。
- **唯一键的索引名在两库上不同形**：H2(`MODE=MySQL`) 把 `UNIQUE KEY uk_x (...)` 建成约束 `UK_X` + 派生索引 `UK_X_INDEX_n`，MySQL 则直接用 `uk_x`。`DdlSyntaxTest` 原先按索引名精确比对，等于只对 H2 的 `CREATE UNIQUE INDEX` 形态成立；现改为**名字按前缀认、列序与唯一性按完整签名比**，改名/漏列/颠倒列序照样红。非唯一的 `KEY idx_x (...)` 两库同名，不受影响。
- **草案的 `binary` 列改名 `is_binary`**：`BINARY` 在 MySQL 与 H2 里都是保留字，原样建表直接报语法错。同理 `skill_resource` 上草案的 `idx_skill_resource_ver (version_id, path)` 与 `uk_skill_resource_path` 列序完全相同，属纯冗余索引，已删。列名/索引名的保留字回归由 `DdlSyntaxTest` 扫全库元数据挡住。
- **`skill_id VARCHAR(64)` 与 ID 规则同源**：正则、长度上限与列宽都由 `SkillIds` 提供，`DdlSyntaxTest` 断言所有 `skill_id` 列宽 == `SkillIds.MAX_ID_LENGTH` 并实插一条满长 ID，列宽再次放宽或收窄都会红。
- `content_hash` 定义为 `SkillManifests.contentHash(净正文)`（`SkillManifests.java:21-28`，SHA-256 hex over UTF-8），与现网指纹算法逐字节一致，导入器必须复用同一个方法而不是另写一套。
- `resource_hash = SHA-256(按 path 升序拼接 "path:sha256;")`，是 §7.3 修现存缺陷的关键：它进入 `SkillManifest` 与智能体指纹，使「只改文件包」也能失效缓存。
- `skill_blob` 主键即内容指纹，同一文件跨版本只存一份（去重）；`storage_location` 为 P3 多介质共存预留。体量估算：200 技能 × 平均 10KB 正文 × 20 版 ≈ 40MB，文件包按 D6 不进业务库 → **业务库增量可控在 100MB 内**，这是 D1 选 (a) 的量化依据。
- 未加外键：本仓库既有表（`kb_chunk`、`kb_document` 等）一律不用外键，靠应用层 + GC 任务维护，保持一致以免 H2/MySQL 行为差异。
- `body_md LONGTEXT`：MySQL 侧受 `max_allowed_packet` 约束，需部署核实（默认 4MB/64MB 因版本而异）；正文配额上限远低于此（`SkillContentRules` 与提示词预算 24k 字符量级）。
- 双验状态：H2(`MODE=MySQL`) 由 `SchemaMigrationTest` + `DdlSyntaxTest` 覆盖（全新库、V21 历史库补建、半迁移库重放三种形态）；MySQL 侧走 `DdlSyntaxTest.sharedMigrationsApplyOnRealMysql`，该用例在 `PAYROLL_TEST_MYSQL_JDBC` 未配置时 **SKIPPED**，因此当前全绿并不代表 MySQL 已验证——切 db 源前必须挂上专用测试库跑通一次。
- `tags` 用 `VARCHAR(512)` 存逗号分隔（与 `SkillManifest.tags` 转换在仓储层做），不引 JSON 函数，避免方言分叉。
- **`sort_order` 是红线列**：目录顺序直接进入系统提示词与测试断言（`BuiltInSkillRepository.java:22-27` 静态块注释「保持 RoleSkills 的声明顺序：目录顺序进入系统提示词与测试断言」；`NacosSkillRegistry.list()` 同样是内置在前、远端按 id 升序）。因此 `listManifests()` 必须 `ORDER BY sort_order ASC, skill_id ASC`，且**导入器要按现网等价顺序回填 `sort_order`**（内置技能按其声明序取 0..n-1，远端技能接在其后按 id 升序），否则切库当天提示词逐字符变化、`SkillPromptBudgetTest`/`AgentRouterTest` 一类快照断言全红。

### 5.3 与现有 Nacos 数据格式的映射

| Nacos 现状 | 迁移后 |
| --- | --- |
| `<skill-id>.md` 的 frontmatter（`SkillFrontmatter`） | 拆列：`name/description/tags/skill_version/execution_enabled` + `meta_json` 保留未知字段 |
| `<skill-id>.md` 正文 | `skill_version.body_md`（去掉 frontmatter 的净正文，与 `content_length` 同语义） |
| `<skill-id>.files.json` 的 `mdHash` | 由 `skill_resource.version_id` 取代（结构上不可能错配）；`content_hash` 仍保留用于指纹与去重 |
| `files[].path/content` | `skill_resource.path` + `skill_blob`（内容寻址） |
| `version:2` envelope | 不再需要；`SkillFilePackage.compose/parse` 只保留读侧兼容（供导入器使用），写侧在 db 源下停用 |

## 6. `SkillBlobStore` 端口与适配器

**已落地（2026-11-22，P0-2，未提交）**：`payroll-common/.../skill/SkillBlobStore.java` + `LocalDiskSkillBlobStore.java`，与下方签名一致，仅两处形状差异——`BlobRef` 内嵌进端口（与 `SkillFilePackage.SkillFile` 同风格，避免出现只装一个类型的第二层包），以及 `mediaType` **由调用方给出、原样回显**，介质本身不保存它（权威在 `skill_blob.media_type`，同 sha 二次写入返回的 ref 会带新的 mediaType）。

```java
public interface SkillBlobStore {
    /** 内容寻址写入；已存在则幂等返回。key 由实现按 sha256 派生，调用方不控制路径。 */
    BlobRef put(byte[] content, String mediaType);
    /** 缺失返回 empty，由调用方决定「技能资源缺失」文案；指纹与字节不符必须抛，不返回脏数据。 */
    Optional<byte[]> get(String sha256);
    /** 批量取：装配一个技能的全部资源必须一次拿全（§2.2-10），逐文件往返不可接受。 */
    Map<String, byte[]> getAll(Collection<String> sha256s);
    /** 指纹形态不合法按不存在处理：对账巡检不能被一行脏数据打断。 */
    boolean exists(String sha256);
    void delete(String sha256);
    String location();          // 供健康检查与 repositoryInfo 展示
    record BlobRef(String sha256, String storageKey, long sizeBytes, String mediaType) {}
}
```

- 适配器 1（P0）：`LocalDiskSkillBlobStore`，根目录 `payroll.skill.blob.root`（默认 `./data/skill-blobs`），布局 `<sha[0:2]>/<sha>`，写入用临时文件 + `ATOMIC_MOVE`，读取时**校验 sha256 一致**（照抄 `KbStorageService` 的越界检查与 sha256 校验思路，`KbStorageService.java:54-98`）。key 不接受用户输入，无路径穿越面。
  - 落地取值：根目录与单文件上限先走构造参数 + `fromEnvironment()`（`PAYROLL_SKILL_BLOB_ROOT`、`PAYROLL_SKILL_BLOB_MAX_BYTES`，默认 512KB = `SkillFileSettings.DEFAULT_MAX_FILE_BYTES`，切库第一天不放宽任何现网配额）；Spring 属性键 `payroll.skill.blob.root` 随 P0-6 装配一起接进来，届时 `fromEnvironment()` 只作为 CLI/测试入口。**P0-6 已接完**：两个键都进 `@Value`（`PayrollCoreConfiguration.java:432`），`fromEnvironment()` 只剩两个非 Spring 入口在用——对账命令行 `PayrollApplication --skill-migration-check` 与 `SkillRepositories` 的懒加载共享槽位（`SkillRepositories.java:105`，后者正是 §11.1 第 4 条那条装配陷阱要绕开的实例）。
  - 并发实测结论（Windows）：两份同内容并发 `ATOMIC_MOVE` 到同一目标，失败方拿到的是 `AccessDeniedException` 而非 `FileAlreadyExistsException`，适配器按类型判「目标已在位」后再清理临时文件，否则并发导入会把成功报成写失败（`LocalDiskSkillBlobStoreTest.concurrentPutOfSameContentConverges`）。
  - `delete` 删不掉只 `log.warn`：调用点已经是「事务失败后的补偿删除」，再抛会盖掉真正的失败原因；孤儿文件留给 P2 的保留期巡检（T-06）。
- 适配器 2（P3）：`S3SkillBlobStore`（S3 协议族，MinIO/AWS/OSS 通用）。届时新增依赖单独评审；`storage_location` 列已为多介质共存预留。
- 失败语义：`get` 缺失 → `PayrollException("技能资源缺失：<path>")`（不回落 Nacos，R1）；磁盘写失败 → 整个保存事务回滚（先写 DB 事务、blob 用「先写后提交 + 事务失败即删」的顺序，见 §9.2）。**缺失与损坏是两条路**：`get` 返回 empty 表示不存在，抛异常表示存在但校验不过，仓储层不能把两者压成同一个错误。
- 若 D8 选加密：`EncryptedSkillBlobStore` 装饰器复用 `ModelCredentialCipher` 的 AES-256-GCM + AAD=`sha256` 方案，主密钥仍是 `PAYROLL_SECRET_KEY`，解密失败 fail-closed。

## 7. 运行期读路径：`DbSkillRepository`

`final class DbSkillRepository implements AgentSkillRepository, PayrollSkillCatalog`（接口契约逐项对齐 `BuiltInSkillRepository.java:29-120`）。

| 方法 | 实现 | 复杂度对比现状 |
| --- | --- | --- |
| `listManifests()` | `SELECT` 单条查 `skill_definition`（不 JOIN 正文/资源），构造 `SkillManifest` 列表 | 一次**不带正文**的查询 vs 现在一次带回全部正文的翻页列表（§2.2-3） |
| `manifest(id)` | 优先命中目录快照；缺失时 1 行 `SELECT` | `resourceHash` 来自 `resource_hash` 列，**不依赖正文是否已缓存**（§2.2-4） |
| `getSkills(ids)` | `SELECT ... JOIN skill_version WHERE skill_id IN (...)`，分片 ≤500 参数 | 批量一次；保留正文 LRU |
| `nameById()` | 目录快照派生 | 同 |
| `getSkill(name)` | 正文 + **全部资源一次性装配**（见 §7.2，不能懒加载） | 一次 JOIN + 一次 `getAll` |
| `save/delete/isWriteable` | 只读，与 `BuiltInSkillRepository` 同形：`save/delete` 返回 `false`，写全走 §8 的 `SkillStore` | 草案曾写「委托 §8 的仓储」，实际不该委托：运行期实例是共享单例，谁都能经由它删库 |
| `skillExists(name)` | 只查目录行，不装配正文 | 现网 Nacos 版为了回答「有没有」会拉一次正文 |

### 7.1 缓存与失效

- 目录快照 `volatile Catalog`（`Map<String,Row>` 按 `ORDER BY sort_order, skill_id` 保序 + `fetchedAt`），TTL 复用 `payroll.skill.catalog.ttl-ms`（默认 60000，读侧环境变量 `PAYROLL_SKILL_CATALOG_TTL_MS`）+ §10 的主动失效。**快照里不放 `aggregateRevision`**：那是 §9.1 轮询器的输入，混进目录快照会让「目录新于快照」和「revision 新于快照」变成两件容易走偏的事。
- 正文 LRU 复用现有配额 256 条 / 8,000,000 字符（`NacosSkillRepository.java:33-36`），命中率继续打 `SkillMetrics.contentLoaded(source, hit, chars)`。命中条件改为**内容身份** `(version_id, resource_hash)`：身份不符即回源，因此条目自身不设 TTL。理由是版本行不可变、字节按 sha 寻址，身份相同就必然内容相同——比 Nacos 的「TTL 到点重读」严格，也消掉了「目录说新版、正文还是旧版」这段窗口。
- ~~资源内容 LRU：新增 `payroll.skill.resource-cache.{max-entries:128,max-bytes:33554432}`~~ —— **P0-4 不引入**：§7.2 要求资源随正文一次装配进 `AgentSkill`，装配结果已整体在上面这条 LRU 里，再开一层「未装配资源缓存」没有读它的代码路径。`NacosSkillRegistry.java:155` 那组配额管的是管理台逐文件预览，与运行期读路径无关。
- 兜底：装配仍是 `CompositeSkillRepository[Db, BuiltIn]`，DB 不可用时内置技能可读，但**必须**打 `skill.source.fallback{to=builtin}` 计数并在 `/health` 亮黄，不允许无声降级。计数标签按最终供货方分三种，已由 `CompositeSkillRepositoryTest` 锁死：逐 id 读（`getSkill`/`manifest`）由后继来源顶上打 `to=<该来源>`；整表合并读（`listManifests`/`getAllSkillNames`/`getSkills`/`nameById`）无法指名某一个来源，统一打 `to=composite`；没有任何来源供货打 `to=none`。

### 7.2 资源必须一次装配（不是可选优化）

`AgentSkill` 一旦构造完成就烤进智能体实例（§2.2-10），运行期没有仓库可回查。因此：
- `getSkill()`/`getSkills()` 返回前必须把 `skill_resource` 全部 `getAll()` 读出并 `addResource(path, content)`，语义与 `NacosSkillRepository.withResources()`（`:453-464`）逐条对齐；
- **装配不过滤路径，`SkillFilePackage.isReadable()` 只管 `skill_read`**（本条原文写错，2026-11-23 按代码更正）。现网 `NacosSkillRepository.withResources()`（`:453-464`）把文件包里的**每一个**文件都 `addResource`，一个都没筛；db 侧 `JdbcSkillStore.runtimeData()` → `DbSkillRepository.toSkill()` 同样整批挂载。筛不得：`setup.sh` 与 `scripts/**` 正是沙箱要用的那批——`SkillInitRequirements.java:35` 靠它们判定技能是否需要 init，`SkillRunTools.java:54,73` 靠它们拼执行命令。装进 `AgentSkill` 不等于进模型上下文，`isReadable` 拦的是 `skill_read` 的入参（`normalizeReadPath`，`SkillFilePackage.java:258-265`），`assets/**` 与 `setup.sh` 因此读不到但照样在包里。
- 二进制行在 P0 **结构上还不存在**：写路径 `JdbcSkillStore.writeResources()`（`:1099-1107`）只做 `file.content().getBytes(UTF_8)` 并以 `MEDIA_TYPE_TEXT` 落盘，`is_binary` 恒为 `FALSE`（`SkillFile.content()` 本来就是 `String`）。P2-2 开 `assets/` 白名单时必须同时给装配加一道 `is_binary` 过滤——`AgentSkill` 的资源位是 `Map<String,String>`，二进制按 UTF-8 解出来是乱码，而且会污染 `contentLoaded` 的字符数指标。这条列入 P2-2 前置检查。
- 装配结果按 `(version_id, resource_hash)` 缓存，一个版本每节点只组一次；代价对比：现网是「解析一个 ≤4MB JSON」，新方案是「≤200 次本地小文件读 + 一次 JOIN」，同量级（`getAll` 批量接口就是为避免 200 次远程往返而存在）；
- mdHash 错配这一类静默忽略在 db 源下**结构上不可能发生**（资源按 `version_id` 绑死），因此 `loadFiles()` 里四条 `log.warn(... 已忽略)` 分支在新实现中不存在——这是本次迁移最实质的可靠性收益。

### 7.3 顺带修复：文件包变更不失效（现存缺陷 → **已实现，P0-4a**）

原缺陷：只改文件包不会让已缓存智能体失效（§2.2-9）。已落地的修法：
1. `SkillManifest.hasResources` **删除**，改为 `String resourceHash`（`SkillManifests.resourceHash(Map)` = 按 path 升序拼 `path:sha256;` 再 SHA-256）；`NacosSkillRepository`/`BuiltInSkillRepository` 两侧同源现算，DB 侧后续由 `resource_hash` 列直填；
2. `PayrollAgentFactory.skillFingerprint()`（`:467-512`）快慢路径**合并为一条**，资源段追加 `:{resourceHash}`；指纹先 `getSkill()` 预热，避免冷/热态算出不同值导致凭空重建；
3. 管理写触发运行期失效：`PayrollSkillCatalog.invalidate()` + `SkillRepositories.invalidateShared()`，`NacosSkillRegistry` 全部写点收敛到 `dropSnapshot()`（§2.2-7 同进程部分）；
4. 一次性代价：指纹字符串变化会让切库当天所有实例重建一次，与 K1 合并观察；本次改动上线当天同样会有一次全量重建，属预期。

未完成部分：**跨实例失效只剩 Nacos 源这一个缺口**。P0-7 的 `SkillInvalidationPoller` 吃的是 `SkillRuntimeReader`（只有 db 侧实现），P0-6 已把它接成 bean（只挂 db 源，`poll-ms` 走 `@Value`），因此 **db 源的跨节点滞后现在有 15s 上界**；Nacos 侧仍只由目录 TTL（`payroll.skill.catalog.ttl-ms`）兜底，补它需要 Nacos 的变更推送订阅或一个 Nacos 侧的 `SkillRuntimeReader` 实现，都不在 P0 范围内（记在 §20 风险）。滞后指标本身已埋好（`skill.invalidation.lag`）。`resource_hash` 列随 P0-1 建好、P0-3 写入、P0-4 读路径已改为**直接取列**，不再现算。

与智能体缓存的联动（关键，勿漏）：`definitionSignature` 含 `skillFingerprint(skillIds)`。因此
- `source` 由 `nacos` 变为 `db` 会让**所有**智能体实例在切换瞬间重建一次缓存（一次性抖动，可接受，需在 §15 埋点观测 `agent.cache.rebuild.count{reason=skill_fingerprint}`）；
- 只要 `content_hash`/`resource_hash`/`execution_enabled` 三者不变，指纹不变，缓存不重建——**已由 `SkillFingerprintContractTest` 在 db 源锁死**（§18 T-07），Nacos 源那半边此前已由 `AgentCacheTest`/`NacosSkillRepositoryTest` 覆盖。

**P0-4 落地情况（2026-11-23，未提交）** —— `DbSkillRepository`（`payroll-common/.../skill/DbSkillRepository.java`，411 行）按上表落完，另有六处与草案的有意偏差：

1. **只收 `SkillRuntimeReader`，不收 `SkillStore`**：运行期需要的读能力就三件（`catalog()` 单查询目录、`find(id)` 单行补查、`runtimeData(rows)` 批量取正文+资源），`JdbcSkillStore` 一个类同时实现两个端口，读路径的构造器只暴露只读那个。理由很直白：`DbSkillRepository` 是跨会话共享的单例，把 13 个管理写方法递到运行期等于给任何拿到仓库引用的代码一条删库路径（§13-1 的最小权限）。副作用是 `save/delete` 只能保持只读形态，见上表。
2. **`source` 常量与 store 同源**：`DbSkillRepository.SOURCE = JdbcSkillStore.SOURCE_DB = "db"`，不再各写一份字面量——指纹字符串里带 `source`（§7.3），两边拼错一个字母就是每次对话重建一次智能体。
3. **目录读失败沿用上次成功快照，且把失败时刻计入 TTL**：`rebuild()` 捕获 `PayrollUnavailableException` 后复用 `lastGood` 并以「本次尝试时刻」重建快照，库整段抖动时不会每次读都重试一次全表查询把连接池拖死（与 §10 的 K5 缓解同方向）。**冷启动没有历史快照则原样抛出**：那正是 §7.1 要求「计数 + 亮黄」的场景，吞掉异常等于骗 `CompositeSkillRepository` 说库里真没技能，会静默退化成只有内置技能。
4. **回源失败一律不兜旧正文**：`assemble()` 里 `runtimeData()` 抛出的异常直接上抛。缓存条目的身份取自目录行的 `(version_id, resource_hash)`，正文却回退到上一版，等于给智能体缓存配上「新指纹 + 旧提示词」——V-09 明确禁止的静默降级，比报错严重。有测试锁死（`assemblyFailurePropagatesInsteadOfServingStaleBody`）。
5. **目录行在、版本行不在 → 读作缺席并负缓存**：`skill_version` 缺行时该技能不进结果，同一 `version_id` 只 INFO 一次（`missingWarned` 集合，恢复可读版本时再 INFO 一次），条目照常进 LRU 以免每次对话重查。半迁移的库不该让整个对话挂掉；这种行是 §11.3 对账门禁的输出，处置归导入器不归读路径。
6. **`skill.catalog.build.duration{source=db}` 打在 `JdbcSkillStore.catalog()`（`:330`）而不是仓库里**：一次目录构建就是那条查询，仓库再打一次会在同一个指标上留下两条记录，§15 的 P95 门槛直接失真。

测试：T-03（`DbSkillRepositoryTest` 15 用例）、T-07 的 db 腿与 §7 的「三者不变则指纹不变」（`SkillFingerprintContractTest` 4 用例）、T-13 的仓库侧（`SkillCatalogOrderContractTest` 3 用例）、组合仓库降级三态计数（`CompositeSkillRepositoryTest` 新增 3 用例）。全量 `mvn -o test` **1342 用例 0 失败 4 跳过**（较 P0-3 基线 1317 增 25；4 个跳过仍是未配置的 MySQL 腿与既有沙箱项）。桩的一条硬要求记在这里以免后来人放松：`DbSkillRepositoryTest`/`SkillFingerprintContractTest` 的行对象必须由正文与资源文件**现算** `content_hash`/`resource_hash`（`SkillManifests.*`，与 `JdbcSkillStore` 写路径同一方法），手工常量会把「目录指纹」这一侧变成自证，测不出装配前后的漂移。

## 8. 管理写路径与接口层解耦

现状 `SkillAdminController` 依赖具体类，迁移会变成 13 个端点全改。先在 P0 抽出端口，controller 只依赖端口。下列签名是 **P1 目标形态**，P0 端口只落现网已有语义的子集（见本节末「P0-5 落地情况」）：

```java
public interface SkillStore {                  // 管理面读写共用端口
    List<SkillSummary> list(int page, int size);
    SkillDetail detail(String id);
    List<SkillFilePackage.Summary> files(String id);
    String fileContent(String id, String path);
    SkillSummary save(String id, String description, String body, Map<String,Object> meta,
                      boolean executionEnabled, List<SkillFile> files, long expectedRevision);
    boolean delete(String id, long expectedRevision);
    List<SkillSummary> replaceFiles(String id, List<SkillFile> files, long expectedRevision);
    boolean deleteFile(String id, String path, long expectedRevision);
    List<VersionSummary> versions(String id);
    SkillSummary rollback(String id, int versionNo, long expectedRevision);
    boolean writable();
    String location();
}
```

- `NacosSkillRegistry` 直接实现 `SkillStore`（P0 不加同名适配器层），`JdbcSkillStore`（P0-3 已落）是第二个实现；`PayrollCoreConfiguration` 按 `payroll.skills.source` 注入其一。
- `SkillImportService` 改为依赖 `SkillStore` + `SkillProjectParser`，Git/ZIP 导入在两种源下走同一套解析与配额。
- `RuntimeHealthService.java:35` 改为依赖 `SkillStore`，暴露 `location` 与 §10 的滞后指标。
- `SkillAdminController` 的 `SkillDetail`/`SkillSummary` DTO 从 `NacosSkillRegistry` 内部类提升为 `skill` 包顶层 record（避免 controller 仍 import 具体类）。

**P0-5 落地情况（2026-11-22，未提交）** —— 与上述草案的三处有意偏差：

1. **端口只落子集**：`SkillStore` 现为 `writable / enabled / lastSuccessfulListAt / location / list / detail / files / fileContent / replaceFiles / deleteFile / create / update / importProject / delete`。`expectedRevision`、`versions()`、`rollback()`、`list(page,size)` 未进端口——Nacos 实现没有这些概念，此时加参数只能写出假的乐观锁；分页本就在 `SkillAdminController.list()` 展示层做。P1-2 前端回传 revision 时改用 `JdbcSkillStore` 上的同名重载（见下「P0-3 落地情况」第 3 条），要不要提进端口等 db 源默认真跑起来再定，controller 的 13 个端点仍不必改。`lastSuccessfulListAt()` 一并进端口，因为 §10 的滞后指标要跨实现读它。
2. **不做适配类**：`NacosSkillRegistry implements SkillStore`（类仍 `final`，管理台测试改为 `mock(SkillStore.class)`，不需要为可测性放开继承），嵌套 DTO 提为顶层 record，`nacosEnabled()` 更名 `enabled()`；`SkillAdminController`（字段与两个构造器参数改叫 `store`）、`SkillImportService`、`RuntimeHealthService` 与 `PayrollCoreConfiguration` 的 bean（`skillRegistry` → `skillStore`）一起换依赖。纯重构、行为零变化。
3. **`/health` 组件键保持 `nacos`**：端口化后该组件由 `SkillStore.enabled()` 驱动，但键名不改——改键会让既有告警与 §17 基线口径失配；db 源的组件键随 P0-6 的 `payroll.skills.source` 一起定。（**该结论已被 P0-6 推翻并改名为 `skills`**，理由、影响面与全仓库引用清点见 §11.1 第 5 条；当时留的「不改的唯一替代是两键并存」也确实没有采用。）

**P0-5 后仍存的 Nacos 静态耦合（2026-11-22 随 P0-1 收敛了一半；ID 那一半已于 P0-4 收完）**：ID 规则已提为中立类 `SkillIds`（`^[a-z][a-z0-9-]{1,63}$` + 上限 64），`SkillProjectParser` 的截断长度与合法性判定、`NacosSkillRegistry` 的 `normalizeId`、§5.2 的建表注释与 `DdlSyntaxTest` 的列宽断言全部读它，`JdbcSkillStore`（P0-3）不必再抄一份。`NacosSkillRegistry.isValidId()` 已随 P0-4 删除，`NacosSkillRepository.java:248` 与 `NacosSkillRegistry.java:586` 两处调用点直接指 `SkillIds.isValid`（P0-5 曾保留转发方法以免混入读路径行为变更，P0-4 本来就要改这条读路径，一并收掉）。剩下的 `DATA_ID_SUFFIX`（`NacosSkillRegistry.java:28`）是 Nacos 读路径专属，db 源不需要，随 P3-3 一起删。ID 派生不做大小写折叠：dataId 去掉 `.md` 后必须原样通过 `SkillIds.isValid`，大写技能名在现网就是被跳过而不是被改写，迁移后保持同一口径。

事务边界（保存一个技能 = 一个事务）：
1. `SELECT revision ... FOR UPDATE`（或 `UPDATE ... WHERE revision = ?` 判定影响行数，与 `JdbcAgentDefinitionRepository` 同风格）→ 不匹配抛 `PayrollConflictException`（409）。**`version_no = max+1` 的分配必须在这把行锁之后做**，否则并发写会撞 `uk_skill_version_no`。
2. 计算 `content_hash`；与当前版本相同则**不追加版本**，只允许更新元数据/资源（避免版本膨胀）。
3. 新正文 → `INSERT skill_version`；资源：blob 先落盘（内容寻址、幂等），再 `INSERT skill_blob`/`skill_resource`；任一 SQL 失败要删除本轮新写且已无引用的 blob。
4. `UPDATE skill_definition SET current_version_id=?, content_hash=?, resource_count=?, resource_bytes=?, resource_hash=?, revision=revision+1 ...`（三个 resource_* 必须与步骤 3 写入的行**同事务**重算，否则指纹会漂）。
5. 提交后广播失效（§10）。

`writable()` 语义变更：现网 `NacosSkillRegistry` 的可写性取决于 Nacos 是否启用；db 源下 `writable()` == MySQL 可用。这意味着**Nacos 完全关闭的部署也能管理技能**（当前不行），是迁移的附带收益，需在 §21 的部署文档里明写。

**P0-3 落地情况（2026-11-23，未提交）** —— `JdbcSkillStore`（`payroll-common/.../skill/JdbcSkillStore.java`）是 `SkillStore` 的第二个实现，上面那节事务边界逐步照做，另有七处与草案的有意偏差：

1. **类名不叫 `DbSkillStore`**：跟 `JdbcAgentDefinitionRepository` 的 `Jdbc` 前缀同风格；P0-4 的运行期读路径叫 `DbSkillRepository`，两个名字只差一个词会读混。
2. **版本只跟正文走**：`content_hash` 不变则复用 `current_version_id`，只改元数据列（草案步骤 2 的「避免版本膨胀」落成可断言行为——`replaceFiles`/`deleteFile` 全程 `version_id` 不动而 `revision+1`，`skill_resource` 仍按版本各留一行）。`version_no = max+1` 在 `SELECT … FOR UPDATE` 拿到行锁之后才分配（草案步骤 1）。
3. **`expectedRevision` 只做重载，不进端口**：`update`/`replaceFiles`/`delete` 各多一个带 `expectedRevision` 的重载。`null` 或空串 = 不做并发检查（照样拿行锁，写序列仍互斥），非空且不匹配 → `PayrollConflictException`（409，附带当前 revision 供前端回显）。端口签名保持 Nacos 语义，db 源的强一致是「实现里已经有、接口层还没接」。
4. **三个 `resource_*` 与清单同事务重算**：统一走 `SkillManifests.resourceHashOf`，与 Nacos 侧同一个方法，所以切库当天指纹不漂、不会全量重建智能体缓存。副作用：`CHAR(64)` 的空串两库都读回补空格，比较前必须 trim。
5. **blob 清理只认「本轮新写的字节」**：`writeResources` 先 `exists` 再 `put`，只有本轮真正新增的 sha 进清理名单，回滚时删它们；已被其他版本引用的字节一律保留——删了就是把别人的技能读坏了。`skill_blob` 行本身随事务回滚，不需要补偿删除。
6. **两处静默行为改成显式失败**：`tags` 超 512 字符、`skill_version` 超 32 字符直接抛 `PayrollException`（Nacos 侧靠 dataId 长度天然限住，db 侧不拦会静默截断）；内置技能尚无落库行时 `delete()` 报错，而不是返回「删成功」却照样在列表里。
7. **`created_by`/`updated_by` 写 NULL**：管理台 DTO 里没有操作者字段，接线随 §12 的审计改造一起补，本批不假造一个 "admin"。

跨实现的两条读辅助也在这里，P0-4 直接复用：`catalog()` 单查询出全表（`ORDER BY sort_order, skill_id`，不读正文与字节），`list()` 则照 Nacos 口径把未落库的内置技能补进列表、内置在前；`fingerprint()` 出全表 `skill_id:revision` 的哈希外加 `MAX(updated_at)`，供 §9.1 的轮询比对（草案里叫 `aggregateRevision()`，P0-7 落地时更名，理由见 §10 末）。测试见 §18 T-04（14 个用例）；MySQL 腿仍 SKIPPED，切 db 源前必须挂专用测试库跑一次 `DdlSyntaxTest.sharedMigrationsApplyOnRealMysql`。

**P1-1 落地情况（2026-11-23，未提交）** —— 本节末「P0-5 落地情况」第 1 条留的那句话（「P1-2 前端回传 revision 时改用 `JdbcSkillStore` 上的同名重载」）本轮实施，但**没有**按草案把 `long expectedRevision` 塞进端口签名，而是做成端口上的默认方法。四处偏差与理由：

1. **`expectedRevision` 进端口，但只做 `default` 重载**：`SkillStore` 新增 `update(…, String expectedRevision)`、`replaceFiles(id, files, String expectedRevision)`、`deleteFile(id, path, String expectedRevision)`、`delete(id, String expectedRevision)` 四个默认方法，全部委托到原来的无 token 方法。这样 `SkillAdminController` 只依赖端口就能把 token 一路传到 `JdbcSkillStore` 的重载，而 `NacosSkillRegistry` 一行不用改——Nacos 没有行级 revision，让它实现一组「收下参数再假装没用」的重载只会留下一个骗人的签名（草案的 `long` 类型同样落不了地：库里的 token 是 `revision` 的十进制串，`Long.parseLong` 会在空串上炸，端口取 `String` 与 C3 智能体定义那套完全一致）。`P0-3 落地情况` 第 3 条说的「db 源的强一致是实现里已经有、接口层还没接」到本批收口。
2. **token 从 `@RequestParam(name = "revision", required = false)` 进**：与 `AgentAdminController` 的 C3 契约逐字对齐（同名参数、同为可选、`AuditContext.markRevision(revision)` 紧跟方法第一行），审计列因此记得到 `audit_log.revision`、`writeResult` 走 `write_ok`。冲突不用控制器操心：`ApiExceptionHandler.java:57-67` 把 `PayrollConflictException` 转成 409 + `currentRevision` 回显，并顺手把审计列写成 `write_result=conflict`——所以 409 时**只有一条带冲突标记的通用请求事件，没有成功事件**（`SkillAdminControllerTest.staleRevisionPropagatesWithoutAudit` 锁住「异常照抛、`auditService.record` 一次没调」）。前端不传 = 不做并发检查（行锁照旧），灰度期允许缺失，与 P0-3 第 3 条同口径。
3. **`version_no` 走详情读，不进目录查询**：`JdbcSkillStore.detail()` 原先用只取 `body_md` 的语句读正文，本批改用 `SELECT_VERSION_BODY`（`version_no, body_md` 一次带回），`SkillDetail` 因此多两个字段 `revision` + `versionNo`、`SkillSummary` 多一个 `revision`（列表页不点详情也能拿到下一次写的 token）。`ROW_COLUMNS`/`SELECT_CATALOG` 不动：A-01 的 18 技能 / 均值 1.06ms / P95≤2.1ms 量的就是那条无 join 的单查询，为一个审计字段给它连 `skill_version` 不划算。内置项与 Nacos 源没有行，`revision` 取空串、`versionNo` 取 0，写进审计时两个键**都不出现**——理由见 §13-6。
4. **写后重读只为补齐 `versionNo`，且只补缺失键**：`create`/`update`/`import`/`files` 的成功路径写完后调一次 `persisted(id)`（`store.detail`），快照上已有 `revision` 时不覆盖——写接口返回的摘要带的是本次写入后的 token，重读拿到的可能是别人的新值。`putVersion` 用 `putIfAbsent` 正是为此。该重读是 best-effort：读失败只 warn 并少一个审计字段，绝不把已提交的写变成 5xx（`SkillAdminControllerTest.auditSnapshotFailureKeepsWrite` 锁住）。被否掉的替代方案一并记下，免得下轮重走：把 `version_no` 塞进目录投影（热路径付代价）、在 Web 层用 `before.versionNo + 1` 推算（正文没变时根本不追加版本，会写出假版本号）、给 `SkillSummary` 加只写字段（列表 DTO 里凭空多一个用不上的列）、用 `revision.isBlank()` 反推来源（把「来源」编码进「令牌有没有」，将来任何一侧改了语义都读不出来）。

顺带一处与 §9.2 有关的接口事实，P1-4 前端必须按它设计：**文件包写入也递增 `skill_definition.revision`**（`replaceFiles`/`deleteFile` → `replaceResources` → `rewriteDefinition` 带 `current.revision() + 1`），版本行不动但行令牌动了。所以 `PUT /{id}/files` 与 `DELETE /{id}/files/{*path}` 的响应体在 `{count, files}` 之外回吐写后的 `revision`，前端拿它覆盖手里的 token；只回 `{count, files}` 的话，用户改完文件再改正文必然撞一次假冲突。

**P1-2 落地情况（2026-11-23，未提交）** —— 本节开头草案签名里剩下的 `versions()` 与 `rollback()` 已进端口并接到 `JdbcSkillStore`，另有五处与草案的有意偏差：

1. **返回类型叫 `SkillVersion`，不叫 `VersionSummary`**：`payroll-common/.../skill/SkillVersion.java` 是顶层 record（`(versionNo, changeNote, contentLength, createdBy, createdAt, current)`，带 `@JsonNaming(LowerCamelCase)` 与其余 DTO 同风格）。草案那个名字会和已有的 `SkillFilePackage.Summary`、`SkillSummary` 挤成三个「Summary」，而这一行的实际语义是「一个版本的时间线条目」，不是聚合摘要。
2. **`expectedRevision` 仍是 `String`**：理由与上面「P1-1 落地情况」第 1 条完全相同（库里就是 `revision` 的十进制串，`Long.parseLong("")` 在「前端没传」这条合法路径上炸），草案这里的 `long` 与 P1-1 一起更正，不再单独记一遍。
3. **两个方法都是端口上的 `default`**：`versions()` 默认返回 `List.of()`，`rollback()` 默认**抛** `PayrollException("当前技能来源没有版本历史，不支持回滚（只有 db 源写 skill_version）")`。列表退化成空、回滚退化成失败——Nacos 源没有版本行，「没有版本可看」与「不能回滚」是两种答案，后者静默返回一个 `SkillSummary` 会让管理台以为回滚成功。`NacosSkillRegistry` 因此仍然零改动。
   测试侧有个不显然的连带后果：Mockito 的 mock **会拦到 `default` 方法**，未打桩时返回 `List.of()` 而不是 `null`，所以 `SkillAdminControllerTest` 里凡是断言新行为的用例都必须显式 stub 带 token / 新签名的那个重载，`verify` 也一样指过去——沿用 P1-1 的同一手法。
4. **`versions()` 是单查询、不读正文**：`SELECT_VERSION_TIMELINE`（`:123-127`，`ORDER BY version_no DESC`）只取 `version_id, version_no, change_note, content_length, created_by, created_at`——打开时间线不该等于下载全部历史，正文按需另走 `SELECT_VERSION_BY_NO`。`content_length` 用 `skill_version` 上已有的列，不做 `CHAR_LENGTH(body_md)`。技能在库里没有行（内置项、Nacos 源）时返回**空列表**而不是报错。`current` 由 `version_id == skill_definition.current_version_id` 逐行判定（`:662`），**不是** `version_no` 最大。今天两者恰好总相等（版本行只在移动指针时追加），但库里生效的定义是 `current_version_id`，把它写成「取最大序号」等于给将来任何「追加但不移指针」的写入口埋一个假当前版。
5. **`rollback()` 多做了一件草案没写的字节体检**：见 §9.2 末「P1-2 落地补充」第 3 条。

**P1-3 落地情况（2026-11-24，未提交）** —— §8 草案端口签名里最后没接的两根线（操作者、导入路径的令牌）已接通。端口新增六个**默认重载**——`create(5)`、`update(7)`、`replaceFiles(4)`、`deleteFile(4)`、`rollback(4)`、`importProject(4)`，每个都委托到窄一格的签名，`NacosSkillRegistry` 因此**仍然一行未改**（它只实现基础签名，`NacosSkillRegistry.java:274/314/338/349/366/444`）。另有一处端口外的接线与三处不显然的取舍：

1. **`operator` 走端口而不是走 `AuditContext`**：存储层不读线程上下文，操作者由调用方显式给。理由与 §11.2 第 8 条「新类自身不读环境变量」同一类——`SkillNacosImporter`、CLI 与测试都要能调同一段写入代码，而它们没有请求上下文；把上下文读在 Web 层（`SkillAdminController.operator()`，`SkillAdminController.java:742-746`）是唯一能同时满足两侧的层位，且它与 `audit_log.actor_name` 同源，不会出现「审计记的是张三、技能行上写的是李四」。上下文缺失时返回**空串**而不是 `null` 或 `"admin"`，由存储层落 NULL。
2. **归一只有一处**：`JdbcSkillStore.operatorOrNull()`（`JdbcSkillStore.java:1366-1368`）把 `null`/空白统一成 SQL NULL。库里没有「空串操作者」这种中间态——`created_by`/`updated_by` 是 `VARCHAR(128)` 可空列（`V22__skill_definition.sql:25/27`，`skill_version.created_by` 同理 `:42`），迁移灌进来的 18 条技能**没有人类操作者**，写个假的 `"admin"` 会让对账页读起来像「管理员改的」，而 §11.2 第 6 条要的正是「能把灌进来的和页面上改的分开」。
3. **`created_by` 只在插入时写、`updated_by` 每次写都覆盖**：`insertParams`（`:1634-1645`）把同一个 `pending.operator()` 填进两列，`updateParams`（`:1648-1653`）只带 `updated_by`。于是「这条技能谁建的」在库里不会被后续保存抹掉，「最近一次是谁改的」永远新鲜。窄签名（内部调用、迁移）写入时两列落 NULL，`versions()` 回读成空串——不是 bug，是「归属未知」的唯一可读形态。
4. **Git/ZIP 导入拿到行令牌**：`SkillImportService.importGit/importZip` 各多一个带 `expectedRevision`+`operator` 的重载（窄签名原样保留并委托过去，`SkillImportService.java:73-76/105-107`），控制器的两个导入端点接上同名 `?revision=` 参数。**dryRun 不标令牌**：预览一个字节都不写，`markRevision` 只会让审计把「看一眼」记成一次成功的条件写入（`SkillAdminController.java:306-309/342-344`）。
5. **导入的 409 有两类，判别点是 `currentRevision` 在不在**：「技能已存在，覆盖需要显式确认」是确认门（`JdbcSkillStore.java:964-966`，不带令牌回显），令牌不一致才是真冲突（带）。前端若把两类混成一条「刷新重试」，等于替用户按下覆盖。这条判别在端口注释（`SkillStore.java:177-179`）与 §9.3 里各写了一遍，`JdbcSkillStoreTest.importConflictsDifferByCurrentRevision` 锁住。

一处端口形状的实话：这些重载在 Nacos 源下是**静默降级**的（委托到不认识操作者的窄方法）。这不是漏洞——Nacos 的 dataId 是整包共享的，没有「谁改的这一行」可记；`writable()` 与时间线在 nacos 源下本来就给不出版本行（§8 末 P1-2 第 3 条）。测试：`SkillAdminControllerTest` 30 → **33 用例**、`JdbcSkillStoreTest` 21 → **24 用例**、`SkillImportServiceTest` 12 → **13 用例**，全量 `mvn -o test` **1398 → 1405 用例 0 失败 4 跳过**。变异验证十一条（P1–P11）见 §18 T-08c。


## 9. 并发、版本与回滚

### 9.1 并发

- 行级 `revision`（BIGINT，单调递增，初值由导入器写入），写前重读比对，不匹配 → `PayrollConflictException` → HTTP 409 + `location` 提示，与管理台现有智能体页 409 处理一致（`architecture-review` C3 批次已实现前端回传 revision）。
- 聚合 revision（跨节点失效用）：`SELECT` 全表 `skill_id, revision` 后按 id 排序取 SHA-256（照抄 `JdbcAgentDefinitionRepository.java:283-290`）。200 技能规模下代价可忽略；>2000 技能时改为 `MAX(updated_at) + COUNT(*) + SUM(revision)` 的廉价查询，作为 D5 的备选实现注明。**P0-7 已按此口径落地**：`JdbcSkillStore.fingerprint()` 在同一条 SQL 里多带一列 `updated_at`，只用于估算滞后、**不进哈希载荷**（载荷必须与本节定义的字符串逐字节一致，否则切库当天的基线指纹失去可比性），返回值是 `CatalogFingerprint(revision, lastWriteMillis)` 二元组。

### 9.2 版本与保留

- 每次正文变更一个版本；`change_note` 取管理台「变更说明」字段（可选，≤200 字符）。
- 保留 `payroll.skill.version.keep`（默认 20）个版本，超出由 §14 的裁剪任务删除。
- 回滚 = 新增一条 `skill_version`（内容复用旧版）+ **把目标版本的 `skill_resource` 行整批复制到新 `version_id`** + 移动 `current_version_id`，**不改历史**，审计里 `skill.version.rollback` 记 `from`/`to` 的 `version_no`。
  复制而非改指针的原因：资源按 `version_id` 绑定（§5.3），只切指针会让新版本的资源清单为空、`resource_hash` 与实际不符；blob 本身内容寻址，复制的是清单行不是字节。
- 只回滚文件包、不回滚正文（或反之）不支持：一个版本 = 正文 + 资源的一个原子快照，这是本次迁移要建立的语义，不留半吊子入口。

**P1-2 落地补充（2026-11-23，未提交）** —— 本节四条规则照做（回滚确实是「追加版本行 + 复制 `skill_resource` 清单行 + 移 `current_version_id`」，历史行的 `body_md` 一字不改，测试用 SQL 直查两行旧正文证明），另有五条要把口径写死：

1. **`change_note` 的长度在事务开始前校验**：超 200 字符直接 `PayrollException`（「变更说明过长（N 字符），上限 200 字符」），位置在 `requireChangeNote()`（`JdbcSkillStore.java:1374-1384`）而不在 SQL 里。区别是有代价的——交给库截断会静默丢字，交给 SQL 报错则 revision 已经涨、版本行已经插；现在这条路径上**超限的写什么都不发生**（`changeNoteFollowsBodyOrCurrentRow` 断 400 类失败后 revision 仍是原值）。入库前 `trim()`，恰好 200 字符放行（列宽就是 200）。
2. **空说明 = 不改说明，不是清空说明**：`if (!note.isEmpty())` 才发 `UPDATE_VERSION_NOTE`。理由很日常——管理台表单里「变更说明」是一个每次保存都会整体提交的字段，用户只翻执行开关时它是空的；空串照写等于「任何一次不写说明的保存都会抹掉上一版的说明」。真要抹掉一句话目前没有接口，登记在 §19 P1-2「没做的事」。
3. **说明只跟两个位置绑定**：正文变了 → 随新 `INSERT_VERSION` 落在新版本行；正文没变（`content_hash` 相同，见 §8 事务边界第 2 步）→ 用 `UPDATE_VERSION_NOTE` **补写在当前版本行上**，不追加一条同内容版本。于是 `skill_version.change_note` 恒等于「导致这一版正文出现的那句说明」，时间线里不会出现只有说明、正文与前一行重复的版本。新建路径忽略这个字段（第一版没有「变更」可言）。
4. **回滚多做一步 fail-closed 字节体检**（草案没有）：`requireBlobsPresent()`（`JdbcSkillStore.java:1456-1467`）按目标版本的清单逐个 `blobStore.exists(sha)`，任一缺失就拒绝回滚并点名是哪个路径（「技能 X 的 vN 文件字节缺失：<path>」）。因为指针一移，运行期那次整批装配就会撞上「技能资源缺失」——一个技能的坏包能让同批技能一起读不出来（与 §11.3 第 4 条 blob 根指错的级联同形）。检查放在 `nextVersionNo` 之前，验不过整笔事务不提交，**不会留下一条空版本行**。`PAYROLL_SKILL_FILES_ENABLED=false` 时跳过这一步：那个形态运行期不读清单，拦在这里只会让回滚在功能关闭的环境里凭空不可用。
5. **回滚只换正文与资源两维**：新版本行的 `meta_json` 取**当前**版本行（`SELECT_META_JSON`），`description` 与 `execution_enabled` 沿用锁定行，`change_note` 固定写「回滚自 vN」（时间线里要看得出哪一步是回滚，留空就只剩一串没有说明的版本）。审计键名同样按 §13-6 的 camelCase 口径落成 `fromVersionNo`/`toVersionNo`，不是草案写的 `from`/`to`。
   ~~`created_by` 仍写空串~~ —— **这条欠账已由 P1-3 还清**（见下面「P1-3 落地补充」第 3 条），`versionsTimelineFollowsPointer` 现在断的是「窄签名写入 → `created_by` 落 NULL → 回读空串」，即「归属未知」而不是「本仓库不记归属」。

**P1-3 落地补充（2026-11-24，未提交）** —— 本节四条规则没变，变的是「这一版是谁写的」终于进了库，四条要说清：

1. **`skill_version.created_by` 记的是「造出这一版的人」**，不是「最初创建技能的人」：新建 → 灌包人；正文变了 → 那次保存的操作者；正文没变只补说明 → **不改**已有版本行的作者（`UPDATE_VERSION_NOTE` 只动说明列，`created_by` 保持原位）。于是时间线里每条历史都能单独回答「这一版的正文是谁弄出来的」。
2. **`skill_definition.created_by` 只在插入时写**（`insertParams`，`JdbcSkillStore.java:1634-1644`），之后任何写只动 `updated_by`（`updateParams`，`:1648-1653`）。覆盖导入（`overwrite=true`）走的也是同一条 `rewriteDefinition`，所以「先灌包、后被人接手」的技能，`created_by` 仍是灌包人——`importConflictsDifferByCurrentRevision` 专门验了这一点。
3. **回滚的新版本行归按下回滚的人**（`:808` 的 `String writer = operatorOrNull(operator)`）：造出那条 v3 的是他，不是 v1 的作者。测试用五个互不相同的哨兵值（灌包人/改稿人/换文件人/删文件人/回滚人）串完 import→update→replaceFiles→deleteFile→rollback 整条链，任何一处把槽位写串都会红在那一条上（`operatorWritesEveryProvenanceColumn`）。
4. **`operator` 与 `change_note` 是相邻的 `String` 形参**，串位**照样编译得过**、调用也不抛异常，只有落库的列会错——端口注释里明写了这一点（`SkillStore.java:123-129`），而防它的手段是一条用例同时验两列（说明进 `change_note`、人进 `created_by`），不是类型。

### 9.3 新增接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/admin/skills/{id}/versions` | `skill:manage` | 版本列表（`version_no`、`created_at/by`、`change_note`、`content_length`、是否 current） |
| POST | `/api/admin/skills/{id}/versions/{versionNo}/rollback` | `skill:manage` | 回滚，带 `revision` |
| GET | `/api/admin/skills/{id}/files/{*path}` | 现有 | 命中 `skill_resource` → `SkillBlobStore.get`；响应加 `ETag=sha256` |

**P1-2 已落（2026-11-23，未提交）** —— 三行都接上了，形状与草案有四处要说清：

- `GET /{id}/versions`（`SkillAdminController.java:169`）返回 `{id, total, versions:[…]}`，条目即 `SkillVersion` 的 camelCase JSON。`id` 先过 `SkillIds.normalize`（trim + 校验），所以 `" payroll-auditor "` 能用、`"Bad Id"` 报 400；响应里的 `id` 是**归一后**的那个，前端拿它再调回滚不会再撞一次校验。
- `POST /{id}/versions/{versionNo}/rollback`（`:185`）走与 P1-1 完全相同的 C3 契约：可选 `?revision=` → `AuditContext.markRevision()` → 冲突交 `ApiExceptionHandler` 转 409。它同时是 §13-2「新端点同权限」的落地，也被那条反射式权限测试自动覆盖（`isWriteMapping` 扫 `@PostMapping`，`SkillAdminControllerTest.java:585-588`），将来漏标 `skill:manage` 的新写端点自己会红。
- **ETag 在控制器现算，不读库里的 `sha256`**（`:146-166`）：`'"' + SkillManifests.contentHash(content) + '"'`。两个理由——(1) 这个端点两种源都要能用，Nacos 源根本没有 `skill_resource` 行；(2) 响应体就是刚读出来的那段字节，现算保证「指纹描述的一定是本次发出的内容」，从清单取则有「清单写 A、实际发 B」的错配可能（blob 侧本来有读时校验，但那条不适用于 Nacos 源）。
- `If-None-Match` 的匹配按 HTTP 语义放宽到实用：`*`、单个指纹、逗号分隔的多值、`W/` 弱前缀都算命中（`etagMatches`/`normalizeEtag`，`:779`/`:794`），命中即 304 且**响应体为空、ETag 照吐**；不匹配则正常 200。没有引入 `Cache-Control`，本条要省的是点开一个文件时的正文传输，不是把文件包变成浏览器可长期缓存的资源。

**P1-3 已落（2026-11-24，未提交）** —— 本表三行没动（P1-3 不加端点），改的是**表外两个既有导入端点**：`POST /import/git` 与 `POST /import/zip` 各接上可选的 `?revision=`，与 `PUT /{id}` 完全同一套 C3 契约（参数名、可选性、`markRevision` 位置一致），整包覆盖从此也走行令牌。两点与前文不同：

- **dryRun 不调 `AuditContext.markRevision()`**：其余写端点无条件标，是因为它们一定在写；导入的预览分支一个字节都不写，标了就让审计把「看一眼」记成 `write_result=write_ok` 的条件写入（`SkillAdminController.java:306-309/342-344`，`dryRunImportLeavesRevisionUnmarked` 锁住「预览后 `AuditContext.current().revision()` 仍是 null 且 `auditService.record` 一次没调」）。
- **导入返回的是 409 的两副面孔**：确认覆盖（无 `currentRevision`）与令牌过期（有），判别见 §13-6 与 §8 末「P1-3 落地情况」第 5 条。P1-4 前端必须分两条提示写。

## 10. 跨节点一致性

| 机制 | 取值 | 说明 |
| --- | --- | --- |
| 主动失效（同进程） | 写事务提交后立即刷快照 | **两源均已实现**：Nacos 侧 P0-4a（`SkillRepositories.invalidateShared()` ← `NacosSkillRegistry.dropSnapshot()`），db 侧从 P0-3 起在事务提交后调同一入口（`JdbcSkillStore.java:835/876/916/1016/1086`）。P0-7 给这条入口补上 `skill.invalidations.count{trigger=write}`，「谁触发的失效」从此在指标里可分 |
| 轮询失效（跨实例） | `payroll.skill.invalidation.poll-ms`（默认 15000）比对聚合 revision | **已装配并在跑（P0-6）**：`SkillInvalidationPoller` bean 挂在 db 源上（`PayrollCoreConfiguration.java:508`，`destroyMethod="close"`），无新依赖，滞后上界 = poll 间隔 + 一次目录构建。输入端口是 `SkillRuntimeReader`，因此这条只服务 db 源；Nacos 侧无变更推送订阅（`NacosSkillRegistry` 里没有 listener），跨节点仍靠 `payroll.skill.catalog.ttl-ms` 兜底——迁移把这半边的滞后从「不确定」变成「有上界」，见下「P0-7 落地情况」 |
| 广播失效（可选，D5） | Redis `payroll:skill:changed`（复用现有 Redis 基建） | 亚秒级；无 Redis 部署自动退化为纯轮询，行为不变 |
| 指纹联动 | 聚合 revision 变化 → 重建 `CatalogSnapshot` → `skillFingerprint` 变 → `AgentCache`（64 条 / 30min 空闲 / 15min 压力）按需重建 | 不主动清空 AgentCache，避免重建风暴 |

门槛（§15 埋点校验）：A 节点保存 → B 节点可见 ≤ **20s**（P95）；引入 Redis 广播后 ≤ **2s**。

**P0-7 落地情况（2026-11-23，未提交）** —— `SkillInvalidationPoller`（`payroll-common/.../skill/SkillInvalidationPoller.java`）+ `DbSkillRepository.refresh()`（`:201`）+ `SkillMetrics` 两条新指标。线程形态照仓库既有做法（命名 daemon 线程 + `scheduleWithFixedDelay`，首轮延迟 == 间隔，见 `TurnLeaseService.java:75`），仓库里没有 `@Scheduled`/`@EnableScheduling`，本批也不引入。七处与草案的有意偏差：

1. **端口方法叫 `fingerprint()` 而不是 `aggregateRevision()`**：它要返回「聚合 revision + 库内最后写入时刻」二元组（`SkillRuntimeReader.CatalogFingerprint`），叫 revision 会让调用方以为一个字符串就够。`updated_at` 是 §10 那条滞后门槛**唯一能落地的观测点**，不带这列就只能把 `skill.invalidation.lag` 空着。
2. **方法进 `SkillRuntimeReader`，不进 `SkillStore`**：轮询是运行期读路径，每个 JVM 每 15s 跑一次；挂到管理端口等于逼 web 与 runtime 都为它多持有一份写能力，违背 §13-1 的最小权限。
3. **失效动作是注入的 `Runnable`，不是直接调 `SkillRepositories.invalidateShared()`**：那条入口走 `invalidate()`，会连正文缓存一起清。聚合 revision 是**全表**口径——任何一个技能保存都会让它变——跟着清正文等于每改一个技能就把全部技能重新装配一遍，§7.1 的内容身份缓存白做。因此新增 `refresh()`（只丢目录快照，正文缓存留着）由调用方传入，同进程管理写仍走全清的 `invalidate()`（那是「本节点刚写的这个技能一定变了」，粒度对得上）。
4. **滞后是下界、且可能为负**：`本节点时钟 − 库内 MAX(updated_at)` 混了两个时钟源，负数没有意义，就地截零（`SkillMetrics.java:116`）。它只能用来估算「距库里最后一次写入过了多久」，不能用来排序或判定因果。
5. **失败轮次不推进基线，且异常绝不逃出调度任务**：`pollOnce()` 抛出时 `lastRevision` 不动，恢复后的第一轮必然失效一次——否则故障期间对端的写入会被永久漏掉。调度入口 `pollQuietly()`（`:110`）把 `PayrollUnavailableException` 与 `RuntimeException` 一律吞成 WARN：`scheduleWithFixedDelay` 一旦任务抛出就**永久取消后续调度**，一次瞬时断库不该让这台节点的失效机制从此停摆。
6. **首轮只登记基线，不算一次失效**：进程刚起时缓存本来就是冷的，把「第一次读到」当成变化会白建一次目录快照、白报一次滞后。
7. **Spring 装配已随 P0-6 落地（2026-11-23）**：本批只提供普通类 + `pollMillisFromEnvironment()`（读 `PAYROLL_SKILL_INVALIDATION_POLL_MS`，空白取默认，非数字直接抛 `IllegalArgumentException`），`@Value` 接线、bean 生命周期与「两源各自要不要挂 poller」留给 P0-6。**P0-6 的定法见 §11.1 第 6 条；那个环境变量读法同时被删掉了**——`@Value("${payroll.skill.invalidation.poll-ms:15000}")` 加上 yml 里的 `${PAYROLL_SKILL_INVALIDATION_POLL_MS:15000}` 已经覆盖同一个环境变量，而 poller 只在 Spring 上下文里创建（测试直接把毫秒数当构造参数传，CLI 侧根本不建它），留着就是第三份没人走的读法。

指标口径与 §15 草案有两处差别。命名：取 `skill.invalidation.lag`，`_ms` 后缀在本仓库的 Timer 里没有先例。**分位数（P0-8a 已补，2026-11-23，未提交）**：草案写作时 `SkillMetrics` 的 Timer 一个都没开分位数发布，`application-common.yml:329-347` 暴露了 `health,info,metrics,prometheus` 但 `management.metrics` 只打了 `application` 标签（`:345-347`）、没有 distribution 配置，所以 Prometheus 侧每个 Timer 只有 `_count`/`_sum`/`_max`，而 §15 与 §17 的门槛写的是 P95。二选一里选了**给指标类补 `publishPercentileHistogram()`**（`SkillMetrics.java:166`、`:178`，覆盖 `skill.catalog.build.duration` 与 `skill.invalidation.lag` 两个 Timer），yml 保持不动：`management.metrics.distribution.percentiles-histogram.all` 会把直方图加到**全部**指标上，一个技能的门槛不该让整个 Actuator 载荷翻倍。先例是本仓库的 `TurnMetrics.java:118,136,169`。

补这条时踩到一个必须记下来的测试陷阱：**`SimpleMeterRegistry` 不实现分位数发布**，开了 `publishPercentileHistogram()` 它的 `takeSnapshot().histogramCounts()` 仍然恒空。所以门槛只能这么验：把 `PrometheusMeterRegistry` 挂进 `Metrics.globalRegistry`、`scrape()` 出来数 `_bucket{` 行（`SkillMetricsTest.publishesPercentileHistograms`）。任何用 Simple 注册表写的「直方图已开」断言都是假绿灯。

测试见 §18 T-15（8 个用例，其中一条是真库双腿：同一份共享库上开两个 `JdbcSkillStore`，B 侧仓库必须只在一次轮询之后才读到 A 侧写入的新正文，且同一 revision 的下一轮不再失效）。**仍未验证**：V-07/T-12 的双进程（两个 JVM + 共享 MySQL）滞后测量是手工项，本批只在同一 JVM 内做了双腿；15s 默认间隔在本机没有实测过。

## 11. 迁移、灰度与回滚

### 11.1 开关

- `payroll.skills.source=nacos|db`，默认 `nacos`（与 `payroll.definitions.source` 完全同构，后者在 `PayrollCoreConfiguration.java:395` 的 `agentRegistry` 上读取，配套的 `import-from-nacos` runner 在 `:404-411`）。
- `payroll.skills.import-from-nacos=true` → `ApplicationRunner` 调 `importAllIfEmpty()`，幂等（表非空即跳过），只在 Web 侧开启，避免双进程并发导入。

**P0-6 落地情况（2026-11-23，未提交）** —— 开关、装配、导入、对账四件全部落地。与草案的差别都在形状，语义没改：

1. **db 源多四个 bean，Nacos 源一个都不加**：`dbSkillRepository`（`:479`）、`skillRepository`（`:492`，`CompositeSkillRepository(DbSkillRepository, BuiltInSkillRepository)` 并 `SkillRepositories.install()` 进共享槽位）、`skillInvalidationPoller`（`:508`，见第 6 条）、`skillNacosImportRunner`（`:527`），前三者都挂 `@ConditionalOnProperty(prefix="payroll.skills", name="source", havingValue="db")`。缺 `skillRepository` 时读路径仍走 `SkillRepositories.shared()` 的懒加载 —— 默认 `nacos` 形态**行为零变化**，这是 P0 敢独立上线的前提。管理台写侧的 `@Primary SkillStore` 按 `SkillsSource`（`:424`）二选一，与 `payroll.definitions.source` 同构。
2. **`import-from-nacos` 只对 db 源生效**：runner 先比 `SkillsSource`，Nacos 模式下打一条 INFO 直接返回。理由与草案「只在 Web 侧开启」不同一层——Nacos 模式下灌库等于制造两个权威，比双进程并发导入更糟。Web 侧的约束交给部署（runtime 的 yml 不置该键），代码里不再重复判一次进程角色。
3. **装配读的键全部进 Spring `@Value` + `application-common.yml`**：`payroll.skills.source`、`payroll.skills.import-from-nacos`、`payroll.skill.blob.root`、`payroll.skill.blob.max-bytes`、`payroll.skill.catalog.ttl-ms`、`payroll.skill.invalidation.poll-ms`、`payroll.health.skills-stale-seconds`（`payroll.skill.file-cache.*` 两条本就是 Spring 键，不属本批）。§6/§7/§10 各末段留的「Spring 键随 P0-6 接线」到此收完。**唯一没进 Spring 的是 `SkillFileSettings` 那四个文件包配额**（`PAYROLL_SKILL_FILES_ENABLED` / `PAYROLL_SKILL_FILE_MAX_BYTES` / `PAYROLL_SKILL_FILES_MAX_BYTES` / `PAYROLL_SKILL_FILES_MAX_FILES`）：它们仍是环境变量专用，Spring 侧经 `JdbcSkillStore` 的 2 参构造与 `SkillImportService` 里的 `fromEnvironment()` 读到；不补键是因为本仓库同时只有 `SkillFileSettings.fromEnvironment()` 一个读法，加 Spring 键等于给同一个旋钮建两处入口，而 §16 末的 ⚠️ 已定「默认值调整必须与流式投送同批」。
4. **一处装配陷阱（改了 `AgentAdminController` 的构造器）**：`SkillRepositories.shared()` 在槽位为空时会按环境变量**自己**建一个仓库，其中包含 `PayrollDatabase.openDefault()`。任何 bean 在 `skillRepository` 之前调它，容器里就会永久多出一个不由 Spring 管的连接池——`install()` 只 WARN 不关旧实例（防止误关正在服务的读路径）。因此 controller 改成注入 `ObjectProvider<AgentSkillRepository>` 再 `getIfAvailable(SkillRepositories::shared)`：db 源复用 Spring 那个仓库，Nacos 源才退回懒加载槽位。**新增 bean 若要读技能仓库，一律走这条，不要直接调 `shared()`/`create()`。**
5. **`/health` 组件键 `nacos` → `skills`**，属性 `payroll.health.nacos-stale-seconds` → `payroll.health.skills-stale-seconds`（`PAYROLL_HEALTH_SKILLS_STALE_SECONDS`，默认 300 不变）。§8 末「P0-5 落地情况」第 3 条把键名留给 P0-6 定，定成改名的理由：该组件由 `SkillStore.enabled()` + `lastSuccessfulListAt()` 驱动，两源同一算法，继续叫 `nacos` 会让 db 源的告警挂在错误的名词上；键名不随 `payroll.skills.source` 变化，这是不改的唯一替代方案（两键并存）真正想避免的事。代价：既有 `components.nacos` 查询要改——全仓库除 `RuntimeHealthServiceTest` 的断言外没有采集规则、脚本或前端读它，`docs/architecture-hardening-prd.md:808` 同批改名。
6. **轮询器现在真在跑**（收掉 §10「P0-7 落地情况」第 7 条）：`payroll.skill.invalidation.poll-ms` 走 `@Value`，`destroyMethod="close"` 交给容器，只挂 db 源。只挂 db 是端口决定的，不是取舍——`SkillInvalidationPoller` 的输入是 `SkillRuntimeReader`，Nacos 侧没有这个实现，也没有变更推送可轮询。`poll-ms <= 0` 时不建线程，退化为只靠 `payroll.skill.catalog.ttl-ms` 兜底。
7. **`SkillRepositories` 两条顺带修掉的**（它们原挂在 §19 P0-8 的「顺带」清单上，现在工作树里已改完，且各有一条断言）：（a）`filterFor()` 只放行 `_nacos`/`_builtin` 两个后缀（`HEAD:78-84`），db 源技能因此在系统提示里一个都不列，表现为「技能在、角色不按技能干活」——这种缺陷只有切库当天才会暴露，改成三源全放行（`:137-143`，多放一个当前不存在的后缀没有副作用），断言进 `DynamicSkillWiringTest.filterOnlyAllowsRole`。（b）`scoped()` 用 `Set.copyOf(skillIds)`（`HEAD:99`），迭代顺序每次 JVM 都可能不同，同一份定义的提示词重启后会换序——换成 `LinkedHashSet`（`:161`），并由新增的 `scopedRepositoryKeepsBindingOrder` 锁住「绑定顺序即视图顺序」（该视图的 `getAllSkills()` 直接迭代这个集合，`Set.copyOf` 下这条断言就是掷骰子）。这条与 V-12 的「逐字符不变」是同一个坑，只是它在切库之前就已经在 Nacos 源上存在。

### 11.2 导入器

`SkillNacosImporter`：
1. 分页列 Nacos `*.md`（复用 C2 批次的翻页修复），逐条解析 frontmatter + 正文；
2. 读同名 `<id>.files.json`，解析失败/`mdHash` 不匹配时**继续导入正文**并把文件包问题写进导入报告（不能因为一个坏包中断全量导入）；
3. `skill_version` + blob + `skill_resource` + `skill_definition` 逐技能一个事务；
4. 已存在的 `skill_id` 跳过并计数（幂等重跑安全）；
5. 内置技能（`RoleSkills.ids()`）按 D10 落库并置 `builtin = TRUE`。

**P0-6 落地情况（2026-11-23，未提交）** —— `SkillNacosImporter` + `SkillNacosSource`（Nacos 侧导出视图，不复用 `NacosSkillRegistry` 快照的两条理由写在该类注释里：它把坏文件包吞成空列表，且带 TTL 缓存与写路径，一次性全量读不该把正在服务读流量的实例拉进来）。五条里三条照做（规则 2/3/5）、两条换了形状（1/4），另补三条草案没有的（6/7/8）：

1. **规则 1 的顺序换了口径，这是 V-12 的已知风险而不是笔误**：`readSkills()` 先按 `RoleSkills.ids()` 放内置技能（Nacos 缺该 dataId 时用代码内置正文合成一条），其余**按 id 字典序**；导入按这个顺序写 `sort_order`（`JdbcSkillStore.nextSortOrder()` 取 `MAX(sort_order)+1`）。而 Nacos 运行期目录用的是配置列表的**返回顺序**（`NacosSkillRepository.java:220` 的 `LinkedHashSet` + 插入序，不排序）。所以切库后的顺序可复现，但只有 Nacos 恰好按字典序返回时才与切库前逐 id 相同。§18 T-13 预留的「跨源逐 id 一致」自动化因此仍写不出来——缺的不是测试，是两侧基准不同；V-12 必须真跑一次提示词 diff，本机实测归 P0-8。
2. **规则 2 落成 `Report.fileProblems`，不阻断**：坏 JSON、`mdHash` 与正文对不上、超配额，一律只记一条问题、正文照写、`resource_count` 保持 0。全量导入被一个坏包中断，等于让运维在几十条技能里手工挑出那条坏的。
3. **规则 3 无改动**：`importProject()` 内部就是 §8 那套单事务边界，导入器不再包一层批量事务。一批技能 = N 个事务，中途挂掉只回滚当前这条，前面已落的留着，重跑由第 4 条挡回去。
4. **规则 4 的幂等做在表级而不是行级**：`importAllIfEmpty()` 先读 `SkillRuntimeReader.catalog()`，非空即整批跳过并报告「skill_definition 已有 N 行，跳过导入（幂等）」。行级跳过（`overwrite=false` 撞已有行抛 409）仍然在，但那是 Git/ZIP 导入的语义，这里不能用：切换窗口内运维可能在 db 侧删掉某个技能，行级补齐会把它从 Nacos 复活。判空走 `catalog()` 而非 `SkillStore.list()`——后者把代码内置技能合进视图，首次导入永远被判成「非空」。
5. **规则 5 无改动**：`builtin` 列由 `importProject` 自己按 `RoleSkills.ids()` 推导；导入器给内置技能传的 `overwrite` 实参为 `true`，只为绕过「builtin 行不许覆盖导入」那条保护，表级判空已保证此刻库里一行都没有，不构成覆盖能力。
6. **补一条草案没有的：执行开关必须显式带过来**。Nacos 的 `execution.enabled` 存在 `<id>.md` 的 frontmatter 里，而 `importProject` 的端口语义是新行取 `false`（白名单默认关）。照默认走，开了沙箱的技能会在切库当天静默失去 `skill_run`——一个只让对话变笨、不报任何错的变更。因此加了 `executionEnabledOverride`（`JdbcSkillStore.importProjectForMigration`，`:941-944`，判定在 `:975-976`），只给迁移导入用，Git/ZIP 那条路径继续传 `null`。**P1-3 之后这条更值**：迁移入口给 `importProjectInternal` 传的 `operator` 是空串，落库即 `created_by = NULL`，所以「灌进来的 18 条」与「页面上改过的」在库里就是两回事，对账时无需再看审计。
7. **开灌之前先确认表读得出来**：判空只捕 `PayrollUnavailableException` 并转成 `skippedAll("读取技能表失败：…")`。库故障时继续往下跑等于反复尝试全量写入；把「读不出来」当成「空表」更糟——判空直接通过、开灌。
8. **两个新类比现网类多一条纪律：自身不读环境变量**，`SkillFileSettings`/`NacosSkillSettings` 一律由组装点给。Spring 与 CLI 侧走 `new SkillNacosSource()` / `LocalDiskSkillBlobStore.fromEnvironment()` 这类无参工厂（读环境变量发生在这两个工厂里，不在导入器/对账门里），测试显式 new 一份设置对象——否则 §18 T-05/T-16 的结论会随本机 `PAYROLL_SKILL_FILES_ENABLED` 漂移，这条在写第一版测试时就踩过。

### 11.3 切换前对账（硬门禁）

只读工具 `SkillMigrationCheck`（`payroll-tests` 集成测试 + 一个 `skill:manage` 的 dry-run 接口，二选一在 D4 评审时定）：
- 数量：Nacos 技能数 == `skill_definition` 行数（差值只允许来自内置技能策略）；
- 正文：逐条比 `content_hash`（对同一正文分别按 Nacos `mdHash` 与库内 `SHA-256` 规则计算，取等价的规范化输入）；
- 文件包：`path` 集合一致 + 每个文件 sha256 一致；
- 输出：`{match, bodyMismatch[], fileMissing[], fileExtra[], orphanEnvelope[]}`，任一非空则**禁止切换**。

**P0-6 落地情况（2026-11-23，未提交）** —— 类在 `payroll-common/.../skill/SkillMigrationCheck.java`，比对基准一律取库侧写入时用的那个函数（`SkillManifests.contentHash`），不在门禁里另立一套规范化规则，否则「对账通过」只证明本类自洽。

1. **D4 那道「二选一」定成集成测试 + 命令行，不做管理台接口**：形态是 `SkillMigrationCheckTest`（15 用例，v0.10 前 13）+ `PayrollApplication --skill-migration-check`（一致退出 0，不一致打印 `problemCount()` 与逐桶明细后退出 1）。理由只有一条但足够硬：**这道门要在还没切到 db 源之前跑**，而接口只有在应用起来后才存在，且切库当天最想用的正是「应用还没换配置」的那一刻。命令行两头直连（`SkillNacosSource` + `JdbcSkillStore`），与 `payroll.skills.source` 当前取值无关。
2. **比草案多比五项**：`missingInDb`（草案「数量」那条的正身）、`executionMismatch`（切库后 `skill_run` 静默消失只能靠这条抓，§11.2 第 6 条的反向验证）、`resourceHashMismatch`（D11b：`resource_hash` 进了智能体缓存指纹，行上的值与实读清单重算不一致就意味着切库当天全量重建缓存）、`extraInDb`、`problems`（读不出来、正文不合格、版本行缺失等解释性条目）。文件字节经 `SkillRuntimeReader.runtimeData()` 真读一次，顺带证明 blob 可取回——这是草案「每个文件 sha256 一致」唯一能落地的读法。
3. **「数量相等」没有做成计数比对**：草案那条按字面实现是 `nacosCount == dbCount`，而它恰好漏掉最常见的一种事故——切库窗口内删一条、加一条，计数照样相等。现在按 id 集合双向比：Nacos 有库里没有 → `missingInDb`（硬差异，阻断），库里有 Nacos 没有 → `extraInDb`（**不进 `match`**：切换后管理台只写 db，这一桶迟早必然非空，把它算成故障会让门禁在切库次日就长红）。两个计数仍进 `Result` 供人读，`problemCount()` 不含 `extraInDb`。
4. **失败方向是「不许切」，不是「当没有差异」**：三条读路径各自 catch —— Nacos 读不出、库目录读不出、资源字节读不回，一律落 `problems` 从而 `match=false`。目录整表读不出时还有一层保护（`Buckets.catalogRead`）：不逐条比，否则全表 Nacos 技能会被报成 `missingInDb`，运维会去找一个根本不存在的导入漏洞，而真因（连不上库）只躺在 `problems` 里。表**真的**为空时不走这条，逐条比照样报缺失。
   - **资源字节读不回走同一条短路（`Buckets.runtimeRead`，v0.10 补）**：`runtimeData` 是整批一次取回，一个 blob 读不到就整批抛，此时还逐技能比文件会把**这一次**失败写成每人一条「库内运行期数据缺失（版本行未落库或被删）」——现网 18 条技能即 1 条真因 + 18 条误诊、硬差异从 3 项涨到 22 项（§19 P0-8b 记下的那条 P1，本次已修）。现在只记一条 `读取技能资源失败，本轮未比对文件包（正文与执行开关仍已比对）：<原因>`，`fileMissing`/`fileExtra`/`fileHashMismatch`/`resourceHashMismatch` 四桶保持为空（没比成的事不给结论），不依赖字节的 `bodyMismatch`/`executionMismatch` 照旧逐条比。逐条「库内运行期数据缺失」只剩一种触发路径：整批读成功、单条版本行确实被并发删掉。
   - 配套：`JdbcSkillStore` 的「技能资源缺失」文案带上技能 id（`<id>/<path>`，`contents` 与 `runtimeData` 两条读路径同源）。整批装配时抛的是最先撞上的那条技能，只有路径分不清是单条脏数据还是 `PAYROLL_SKILL_BLOB_ROOT` 指错了目录。
5. **`filesEnabled=false` 时只比正文**，输出里的 `checkedFiles` 明写这一轮**有没有真的逐文件比过**：开关关闭、以及第 4 条的资源整批读不回都是 `false`（后者原因在 `problems` 里）。开关关闭时库里没有清单行、`resource_hash` 归零，再拿 Nacos 的文件清单去比就是自己造差异。
6. **它是切换前的门，不是持续的一致性监控**：切库之后 Nacos 停在切换那一刻，本类此时报出的差异是双写窗口的正常结果。§11.4 的双写窗口演练要在切库后另行安排，不能拿这条门禁当巡检用。

### 11.4 回滚

- 一级：`payroll.skills.source=nacos` 重启。Nacos 配置在切库后**不删除**（R2），期间禁止在管理台双写，db 源的写只在 db 生效 → 回滚会丢失切换窗口内的改动，因此 §19 P1 完成后必须先跑一轮「双写窗口演练」并在 `restore-runbook` 写明。
- 二级：`skill_definition`/`skill_version` 与主库同备份（MySQL binlog + 每日 dump），blob 目录并入既有备份脚本（`O4 备份任务`）——**必须**同快照序，否则恢复后 sha256 校验会大面积失败；runbook 写明「先恢复库、再恢复 blob 目录、启动后看 `skill.blob.integrity` 指标」。

## 12. 文件包与沙箱投送

| 项 | 现状 | 迁移后 |
| --- | --- | --- |
| 载体 | 一个 Nacos 配置项内联 JSON（≤4MB/200 文件/512KB 单文件） | per-file blob + `skill_resource` 清单 |
| 读单文件 | 解析整包（`NacosSkillRegistry.fileContent`） | `sha256` 直取，O(1) |
| 二进制 | 不支持（`SkillProjectParser.java:229-256` 跳过） | **仅沙箱可见**：可入库、可物化到工作区，但进不了 `AgentSkill`（`Map<String,String>`，§2.2-10），因此模型读不到（§13-3） |
| 沙箱 init 投送 | `SandboxClient.java:141-174` 把所有文件内容内联进一次 POST（30s 控制面超时） | P0/P1 不变（保持可比与最小风险）；P2 改 `tar.gz` 流式 multipart，配额见 §12.1 |

### 12.1 投送演算（必须先算清再改）

- 现配额 200 文件 × 512KB = 100MB 理论上限，但总量上限 4MB（`SkillFileSettings.DEFAULT_MAX_TOTAL_BYTES`）才是实际约束；一次 4MB JSON POST 在 30s 控制超时下安全。
- 若放开二进制到 `assets/`（例如 5MB 图片），4MB 总量会立刻挡住 → 因此**「提高上限」与「二进制支持」必须同一批次落地**，否则功能是残缺的。建议新默认：单文件 4MB、整包 32MB、200 文件、`assets/` 单独 64MB 预算（键名见 §16），且此时必须走流式投送（内联 JSON 序列化 32MB 会带来明显的 GC 与超时风险）。
- 沙箱侧不变量保持：`SkillInitProtocol` 的 2GB/5GB 数据配额、`DockerSkillInitRunner.java:160-207` 的只读 rootfs/tmpfs/1CPU/1GB/uid 65534/cap-drop ALL、`SandboxProtocol.java:19-29` 的执行期上限（10s、20 脚本、64KB/脚本、256KB 总、8KB 输出）。
- `scripts/**` 只允许 `.py|.sh|.js` 这条**不因迁移放宽**（R3）：对象存储让「塞一个可执行二进制进 scripts/」变得更Easy，风险反而上升，必须在写侧强制。
- 清理语义不变：`SkillWorkspaceService.java:207-241` 执行后删 `pkg/`、只留 `data/`；blob 是服务端权威，沙箱侧永远是临时物化。

### 12.2 二进制的真实收益边界（评审时别夸大）

因为 `AgentSkill.getResources()` 只能装字符串，二进制资产**不会**让模型多看到任何东西；它唯一的价值是「`skill_run` 时工作区里有这个文件，脚本可以自己打开」（字体、模板 docx、模型权重、示例图）。因此：
- 若当前没有这类技能诉求，D2/D6 选完之后二进制可以**完全不启用**（`PAYROLL_SKILL_ASSETS_BINARY=false`），P2-2/P2-3 整批推迟，本迁移仍有完整收益（事务、版本、单文件读、目录性能）；
- 一旦启用，`SkillReadTools` 必须显式拒绝二进制路径并返回可读提示（当前 `SkillFilePackage.isReadable` 已过滤，但要有测试锁死，§18 T-09），避免出现「模型请求读字体 → 拿到空字符串」这种无声错误。

## 13. 安全与权限

1. **路径白名单**：沿用 `SkillFilePackage.normalizePath()`（R3），db 源在 `JdbcSkillStore.replaceFiles()` 与 `importProject()` 入口走 `SkillFilePackage.normalizeAll(files, fileSettings)` 再校验一次——**P0-3 已落**（防的是将来绕开 `SkillProjectParser` 的 Java 调用方；运维直接写库不在它的覆盖范围里，那一层只有列宽与 `DdlSyntaxTest` 挡）；`path` 与 `storage_key` 不做任何字符串拼接进 SQL 或文件系统路径。
2. **权限**：全部端点维持 `@RequirePermission("skill:manage")`（`Permissions.java:34`）。新增 `versions`/`rollback` 同权限；读端点是否降为 `skill:read` 属于既有待办，本规格不扩大范围，仅在 §20 风险里登记。

   **P1-2 已按此落地（2026-11-23）**：`GET /{id}/versions` 也标 `SKILL_MANAGE` 而没有顺手降到 `SKILL_READ`——上面那句待办没拍板之前不给新端点开只读口子，且它与回滚成对使用（时间线列出的历史正文长度、操作者本就是管理面信息）。这条同时被 §18 T-08 里那条反射式权限测试覆盖：`isWriteMapping` 扫 `@PostMapping`，`rollback` 因此自动进名单，漏标就红（`SkillAdminControllerTest.java:585-588`）。
3. **二进制与嗅探**（受 §12.2 约束，只在沙箱侧有意义）：允许扩展名白名单（`.md .txt .json .csv .yaml .yml .py .sh .js .png .jpg .jpeg .gif .webp .pdf .woff2 .ttf`），MIME 由 magic bytes 判定而非信任客户端 `Content-Type`；`.pdf/.png/.ttf` 等只允许出现在 `assets/`；`binary=TRUE` 的行不得进入 `AgentSkill`，`skill_read` 命中时返回明确提示而非空内容。
4. **secret 扫描**：正文 + 文本类文件在保存前跑既有补丁扫描规则，命中 → 400 并列出命中文件名（不泄露命中内容），与 R6 一致。
5. **注入面**：`meta_json` 只作为透传字段存/取，不参与 SQL 构造；`description` 进系统提示词前继续过 `SkillContentRules.cleanDescription()`。
6. **审计**：新增动作 `skill.version.rollback`、`skill.import.db`、`skill.blob.gc`；现有 `skill.create/update/delete/files.update/files.delete/import/init.start/init.finish/data.delete` 全部保留，且在 `detail` 里带上 `version_no`。

   **第 6 条的版本定位部分已落（P1-1，2026-11-23，未提交）**，落地形状与草案有三处差别：
   - **键名用 camelCase 的 `versionNo`，不是 `version_no`**：审计 `detail` 的两个 JSON 列（`before_meta`/`after_meta`）现网早已是 camelCase（`skillId`、`contentLength`、`executionEnabled`），塞一个 snake_case 键进去会让读的人以为它是库列名直出。同时补一个 `revision` 键——只有 `versionNo` 无法回答「这次写的是哪个行版本」，而回滚要的就是它。
   - **Nacos 源与未落库的内置项两键都不写**，而不是写 `revision=""` / `versionNo=0`：库里恒为 0 的 `versionNo` 会被读成「第一个版本被覆盖了」，空串 `revision` 又与「前端没回传」看不出区别。判定规则只有一句：`revision` 非空才写、`versionNo > 0` 才写，且**只补缺失键**（`putIfAbsent`）——写接口返回的摘要已经带上本次写入后的 token，写后重读的快照不该把它盖成别人的新值。
   - **代价与偏差都要记账**：`versionNo` 来自 `detail()` 的那次单版本读取（`JdbcSkillStore.SELECT_VERSION_BODY` 顺带多取一列，目录查询保持无 join），但成功路径的 `create`/`update`/`import`/`files` 还要为审计**再读一次**库内快照。高并发下这个快照可能来自他人更新后的行，即 `after_meta` 里的 `versionNo` 偶尔会比本次写入大 1；接受的理由是它只影响审计可读性、不影响写正确性（写本身在事务里凭行锁完成），而「不重读」要在 Web 层推算 `before.versionNo + 1`，正文未变时根本不追加版本，推算会写出假版本号。重读失败只 warn + 少一个字段，绝不把已提交的写变成 5xx。

   **三个新动作里 `skill.version.rollback` 已随 P1-2 出现**（另两个 `skill.import.db`/`skill.blob.gc` 仍待 P1-3/P2-1——**前一个的判定已在下一条作废**，别照这句去找 `skill.import.db`）。它的 `before` 是写前的 `SkillDetail` 快照、`after` 是回滚后的摘要 + 写后重读，额外两键 `fromVersionNo`（取自写前快照的 `versionNo`）与 `toVersionNo`（取自写后快照）；后者在重读失败时**省略**而不是写 0——回滚事务已经提交，不能因为补一个审计字段把成功改成 5xx（同 §13-6 上一条的 `putIfAbsent` 口径）。

   **P1-3 更正了 `skill.import.db` 这一条（2026-11-24）**：它**不会以那个名字出现**，不是「还没做」。管理台导入（Git/ZIP）现网就记 `skill.import`，`after_meta` 里用 `source=git|zip` 分面（`SkillAdminController.java:321/359`），P1-3 只在这条已有记录上补齐 `revision`/`versionNo`；而 §11.2 的 `SkillNacosImporter` 是一次性迁移工具，压根不依赖 `AuditService`（全类 grep 无 `audit`），它的可追溯性由导入报告与 `created_by IS NULL` 承担，不新造一个动作名。**剩下的唯一未落动作是 `skill.blob.gc`**（随 §14/P2-1）。本批另有一条审计侧的规矩：**dryRun 一条记录都不写**，也不 `markRevision`——预览不是写入（`SkillAdminControllerTest.dryRunImportLeavesRevisionUnmarked` 同时断这两件）。

## 14. 保留策略与 GC

`SkillRetentionService`（沿用现有 `@Scheduled` retention job 形态，多实例用现有 Redis 锁避免并发 GC）：
- 版本裁剪：每技能保留 `version.keep`（默认 20），超出删最旧 `skill_version` 行及其 `skill_resource` 行。
- 孤儿 blob：`skill_blob` LEFT JOIN `skill_resource` 且 `skill_resource.sha256 IS NULL` 且 `skill_blob.created_at < now - grace`（默认 24h，给在途事务留窗口）→ 先删库行再删文件；删除失败只 warn，不阻断。
- 完整性巡检：抽样（默认每次 200 条）`get(sha)` 并核对 sha256，不一致计数 `skill.blob.integrity{result=corrupt}` 并告警（对应 R2 备份同序风险）。
- 指标：`skill.version.pruned.count`、`skill.blob.gc.count{result}`、`skill.blob.orphan.gauge`、`skill.blob.integrity{result}`。

## 15. 可观测性与门槛

沿用 `SkillMetrics`（已带 `source` tag，切换前后同名指标直接可比），新增：

| 指标 | 类型 | 门槛（验收） |
| --- | --- | --- |
| `skill.catalog.build.duration{source=db}` | Timer | 200 技能 P95 < **50ms**（Nacos 基线需先测，见 §17 A-01） |
| `skill.catalog.query.rows` | Gauge | == 技能数，单条 SQL。**P0-8a 已接**（`JdbcSkillStore.catalog()` 末尾），形态见下面的埋点清单 |
| `skill.content.load.count{source,result}` / `.load.summary` | Counter/Summary | 命中率 ≥ 0.80（与现网 Nacos 同量级，不得劣化） |
| `skill.resource.load.count{result}` | Counter | miss 时必须有对应 blob 读；`corrupt` 恒为 0 |
| `skill.blob.get.duration` | Timer | 512KB 文件 P95 < **15ms**（本地盘） |
| `skill.invalidations.count{trigger}` | Counter | `write`/`poll` 已报（P0-7），`redis` 随 P2-4；稳态量级 = 管理台写入次数 + 跨实例变更次数 |
| `skill.invalidation.lag` | Timer | 跨实例 P95 ≤ **20s**（D5 引入 Redis 后 ≤ 2s）。草案里的 `_ms` 后缀未采用：本仓库的 Timer 一律不带单位后缀（`skill.catalog.build.duration` 同），单位由注册表侧换算 |
| `agent.cache.rebuild.count{reason}` | Counter | 稳态 < 1/min；切库当日一次性抬升需记录基线 |
| `skill.blob.orphan.gauge` / `.integrity{result}` / `.version.pruned.count` | Gauge/Counter | orphan 回收后趋 0；corrupt 恒 0 |
| `skill.source.fallback.count{from,to}` | Counter | 稳态恒为 **0**（>0 即 P1 告警，R1）。指标常量是 `SkillMetrics.SOURCE_FALLBACK`，带两个标签，不是草案写的单标签 |

**埋点清单**（避免把「表里有」读成「已经在报」；P0-8a 之后更新，2026-11-23）：

- 已报：`skill.catalog.build.duration{source=db}`（打在 `JdbcSkillStore.catalog()`）、`skill.catalog.query.rows{source=db}`（**P0-8a 接上**：`SkillMetrics.catalogQueried()`，形态是本类内一个 `Map<String, AtomicLong>` + `Gauge.builder(name, value, AtomicLong::doubleValue)`，绑对象的强引用必须留在这张 Map 里——Micrometer 对绑定对象只持弱引用，传一个没人持有的临时 lambda 会在 GC 之后失去读数。没有选「绑 `DbSkillRepository` 快照」那条路：`SkillMetrics` 全静态、指标也不该反过来依赖某个仓库实例，且 CLI 侧不建仓库）、`skill.content.load.count{source=db,result=hit|miss}` + `.size`（`DbSkillRepository` 命中与回源两分支都打，size 只计回源）、`skill.source.fallback.count{from,to}`（三态标签见 §7.1）、`agent.cache.rebuild.count{reason}`、`skill.invalidations.count{trigger=write|poll}` + `skill.invalidation.lag`（P0-7：前者两条腿都打，后者只由轮询腿打，见 §10 末）。
- `skill.catalog.query.rows` 的**读失败不写 0**：`catalog()` 里行数只在查询成功时才有值，异常路径传负数、`catalogQueried()` 直接 return，Gauge 保留上一次成功读取的行数。理由是这条门禁的两个失败模式（退化成 N+1 / 漏行）都靠「行数 == 技能数」判定，把一次断库记成 0 行与库里真的没技能长得一模一样，告警侧分不开。测试锁死：`JdbcSkillStoreTest.catalogReportsQueryRowsAndKeepsLastGoodValue`（真内存库，DROP 掉 `skill_definition` 逼一次读失败——V22 的表之间没有外键，删得起）与 `SkillMetricsTest.reportsCatalogQueryRows`（负数不覆盖好值）。
- 未报，随后续批次：`skill.resource.load.count{result}`/`skill.blob.get.duration`（这两个是 blob 层的指标，`JdbcSkillStore.runtimeData()` 里 blob 读与 SQL 读混在一次调用内，单独计时随 P2-1 的 GC/巡检一起补，否则会给每次装配加两次时钟）、`skill.invalidations.count{trigger=redis}`（P2-4 的广播腿还不存在）、`skill.blob.orphan.gauge`/`.integrity{result}`/`.version.pruned.count`（P2-1）。
- **P95 类门槛的数据来源已就位（P0-8a）**：`skill.catalog.build.duration` 与 `skill.invalidation.lag` 两个 Timer 都开了 `publishPercentileHistogram()`，Prometheus 侧不再是只有 count/sum/max（补法与测试陷阱见 §10 末）。**门槛本身仍未判定**：A-01、V-03、V-07 要的是真机上的分位数样本，本机 db 源连一行技能都没有（见下条），直方图是空的。另注意 `management.metrics.distribution` 未做全局配置，只这两个 Timer 有桶；将来别的指标要判 P95 得同样补一行，或者接受用 `_sum/_count` 的均值糊过去。
- 命中率门槛 ≥0.80 仍然**测不了**：P0-6 把第一个写入方（导入器）交付了，但没有在任何真实实例上跑过一次导入——本机 db 源至今零技能行，A-01…A-04 基线一并归 P0-8。**db 源端到端跑通这件事，目前只有 JUnit 证据（内存库 + Fake Nacos），没有进程级证据。**

日志：`[Skill] 已保存技能 x（版本 n，m 个文件，source=db）`；**禁止**打印正文或文件内容（含 DEBUG 级）。装配侧现在另有两条 INFO：`[Skill] 装配技能 {id}（版本 {versionId}，{n} 字，{m} 个文件，source=db）` 与断行恢复的 `{id} 已恢复到可读版本`，都只报标识不报内容。

## 16. 配置键清单

| 键 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `payroll.skills.source` | `PAYROLL_SKILLS_SOURCE` | `nacos` | `nacos`/`db`，单源，运行期不热切 |
| `payroll.skills.import-from-nacos` | `PAYROLL_SKILLS_IMPORT_FROM_NACOS` | `false` | **只对 `source=db` 生效**（Nacos 模式下 runner 打一条 INFO 直接返回，理由见 §11.1 第 2 条）；空表整批跳过式幂等，见 §11.2 第 4 条。「只在 Web 侧开启」这条留给部署：runtime 的 yml 不置该键，代码里不再判一次进程角色 |
| `payroll.skill.blob.root` | `PAYROLL_SKILL_BLOB_ROOT` | `./data/skill-blobs` | 本地适配器根目录（`data/` 已在 .gitignore，与 KB 附件同处置）。**Spring 键随 P0-6 接完**（`PayrollCoreConfiguration.java:432`） |
| `payroll.skill.blob.max-bytes` | `PAYROLL_SKILL_BLOB_MAX_BYTES` | 524288（512KB） | **单个 blob 对象上限**，与下面那个「技能文件包单文件配额」是两个旋钮：前者管内容寻址存储拒绝多大的一个对象，后者管 Git/ZIP 导入与 Nacos 内联包。草案把两者写成同一行（原 `payroll.skill.blob.max-file-bytes` ↔ `PAYROLL_SKILL_FILE_MAX_BYTES`），P0-6 接线时按实际实现拆开。取 512KB 与现网单文件上限同值，切库第一天不放宽 |
| —（无 Spring 键） | `PAYROLL_SKILL_FILE_MAX_BYTES` | 512KB → 提案 4MB | 技能文件包单文件配额，环境变量专用：Spring 侧经 `JdbcSkillStore` 的 2 参构造与 `SkillImportService` 里的 `SkillFileSettings.fromEnvironment()` 读到。改默认值需在 §19 P2 与文档同步（见本节末 ⚠️） |
| —（无 Spring 键） | `PAYROLL_SKILL_FILES_MAX_BYTES` | 4MB → 提案 32MB | 同上，整包配额 |
| —（无 Spring 键） | `PAYROLL_SKILL_FILES_MAX_FILES` | 200 | 同上，文件数配额 |
| —（无 Spring 键） | `PAYROLL_SKILL_FILES_ENABLED` | `true` | 不变；关闭时只存正文（回滚开关）。对账门会同步只比正文，见 §11.3 第 5 条 |
| ~~`payroll.skill.resource-cache.max-entries`~~ | — | — | **P0-4 确认不引入**，理由见 §7.1（资源随正文同一条 LRU 缓存，没有第二层读路径） |
| ~~`payroll.skill.resource-cache.max-bytes`~~ | — | — | 同上 |
| `payroll.skill.catalog.ttl-ms` | `PAYROLL_SKILL_CATALOG_TTL_MS` | 60000 | 现有语义，db 源继续用作兜底刷新。**Spring 键随 P0-6 接完**（`dbSkillRepository` bean 的 `@Value`） |
| `payroll.skill.invalidation.poll-ms` | `PAYROLL_SKILL_INVALIDATION_POLL_MS` | 15000 | 0 或负数 = 关闭轮询（仅同进程失效）。**P0-6 已接成 bean**：`@Value` + `destroyMethod="close"`，只挂 db 源（端口决定，见 §11.1 第 6 条）。P0-7 那个 `SkillInvalidationPoller.pollMillisFromEnvironment()` 与配套的 `POLL_ENV`/`DEFAULT_POLL_MILLIS` 常量已删除——yml 里的 `${PAYROLL_SKILL_INVALIDATION_POLL_MS:15000}` 覆盖同一个环境变量，而 poller 只在 Spring 上下文里创建，留着就是第三份没人走的读法（§10 末第 7 条） |
| `payroll.health.skills-stale-seconds` | `PAYROLL_HEALTH_SKILLS_STALE_SECONDS` | 300 | `/health` 的 `skills` 组件新鲜度窗口。**P0-6 由 `payroll.health.nacos-stale-seconds` 改名而来**，`docs/architecture-hardening-prd.md` 的组件表同批改名；改名理由与被推翻的 §8 末「P0-5 第 3 条」见 §11.1 第 5 条 |
| `payroll.skill.version.keep` | `PAYROLL_SKILL_VERSION_KEEP` | 20 | 版本裁剪 |
| `payroll.skill.retention.enabled` | `PAYROLL_SKILL_RETENTION_ENABLED` | `true` | GC/巡检总开关 |
| `payroll.skills.assets.enabled` | `PAYROLL_SKILL_ASSETS_BINARY` | `false` | 二进制资产灰度开关（§12.1 与流式投送同批开启） |

> ⚠️ `PAYROLL_SKILL_FILES_*` 三个键在 `SkillFileSettings.fromEnvironment()` 已存在且语义为「Nacos 单配置项配额」。改默认值会让 P2 之前的沙箱/导入行为跟着变，因此**默认值调整必须与流式投送同一发布批次**，否则 30s init 超时会先炸。

## 17. 基线与验收标准

先测基线（改动前，作为对照写入本规格附录）：

| 编号 | 基线项 | 方法 | 现状预期 / **P0-8b 实测（2026-11-23，本机）** |
| --- | --- | --- | --- |
| A-01 | Nacos 目录构建耗时（P50/P95） | 现网实例 `/health` + `skill.catalog.build.duration{source=nacos}` | 与技能数线性。**nacos 侧（8080，20 技能）：count=32、sum=0.450861901 → 均值 14.09ms，但 P50/P95 读不出来**——那台 JVM 早于 P0-8a 构建，exposition 里 `skill_catalog_build_duration_seconds_bucket` 是 0 行，只有 count/sum/max。同实例另有 composite 44 次/0.2962739s（均值 6.73ms）、builtin 44 次/0.0001589s。**db 侧（8123，18 技能）：count=54、sum=0.057161504 → 均值 1.06ms，桶行在**：累计 25 例 ≤1ms、34 例 ≤1.05ms、50 例 ≤1.40ms、51 例 ≤1.75ms、53 例 ≤2.10ms、54 例（全部）≤2.45ms → **P50 ≈ 1.0ms、P95 ≤ 2.1ms** |
| A-02 | 管理台技能页首屏 | 浏览器计时 | 列表全量返回，无分页（`model-and-skill-scale-prd.md:64` 已记此问题）。改用 curl 7 次取中位（浏览器计时不可复现）：`GET /api/admin/skills` **nacos 中位 5.93ms / 最大 33.09ms（5477B、20 条）**，**db 中位 5.19ms / 最大 5.96ms（5329B、18 条）**。首屏在 20 条量级上两源无差别，差别只在 nacos 有两次 30ms 级别的抖动（Nacos HTTP 翻页在链路里）；技能数上去之后再复测才有意义 |
| A-03 | 冷启动首个回答延迟 | 记录 `agent.cache.rebuild` | 包含逐技能拉取。**未验证**：要跑一次真对话才有数，而 db 源的 `AgentRuntimeApplication` 起不来——它需要 `PAYROLL_AGENT_RUNTIME_TOKEN`，该令牌只存在于 IDEA 运行配置里，按红线不落文件、不进命令。冷启动侧只拿到间接证据：8125 上 db 源进程拉起即完成 18 条技能目录装配（A-01 的 54 次构建即来自它与管理面读写） |
| A-04 | 单文件读取代价 | `GET /api/admin/skills/{id}/files/x` | 解析整包。**poetry 的 `PROMPT.md`（文件 7947B；两源接口响应体同为 8328B，逐字节同源）**：nacos 中位 5.35ms / 最大 6.55ms，db 中位 6.08ms / 最大 17.70ms**。db 慢 0.7ms 且第一次多花 11.6ms——那是冷 blob 读（磁盘 `data/skill-blobs-e2e/`），随后 6 次都稳定在 5.3–6.4ms。结论：单文件读取的代价在 db 侧由 blob 适配器承担，没有出现草案担心的「解析整包」放大 |

> P0-8b 基线怎么读：**A-01 的对照侧缺一半**。db 侧的分位数从 P0-8a 起有真数据，nacos 侧那台是 P0-8a 之前构建的，只有均值。所以四对二的可比性只到「均值 14.09ms → 1.06ms」这个方向性结论，V-03 的门槛判定按这条降级（见下表 V-03）。
>
> 另有一条测量前提值得留下：A-02/A-04 的两侧请求打的是**两个进程**（8080 = nacos 源、8123 = db 源，同一台机器、同一个 MySQL/Nacos），不是同一进程切换开关。切库当天若要拿严格可比的数，得把 8080 也升到含 P0-8a 的构建，否则它永远吐不出桶行。

验收（db 源）：

| 编号 | 验收项 | 判定 |
| --- | --- | --- |
| V-01 | 对账全绿 | §11.3 报告 `problemCount() == 0`（草案那四个数组之外还含 `missingInDb`/`executionMismatch`/`resourceHashMismatch`/`fileHashMismatch`/`problems`，`extraInDb` 不计，见 §11.3 第 3 条）。**仓库侧的自动化形态已随 P0-6 落地**（`SkillMigrationCheckTest.freshlyImportedDatabaseMatches`，内存库 + Fake Nacos）。**P0-8b 已在真机跑过（真 Nacos 20 条 + 真 MySQL 8 的 `payroll_skill_e2e` 18 条，文件包比对开启）：退出码 1、硬差异 3 项**——`missingInDb：[ai-coding, user-talk]` + `bodyMismatch：[it-support：Nacos=70cebd51484e 库=037a493692a1]`。**三条都不是代码缺陷**：前两条是 Nacos 里那两份正文 `strip()` 后只有 22 字（门槛下限 200 字，`SkillContentRules.java:16`；顺带它们的 `##角色` 缺空格也过不了标题正则），导入器按规则拒收；第三条是本批 T-12 测量时往库里 PUT 的正文，真机不存在。所以这条的判定仍是「未通过」，而**卡点在数据**：切库前必须先把 `ai-coding`/`user-talk` 的正文补到达标（或删除），否则这道门永远红。另见 §19 P0-8b 的 blob 根目录运维前置 |
| V-02 | 回答质量不回归 | 50 题薪酬金标集（`docs/profile-baseline.md` 同源脚本）通过率与切库前一致；技能目录渲染逐字符一致 |
| V-03 | 目录性能 | A-01 对比，200 技能 P95 下降 ≥ 80%。**P0-8b 判定：不成立（证据不足，不是不达标）**。两个硬缺口：（1）**没有 200 技能**——本机 db 侧 18 条、nacos 侧 20 条，门槛写的是 200 条量级；（2）**对照侧没有分位数**——8080 那台是 P0-8a 之前的构建，`{source=nacos}` 只有 count/sum/max。能给的只有方向性一条：均值 14.09ms（nacos，20 条）→ 1.06ms（db，18 条），db 侧 P95 ≤ 2.1ms。**门槛值留到 P2-6 的 N-1000 压测或切库前的 200 条灌数据再判**，届时两侧都必须是含 P0-8a 的构建 |
| V-04 | 保存原子性 | 保存中途 kill 进程 → 重启后无「正文新、资源旧」的错配（结构上由 `version_id` 保证，需实测） |
| V-05 | 并发写 | 两会话同 `revision` 保存 → 一 200 一 409，且 409 方未产生脏版本 |
| V-06 | 回滚 | 管理台回滚 → 运行期在下一次目录构建读到旧正文，`skillFingerprint` 变、智能体实例重建一次 |
| V-07 | 滞后上界 | 双实例，A 保存 → B 生效 ≤ 20s（P95，10 次采样）。**P0-8b 只拿到 1 次真机样本：10.139s**（8123/8124 两个 db 源 JVM + 共享 MySQL，A 侧 PUT、B 侧轮询命中后 `skill.invalidation.lag` 计时；同批 `skill.invalidations.count{trigger=poll}=1`、`{trigger=write}=18`）。单次在 15s 周期内、离 20s 门槛有余量，但 **P95 要 10 次采样，这条按「未判定」收**，补齐 10 次的方法见 §18 T-12 的脚本化备注。另注意滞后口径本身是下界（§10 末第 4 条：写端与读端各用自己的 `System.currentTimeMillis()`，跨进程还混上 MySQL 的时间） |
| V-08 | 全量回归 | `mvn -o test` 通过（**当前基线 1405 用例，0 失败，4 跳过**，v0.13 的 P1-3 之后 2026-11-24；较 v0.12 的 1398 增 7 条：`JdbcSkillStoreTest` +3（`operatorWritesEveryProvenanceColumn`、`unknownOperatorLeavesProvenanceNull`、`importConflictsDifferByCurrentRevision`）、`SkillAdminControllerTest` +3（`writeEndpointsThreadOperatorFromAuditContext`、`realImportThreadsRevisionAndMarksIt`、`dryRunImportLeavesRevisionUnmarked`）、`SkillImportServiceTest` +1（`forwardsRevisionAndOperator`）。`mvn -o -q -DskipTests package` 同样通过。上一档 1398 来自 v0.12 的 P1-2（控制器 +4、存储 +5）。**跳过仍是 4 而不是 3，不是有测试被改坏**：v0.11–v0.13 三轮都没挂 `PAYROLL_TEST_MYSQL_*`（一次性库 `payroll_skill_e2e` 里的数据已被 V-12 重测用完，重开一次空库只为拿一个跳过计数不划算），于是 `DdlSyntaxTest.sharedMigrationsApplyOnRealMysql` 保持跳过——挂上就是 3 跳过、同一批用例（v0.8 那一档之所以是 3，正是 P0-8b 唯一一次真挂上 `PAYROLL_TEST_MYSQL_*`）。剩下 3 条既有条件跳过是 devteam 侧的 `DevGuardedFilesystemTest` 1 + `DevSandboxEditorTest` 1 + `DevTeamAiGuideE2eTest` 1。本条要的是切库当天再跑一次，不新增失败也不新增跳过 |
| V-09 | 降级 | 人为断开 MySQL → 管理台 503 明确报错、`skill.source.fallback` 计数、`/health` 变红，不出现静默旧数据回答 |
| V-10 | 备份恢复 | 按 runbook 从同序快照恢复库 + blob 目录 → 完整性巡检 corrupt = 0 |
| V-11 | 只改文件包也生效（原现存缺陷的回归） | 正文不动、只保存参考文件 → 指纹变化（`resource_hash`），门槛内（≤20s 跨实例 + 一次目录构建）对话里 `skill_read` 读到新内容。**同进程部分已由 P0-4a 达成**（管理写立即失效 + 指纹纳入资源哈希），剩余待验的是**跨实例**那一段；P0-7 给了它一个同 JVM 双腿的自动化形态（§18 T-15），但那条走的是**正文变更**；"只改文件包 → 对端读到新文件"这条整链仍由四段各自锁住、没做端到端：文件写 bump 行级 `revision`（T-04）、`revision` 进聚合指纹（T-04 的 `fingerprintFollowsEveryWrite`）、指纹变化只丢目录（T-15）、`resource_hash` 变了才回源重装配（T-03）。真双进程 + 共享 MySQL 的滞后测量仍是 T-12 手工项。改前的对照记录（同操作在 `AgentCache` 淘汰前读到旧内容）已无法在现网复现，只作历史依据 |
| V-12 | 提示词逐字符不变 | 切库前后同一智能体的系统提示词做 diff（含技能目录顺序），唯一允许的差异来自技能内容本身的变化。**这条只能真机跑**：目录顺序那一维在仓库侧无法自动化，理由见 §18 T-13 第（1）条。**P0-8b 已跑，判定：按草案口径不通过，且缺口与「内容」无关。**（本批末已拍 (b)+(a) 并实施重测，最终判定见本行末） 方法：同一台机器、同一份 Nacos、同一个 MySQL，用 `SkillRepositories.create(NACOS)` 与 `(DB)` 各装一份目录，先逐字段比 18 条共有技能，再按生产默认预算（`contextWindow=8192`/`numPredict=1024`，`PayrollAgentFactory.java:82-83`）渲染 `SkillPromptBudget.build(...)` 的块做逐行 diff。结果分四层：（1）**目录顺序这一维实测通过**——两边自然序都是「三个内置角色技能在前（`RoleSkills.ids()` 序）+ 其余字典序」，剔除掉只在 Nacos 存在的那两条之后逐位相同；逐位差异 15 处**全部来自集合成员不同**（Nacos 20 条 vs 库 18 条），不是排序不同。这条顺带把 §18 T-13 第（1）条「运行期 Nacos 序不排序、测试里造不出真返回序」的空缺补上了：真机 Nacos 2.1.1 的 `list("*suffix")` 返回的就是字典序，于是运行期 Nacos 序与导入后的 db 序**在本机这一份数据上相等**。留两个前提别读成普遍结论：只验了 2.1.1 一个版本、20 条单页（翻页边界外的序未验，翻页本身见 §2.2-3 那条「一次带回全部正文的翻页列表」），且「服务端恰好排序」这件事仍然不该写进测试契约；（2）**三个内置角色技能全量注入的块：遮掉技能标题上的 `_nacos`/`_db` 源标签后逐字节一致**；（3）**18 条一起请求、被预算截断成 2 注入 + 16 目录行的块：遮源标签后仍差 2 行、去掉所有空白也不相等**，两个成因都是字节级归一化而不是内容：目录行末尾渲染了正文长度（`（379B）` vs `（378B）`…16 行各差 1），因为 db 侧写入时 `SkillContentRules.requireBody()` 做了 `strip()`；注入正文按字符数截断，正文差 1 字就让截断点整段漂一行（185 行 vs 187 行）。`content_hash` 因此 18/18 不同（17 条只差首尾空白，`it-support` 差 23 字是本批 T-12 的写入）；（4）还有一条不影响提示词但影响指纹的不对称：db 侧目录一次查询就带 `resource_hash`，nacos 侧 `listManifests()` 在未载入正文时该字段是空串（`NacosSkillRepository.java:315,370`，只有 `poetry`/`chinese-songwriter-skill` 两条有文件包因而看得出来）——**这正是切库当天指纹必变、智能体实例重建一次的原因，属 §7.3 设计内**。**三条出路，切库前要拍一条**：(a) 改判定口径为「遮掉 `_nacos`/`_db` 源标签、遮掉目录行末尾的字节数、正文按 `strip()` 归一后逐字符一致，超预算截断点允许漂移」——零代码改动，代价是切库当天提示词里那 16 个字节数集体 −1，模型看得见但语义无关；(b) 目录行不再渲染字节数（`SkillManifest.catalogLine()` 去掉 `（NNNB）`）——一处小改动消掉 (a) 的那个变量，剩下只有截断点漂移，而截断只在超预算时发生；(c) 保存/导入都不再 `strip()` 正文——不建议，质量门槛与 `content_length` 都按 strip 后的正文算，改它等于放开首尾空白。**推荐 (b)+(a) 组合**。**已拍 (b)+(a) 并实施（2026-11-23，v0.9）**：`SkillManifest.catalogLine()`（`:57-66`）去掉长度段，只留 `- <id>：<描述>` 与有版本时的 `（v<版本>）`；`contentLength` 字段与 `SkillManifest.java:9` 的语义不动——管理台还在读它三处：`sort=size` 排序键、`stats.contentBytes`、列表 `meta.contentLength`（前端那一列，`SkillsView.vue:599`）（`SkillAdminController.java:767,798`），去掉的只是**进提示词**这一路。契约测试 `SkillPromptBudgetTest.catalogLineOmitsContentLength`（`:149-164`）断言目录行含 `- tax-guide：个税申报规则` 且不含任何 `B）`。判定口径按 (a) 改写为：**遮掉 `_nacos`/`_db` 源标签、正文按 `strip()` 归一后逐字符一致，超预算截断点允许漂移**——(b) 已经把目录行这一维消掉，剩下的漂移只在「注入正文超预算」时发生，且它改的是截断标记的位置而不是任何一条技能的语义内容；(c) 明确不采纳。**同一套真机重测（同库同 Nacos，`SkillRepositories.create(NACOS)`/`(DB)` 各装一份目录，18 条共有技能）**：目录段（`## 其他可用技能` 起）遮源标签后**逐行一致 = true**；注入正文归一后差异 **1/18**（`it-support`：Nacos=339 字 vs 库=363 字，即本批 T-12 测量真写进库里的那份正文，属数据不同而不是存储失真）；未归一时 18/18 全不同，其中 17 条只差首尾空白、差的就是那 1 个字符；注入段（目录段标题之前的全部）遮源标签后 nacos=4134 字符 / db=4137，公共前缀 4111 字符，其后只剩截断标记位置差 **2 行**（注入段 nacos=164 行 / db=166 行，db 侧多带两行才被截）——这正是新口径允许的那一维。**判定：按新口径通过**，切库当天剩余的确定差异只有两条，且都是设计内：正文首尾空白归一（不改语义）与 `resource_hash` 由空串变实值（§7.3 的实例重建）。草案原口径里 P0-6 之后新增的那条「已知变量」（§11.2 第 1 条：目录顺序）实测没有发生，判定改由内容归一化主导 |

## 18. 测试计划

| 编号 | 测试 | 位置 |
| --- | --- | --- |
| T-01 | `V22` DDL 在 H2 `MODE=MySQL` 与 MySQL 8 均可建表、索引生效 | **H2 已自动化**：`DdlSyntaxTest`（保留字扫描、索引列序/唯一性签名 + 索引名前缀认、`skill_id` 列宽与满长 ID 实插）+ `SchemaMigrationTest`（全新库、V21 历史库补建、半迁移库重放三种形态）。**MySQL 腿已实跑（P0-8b，2026-11-23）**：配上 `PAYROLL_TEST_MYSQL_JDBC/USER/PASSWORD` 后 `DdlSyntaxTest` 4 用例 0 跳过，日志里 `Database: jdbc:mysql://127.0.0.1:3306/payroll_skill_e2e … (MySQL 8.0)`、`Current version of schema payroll_skill_e2e: 22`、`Schema is up to date. No migration necessary.`——**这一趟同时是幂等证据**：该库先前已由同一套迁移建到位，重放为零操作，而 `assertIdentifiersPortable`/`assertSkillIndexes`/`assertSkillIdColumns` 三段真 MySQL 结构断言全过。凭据只从环境变量进，未落任何文件或命令 |
| T-02 | `LocalDiskSkillBlobStoreTest`：幂等 put、sha 校验、越界 key 拒绝、并发 put 同内容 | `payroll-tests`。**已完成（2026-11-22）**：9 个用例覆盖四项 + 配额拒绝、大小写指纹、批量读缺失缺席、delete 幂等；并发用例第一次跑就抓到 Windows 的 `AccessDeniedException` 语义（§6），修适配器而不是放宽断言 |
| T-03 | `DbSkillRepositoryTest`：`listManifests` 单查询（用查询计数桩验证不读正文/blob）、`manifest.resourceHash` 冷启动即为真值（不等正文进 LRU）、`getSkills` 顺序与缺失跳过 | 同上。**已完成（2026-11-23）**：16 个用例（P0-4 交付 15 个，P0-7 的 `refreshDropsCatalogOnly` 补第 16 个）除草案三项外，另锁 `skillExists`/非法 id/已在快照内的补查 id 三种**零 SQL** 路径、身份不符才回源（改 `version_id` 或 `resource_hash` 各触发一次）、目录断库沿用上次成功快照 vs 冷启动断库原样抛出、回源失败不兜旧正文、版本行缺失读作缺席且负缓存不重复查。桩必须现算哈希，见 §7 末「P0-4 落地情况」段末 |
| T-04 | `JdbcSkillStoreTest`：同事务、blob 先写后回滚清理、`revision` 不匹配 → `PayrollConflictException` | `payroll-tests`。**已完成（2026-11-23）**：15 个用例（P0-8a 补第 15 个：`catalogReportsQueryRowsAndKeepsLastGoodValue`）除上述三项外，还锁住「版本只跟正文走」（改文件包不追加版本、`skill_resource` 按版本各留一行）、三个 `resource_*` 与清单同事务重算（`resource_hash` 逐字节等于 `SkillManifests.resourceHash`，删空后读回空串而非补空格的 `CHAR(64)`）、`fingerprint()` 每次写都变（含只翻开关不动正文的写）、内置技能未落库不能删、`SkillFileSettings` 关闭时文件写入口 fail-closed。断言直查表（`SELECT COUNT(*)`、按 `version_id` 取旧版正文），不走被测代码的读路径 |
| T-05 | `SkillNacosImporterTest`：正常/坏 envelope/重复导入幂等/内置技能策略 | 同上。**已完成（2026-11-23）**：7 个用例，草案四项都在但两项换了口径。「重复导入幂等」实现成**表非空 → 整表跳过**（§11.2 规则 4：判空读 `SkillRuntimeReader.catalog()`，不是逐行 `exists`——逐行跳过会把管理台删掉的技能在下一次重启灌回来，那条断言直接写在这里）。「内置技能策略」即 D10a：三个角色技能带 `builtin=TRUE` 落库、描述取 `RoleSkills.description`、Nacos 无配置时执行开关为假。「坏 envelope」在 P0-6 不再算失败，只进 `Report.fileProblems`（§11.2 规则 2），断言因此是「正文照旧导入 + 报告里有这一条」。另三条：整包文件落库后清单与字节都能从库里读回；单个技能写失败不中断其余、失败项进报告；Nacos 读不到与技能表读不到各一条，都必须是「未执行」而不是抛异常或当成空表开跑 |
| T-06 | `SkillRetentionTest`：裁剪保留 N 版、孤儿 blob 宽限期、巡检 corrupt 计数 | 同上 |
| T-07 | `SkillFingerprintContractTest`：仅 `content_hash`/`resource_hash`/`execution_enabled` 变化才导致 `definitionSignature` 变化；`source` 变化只重建一次；**只改文件内容、正文不变 → 指纹必须变化**（§7.3 回归） | 锁死 §7 语义。**已完成（2026-11-23，db 源半边）**：`SkillFingerprintContractTest` 4 用例直接拼出 `id:source:contentHash:resourceHash:exec;` 期望串比对 `PayrollAgentFactory.skillFingerprint()`，覆盖「目录指纹 == 装配后指纹」「只换资源包 → 指纹变」「执行开关 → 指纹变」「未发布技能 → `id:missing;` 且互不影响」。Nacos 源那半边此前已由 `AgentCacheTest.skillFingerprintCoversResourceContentAndPath` / `skillFingerprintStableForMissingSkill` 与 `NacosSkillRepositoryTest.resourceHashTracksFilePackage` / `invalidateDropsRuntimeSnapshots` 覆盖。**剩余缺口**：`definitionSignature` 端到端（真智能体实例只建一次的计数）与「`source` 由 nacos 变 db 只重建一次」——前者要在 Spring 上下文里跑，与本批的单元测试不同形，列入 P0-8；后者要等 P0-6 的开关能真切换。**P0-8b 收口状态**：前半条**仍未验证，原因具体了**——要拿「实例只建一次」的计数就得跑一次真对话，而 db 源的 `AgentRuntimeApplication` 需要 `PAYROLL_AGENT_RUNTIME_TOKEN`（只在 IDEA 运行配置里，按红线不落文件、不进命令行），本批只做到了「db 源进程能起来并完成 18 条技能的目录装配」；间接证据是 8123/8124/8125 三个 db 源 JVM 全程正常服务管理面读写。后半条**建议判为不需要**并留评审拍板：源切换按 D4a 是重启生效，运行期不存在「nacos 变 db」这个事件，于是它能被观测的形态只剩「同一 `definitionSignature` 命中同一个实例」，而那属于 `AgentCache` 的既有契约（`AgentCacheTest`），不该由技能存储的测试计划重复持有。**本批没动代码，也没新增这条测试** |
| T-14 | 历史库升级不被 V22 打断：`PayrollDatabaseBaselineTest`（补建只重放到基线）+ `SchemaMigrationTest.v21LegacyDatabaseAppliesV22AfterBaseline` / `partiallyMigratedLegacyDatabaseReplaysIdempotently`（半迁移库原样重放） | `payroll-tests`。**另已用本机 11-14 的真实历史库副本实跑**（33 表、无 `flyway_schema_history`）：补建止于基线 → Flyway 只执行 V22 → 49 表，`employees`/`app_users`/`chat_messages` 行数逐张不变；一次性诊断用例跑完即删，未留档 |
| T-13 | `SkillCatalogOrderContractTest`：db 源的 `listManifests()` 顺序与切库前逐 id 一致（V-12 的自动化形态，用导出的顺序快照断言） | 保提示词可比。**仓库侧已完成（2026-11-23）**：3 用例用一份**故意既非字典序也非插入序**的桩顺序（`validator → poetry → calculator → df-helper`）锁住「按 id 排序能碰巧过、按 store 顺序必须显式保留」，覆盖进提示词的三个入口（`listManifests`/`getAllSkillNames`/`getAllSkills`）给出同一顺序、5 轮 `invalidate()`+TTL 过期重建后顺序不漂、`getSkills(ids)` 按调用方顺序返回。**两处有意不测**：（1）**跨源逐 id 一致**（即 V-12 的正身）**到 P0-6 之后仍然写不出来**，但理由换了。P0-6 确实补上了写入方：导入按 `SkillNacosSource.readSkills()` 的序落 `sort_order`（内置在前按 `RoleSkills.ids()`，其余字典序，`SkillNacosSource.java:88/95`），db 源目录 `ORDER BY sort_order ASC, skill_id ASC`（`JdbcSkillStore.java:83`）读回的就是这一份序，`SkillNacosImporterTest.emptyTableImportsEverySkillInOrder` 已经把「导入序 == db 目录序」锁住。对不齐的是另一侧：**运行期** Nacos 源的目录序是 `LinkedHashSet(RoleSkills.ids())` + 列表响应原序、**不排序**（`NacosSkillRepository.java:220`），而管理台 `list()` 才是内置在前 + 字典序（`NacosSkillRegistry.java:204`）——同一份库里的两个读端口本身就不保证同序。db 序只有在「Nacos 列表接口按 dataId 字典序返回」时才与运行期 Nacos 序相等，那是服务端行为，测试里造不出真返回序，也不该把「对端恰好排好序」写成契约。所以 V-12 只剩真机 diff 一条路：切库前后各导一次系统提示词做逐字符比对，归 P0-8，同时 §11.2 第 1 条把它登记成切库当天的已知变量。（2）`nameById()` 不在这条契约里，它只给工具组激活中间件查表，而 `BuiltInSkillRepository.nameById()` 用 `Collectors.toUnmodifiableMap` 返回的就是无序 map，硬锁顺序是给一个没人依赖的性质上锁（`CompositeSkillRepositoryTest` 里同一条按集合比） |
| T-15 | `SkillInvalidationPollerTest`：首轮只登记基线、revision 不变不动缓存、变化触发一次失效并记滞后、时钟倒挂截零、失败轮次不推进基线且调度不中断、间隔非正数不起线程、间隔为正时线程自己跑起来、真库双腿端到端 | `payroll-tests`。**已完成（2026-11-23）**：8 个用例。前七个用桩指纹（`revision`/`lastWriteMillis`/注入异常都是字段）逐条手调 `pollOnce()`，滞后断言直接读 `SimpleMeterRegistry` 里的 `skill.invalidation.lag`；「失败不中断调度」那条靠 `pollQuietly()` 的包级可见性直接调用，不用睡过一轮。「间隔为正时线程自己跑起来」确实起真线程，但把间隔压到 20ms、每 20ms 翻转一次 `revision` 直到线程读到两种取值（单次赋值可能正好赶上某一轮在飞、被当成基线吞掉），5s 内必然收敛，不等 15s 默认周期。最后一条不打桩：同一份共享内存库上开两个 `JdbcSkillStore` 当两个节点，B 侧 `DbSkillRepository` + 轮询器，断言 A 侧的新正文**只在一次轮询之后**才出现（TTL 设 60s，排除了自己过期的可能），且同一 revision 的下一轮不再失效。`refresh()` 与 `invalidate()` 的分工（只丢目录 vs 连正文一起丢）由 `DbSkillRepositoryTest.refreshDropsCatalogOnly` 用查询计数桩锁住，本条不重复。写进来的正文要过 §11 质量门槛，测试用一个 240 字的构造器，短正文会在 `create()` 就被拒 |
| T-16 | `SkillMigrationCheckTest`：§11.3 对账门的仓库侧形态 | `payroll-tests`。**已完成（2026-11-23），v0.10 补至 15 用例**：一条全绿基线（`freshlyImportedDatabaseMatches`：导入完直接对账，正文 / 文件 / 执行开关 / `resource_hash` 逐项对上）+ 八类差异各一条（Nacos 侧改过正文、库里删掉技能 → `missingInDb`、库里删文件 → `fileMissing`、库里加文件 → `fileExtra`、文件字节与清单不符 → 逐文件哈希、行上 `resource_hash` 与实读字节不符 → D11b 那条门禁、执行开关漂移、只剩 envelope 的 `orphanEnvelope`）+ 六条判定规则（`extraInDb` 不阻断且不计硬差异、`filesEnabled=false` 只比正文、Nacos 读不出 fail-closed、技能表读不出 fail-closed、**资源整批读不回只记一条 blob 侧问题**（`unreadableBlobsReportOnceNotPerSkill`：拿另一个空 blob 目录建 `JdbcSkillStore` 当读端，与现网 `PAYROLL_SKILL_BLOB_ROOT` 指错同形；断掉 `Buckets.runtimeRead` 短路后该用例立刻红，实测 1 条真因变 5 条）、**整批读成功但单条版本行不在仍逐条报运行期缺失**（`missingRuntimeEntryStillReported`：SQL 把 `current_version_id` 指向不存在的行，证明上一条短路没把这条真差异一起短路掉））。库侧故障用匿名 `SkillRuntimeReader` 抛 `PayrollUnavailableException` 造，不是关连接池——`PayrollDatabase.inMemory()` 的 `close()` 是 no-op，拔不掉。它抓到的那两个生产缺陷（目录整表读不出级联成 `missingInDb`、一个 blob 读不回级联成每人一条「库内运行期数据缺失」）记在 §19 P0-6 / P0-8b，两处改的都是 `Buckets` 上的短路而非断言 |
| T-17 | `SkillMetricsTest`：埋点自身的契约（不是门槛判定，是「有没有数据流出去」） | `payroll-tests`。**P0-8a 补两条（2026-11-23），现 8 个用例**：（1）`reportsCatalogQueryRows`——`catalogQueried(source, -1)` 不得覆盖上一次的好值，与 `JdbcSkillStoreTest` 那条真库断言同侧（§15 埋点清单第 2 条）。（2）`publishesPercentileHistograms`——`skill.catalog.build.duration` 与 `skill.invalidation.lag` 抓出来必须有 `_bucket{` 行。**这条测试最初用 `SimpleMeterRegistry` 写，是假绿灯**：那个注册表不实现分位数发布，`takeSnapshot().histogramCounts()` 恒空，断言直接红；改成挂一个真的 `PrometheusMeterRegistry` 进 `Metrics.globalRegistry` 再 `scrape()` 数桶行。记在这里是因为 §15 的 P95 门槛从此只有这一条自动化证据，写法退回去就等于门禁没有数据来源 |
| T-08 | `SkillAdminControllerTest` 扩展：新端点、409、ETag、`skill:manage` 鉴权、审计含 `version_no` | **五项全部已落（P1-1 于 v0.11、新端点 + ETag 于 P1-2 v0.12，2026-11-23，未提交）：控制器 21 → 26 → 30 用例、存储 15 → 16 → 21 用例，两批各自全绿。** 前置「现有 21 个用例全绿」达成后才动刀，改的只有 DTO  arity（`SkillSummary` +`revision`、`SkillDetail` +`revision`/`versionNo`）与 stub 换到带 token 的重载，没有一条断言被放宽。P1-1 的五条新用例各锁一件事：（1）`staleRevisionPropagatesWithoutAudit` —— 409 原样抛出且 `auditService.record` 一次没调；（2）`writeEndpointsMarkRevisionOnAuditContext` —— 四个写端点都把回传值 `AuditContext.markRevision()`，落 `audit_log.revision` 列；（3）`auditSnapshotFailureKeepsWrite` —— 写后重读抛异常时写结果照常返回、审计照记（把已提交的写变成 5xx 是最贵的错）；（4）`dataAuditsCarryVersion` —— `init.start`/`init.finish`/`data.delete` 三类只有 `SkillData` 没有正文的快照也带上版本定位键；（5）`writeEndpointsRequireSkillManage` —— 反射扫 `@PostMapping`/`@PutMapping`/`@DeleteMapping`，每个写方法的 `@RequirePermission` 必须是 `skill:manage`（全仓库此前**零**条权限测试，这条是第一个，将来漏标的端点自己会红）。既有 `updateWritesAudit`/`deleteWritesAudit`/`replaceFilesWritesAudit`/`deleteFileWritesAudit`/`createWritesAudit` 五条改断言：`verify` 到带 token 的重载、审计前后快照都含 `revision`/`versionNo`、文件端点的响应体回吐写后的新 token。`JdbcSkillStoreTest` 同步 15 → **16 用例**：`detailCarriesRevisionAndVersionNo`（详情与列表都回吐 revision、换文件包只涨 revision 不涨 versionNo、`versionNo` 与同一次读取的正文配对、内置项两键皆空）+ `staleRevisionRejectsEveryWriter` 补上 `deleteFile` 那条第四类写入并断 409 后清单行仍在。**四处变异验证**（改完立刻恢复，均红在被锁的那一条上）：控制器 `update` 把 `revision` 传成 `null` → 红 3 条（`updateWritesAudit`/`staleRevisionPropagatesWithoutAudit`/`auditSnapshotFailureKeepsWrite`）；删掉 `update` 成功路径的 `putVersion(after, persisted(...))` → 红 `updateWritesAudit:178`；`JdbcSkillStore.detail()` 的 `versionNo` 写死 0 → 红 `detailCarriesRevisionAndVersionNo:156`；`summary(Row)` 的 `revision` 写死空串 → 红同一用例的 `:158`。 |
| T-08b | 同 T-08 的 P1-2 半边：`versions`/`rollback` 新端点 + 文件接口 ETag | **已完成（2026-11-23，未提交）**。控制器 26 → **30 用例**（净 +4，其中 `fileReturnsContent` 被 `fileReturnsContentAndEtag` 原位替换）：`fileReturnsContentAndEtag`（响应头 `ETag == "\"" + sha256(正文) + "\""`、路径去掉前导 `/`）、`fileHonoursConditionalGet`（精确值 / `W/` 弱前缀 / 逗号分隔多值 / `*` 四种都返回 304 且**响应体为 null、ETag 照吐**，陈旧值仍 200）、`versionsReturnsTimeline`（`{id,total,versions}` 形状、`id` 取 `SkillIds.normalize` 后的值即 `" payroll-auditor "` → `payroll-auditor`、非法 id 抛 `PayrollException`）、`rollbackWritesAuditWithVersionPair`（`skill.version.rollback` 的 `before_meta` 带 `fromVersionNo`、`after_meta` 带 `versionNo`+`toVersionNo`，用 `detail` 的两次不同快照喂前后值）、`rollbackMarksRevisionAndPropagatesConflict`（409 原样透传、`AuditContext.current().revision()` 在 `begin()` 内可见、`auditService.record` 一次没调）。`JdbcSkillStoreTest` 16 → **21 用例**：`rollbackRestoresBodyAndManifest`（回滚到 v1 后 `current_version_id` 指向**新增的 v3** 而非指针回拨、正文回到初版而描述与执行开关不动、`skill_resource` 按新版本各留一行而 `skill_blob` 仍 1 行即复制的是清单不是字节、两条历史 `body_md` 逐字不变）、`rollbackRejectsUnusableTargets`（当前版本 / 不存在的序号 / revision 不匹配 / 内置技能四种失败后库里一字未动）、`rollbackFailsClosedWhenBlobMissing`（删掉一个 sha 后回滚被拒且不留下空版本行）、`versionsTimelineFollowsPointer`（倒序 + `change_note` + `current` 按 `current_version_id` 判定（回滚后是 `[true,false,false]`）+ 内置与未知 id 返回空 + `createdBy()` 是空串（P1-2 时它是「store 不收操作者」的欠账断言，P1-3 接线后改断「窄签名写入落 NULL、回读空串」，见 T-08c））、`changeNoteFollowsBodyOrCurrentRow`（正文变→落新行且 trim、正文不变→补写当前行、空白不清空、>200 报错且 revision 不涨、恰好 200 放行）。**十一条变异验证**（`D:\tmp\p12_mutation.py`，跑完即恢复；每条**恰好红 1 条用例**，即这十一条锁的性质互不覆盖）：M1 摘掉长度门槛→`changeNoteFollowsBodyOrCurrentRow`；M2 空说明改成照写→同条；M3 摘掉「已是当前版本」判定→`rollbackRejectsUnusableTargets`；M4 跳过 blob 体检→`rollbackFailsClosedWhenBlobMissing`；M5 回滚说明写空→`rollbackRestoresBodyAndManifest`；M6 不复制 `skill_resource`→同条；M7 时间线改升序→`versionsTimelineFollowsPointer`；M8 `current` 写死 true→同条；M9 摘掉条件请求→`fileHonoursConditionalGet`；M10 不回吐归一 id→`versionsReturnsTimeline`；M11 `fromVersionNo` 写死 0→`rollbackWritesAuditWithVersionPair`。恢复后基线复跑 21 + 30 全绿 |
| T-08c | 同 T-08 的 P1-3 半边：操作者贯穿三条写路径 + 导入的 revision 契约 | **已完成（2026-11-24，未提交）**。三层各加一条最能说明问题的用例，另把 11 处 Mockito stub/verify 迁到最宽重载（**这批测试的改动量集中在 stub 而不是断言**：控制器只调最宽签名，窄签名就成了死桩——这个连带改动本身就是「操作者有没有真的一路传到底」的证据）。`SkillAdminControllerTest` 30 → **33**：`writeEndpointsThreadOperatorFromAuditContext`（`AuditContext.begin(HR, …)` 里调 `update`/`replaceFiles`，验端口收到的是 `"hr01"` 而不是空串——上下文与端口之间只隔着 `operator()` 一个方法，接错一次就静默丢归属）、`realImportThreadsRevisionAndMarksIt`（真导入把 `?revision=` 一路带到 `importGit(…, "9", "hr01")`，且 `AuditContext.current().revision()` 在 `begin()` 块里可见）、`dryRunImportLeavesRevisionUnmarked`（`MockMultipartFile` 走 ZIP 预览：`revision()` 仍是 `null`、`auditService.record` 一次没调、但令牌与操作者照样透传给 `importZip`——**预览要传对参数，只是不许留痕**）。`JdbcSkillStoreTest` 21 → **24**：`operatorWritesEveryProvenanceColumn`（五个互不相同的哨兵值串完 import→update→replaceFiles→deleteFile→rollback，一次验三件事：`created_by` 不随后续写变、`updated_by` 每写必刷、每条版本行各自归属；`change_note` 与人名的槽位也在这条里，**槽位互换时它是那条断言级失败**——实测 22 条一起红，见行末）、`unknownOperatorLeavesProvenanceNull`（窄签名写入 → 直查库是三列全 NULL，而 `versions()` 回读成空串——「库里没有」与「库里是空串」是两回事，只有绕过被测代码才看得出来）、`importConflictsDifferByCurrentRevision`（覆盖确认门的 409 `currentRevision()==null`、令牌过期的 409 带真值且**一字未改**，随后带对令牌的导入只刷 `updated_by`）。`SkillImportServiceTest` 12 → **13**：`forwardsRevisionAndOperator`（`ArgumentCaptor` + `times(1)`，含 dryRun 那次不许落库）。<br>**十一条变异验证**（`D:\tmp\p13_mutation.py`，跑完即恢复）：P1 `operatorOrNull` 空白改成 `"admin"`、P2 `INSERT_VERSION` 的说明列与作者列互换、P3 回滚不带操作者、P4 插入不写 `created_by`、P5 更新不写 `updated_by`、P6 覆盖确认门泄漏 `currentRevision`、P7 `SkillImportService` 丢掉令牌与操作者、P8 Git 导入丢令牌、P9 ZIP 导入丢操作者、P10 dryRun 无条件 `markRevision`、P11 控制器 `operator()` 不读审计上下文。<br>**实测红了谁**（`D:\tmp\p13_names.txt`，两轮一致）：P1 → `versionsTimelineFollowsPointer`、`unknownOperatorLeavesProvenanceNull`（空白被写成 `"admin"`，两条断 NULL/空串的立刻红）；P2 → **22 条同时红**（21 条 error、1 条 failure），只有 `operatorWritesEveryProvenanceColumn` 是断言级——槽位一换整条 `INSERT_VERSION` 参数错位，其余用例在准备阶段就炸，真正锁这两列的是它那两句断言；P3 → `operatorWritesEveryProvenanceColumn`；P4/P5 → `operatorWritesEveryProvenanceColumn` + `importConflictsDifferByCurrentRevision`（`created_by`/`updated_by` 断掉会连带覆盖导入的归属断言）；P6 → `importConflictsDifferByCurrentRevision`；P7 → `SkillImportServiceTest.forwardsRevisionAndOperator`；P8 → 控制器 `importGitWritesAudit`/`realImportThreadsRevisionAndMarksIt`/`importDryRunSkipsAudit`（3 条 Mockito error：窄重载成了多余桩）；P9 → `dryRunImportLeavesRevisionUnmarked`；P10 → `dryRunImportLeavesRevisionUnmarked`；P11 → `writeEndpointsThreadOperatorFromAuditContext`、`realImportThreadsRevisionAndMarksIt`、`dryRunImportLeavesRevisionUnmarked`。跑完恢复源码后基线复跑 24 + 13 + 33 全绿 |
| T-09 | `SkillResourcePathGuardTest`：`..`、绝对路径、`scripts/**` 非白名单扩展名、`data/**`、二进制越出 `assets/` 全被拒 | 安全回归 |
| T-10 | 端到端（本机）：ZIP 导入一个含 `references/`+`scripts/` 的目录式技能 → 对话内 `skill_read` 取文件 → `skill_run` 沙箱执行 → 管理台改正文触发回滚 → 二次执行 | 需 IDEA Run 起 Web+runtime |
| T-11 | 性能对比：A-01…A-04 与 V-03 数据表回填本规格 | **数据已回填（P0-8b，2026-11-23），「脚本化」这一列没达成**。A-01/A-02/A-04 的实测数在 §17 表内，A-03 明写未验证与原因。本轮的取数形态是手工的：`curl` 逐次计时 + 抓两侧 `/actuator/prometheus` 的 `skill_catalog_build_duration_seconds_{count,sum}` 与 `_bucket{` 行算分位，没有留脚本。要脚本化缺两件（1）一个稳定的灌数据入口（200 条技能，见 V-03 的判定为什么只能降级）；（2）8080 那台升到含 P0-8a 的构建，否则对照侧永远只有均值。**顺带记一条取数口径**：`_bucket` 是累计计数，P95 要在 `count` 已知的位置上找第一个累计数 ≥ 0.95×count 的桶，读成「第 95 行的桶边界」会偏；本轮 db 侧 54 例 ⟹ 找第 52 例落在哪一行 |
| T-12 | 多实例：两 JVM + 共享 MySQL，验证 V-07 | **形态已跑通，样本只有 1 次（P0-8b，2026-11-23）：10.139s**。8123/8124 两个 db 源 JVM 指同一份 `payroll_skill_e2e`，A 侧 `PUT /api/admin/skills/it-support`，B 侧轮询命中后由 `skill.invalidation.lag` 计时；同批 B 侧 `skill.invalidations.count{trigger=poll}=1`、A 侧 `{trigger=write}=18`，即「写端自己刷一次、对端靠轮询刷一次」这个分工在真进程里对上了。**补齐 P95（10 次）的做法**，下轮照做即可脚本化：循环 10 次，每次用**新正文** PUT A（正文必须真的变，否则聚合指纹不动、B 侧不会失效；本轮为了每次都能触发，用了带序号的正文），随后只读 B 侧 `skill_invalidation_lag_seconds_{count,sum}`，在 `count` +1 的那一步取 `Δsum/Δcount` 作为该样本，10 个样本取第 95 百分位；A 侧的 `{trigger=write}` 计数不参与判定（同进程主动失效滞后是亚毫秒，V-07 要的是跨实例那一段）。**三条别踩**：（1）读端必须是没参与写入的那个 JVM；（2）`poll-ms` 保持 15000 默认值，别为了快测把周期调短，那测出来的不是本条门槛的口径；（3）滞后是下界（§10 末第 4 条，写读两端各用自己的时钟、跨进程还混 MySQL 时间）。测量期间写过库里正文，是 V-01 那条 `bodyMismatch：[it-support]` 的来源 |
| T-18 | `SkillDbSourceWiringTest`：db 源的容器装配契约（`@Primary` 那条 P0 的锁） | `payroll-tests`。**已完成（P0-8b，2026-11-23）**：3 个用例，每个起一个真的 `AnnotationConfigApplicationContext`，**只注册 `PayrollCoreConfiguration`**。装配手法只有一处不显然：整份 bean 定义要在 `addBeanFactoryPostProcessor` 里改成懒加载——那个配置类有几十个 `@Bean`，默认 `refresh()` 会全实例化，就要真连 Nacos、ES、Redis 与模型；标志位也不能在 `refresh()` 前设，`@Bean` 定义是 refresh 过程中才注册的，之前遍历 `getBeanDefinitionNames()` 一个也看不到。懒加载后真正被创建的只有断言那条链（`skillRepository` → `jdbcSkillStore` → `payrollDatabase`/`skillBlobStore`），库侧是每测试一份内存 H2（`payroll.db.url` 带 `System.nanoTime()`，技能表由 Flyway 现建）、blob 侧 `@TempDir` 风格的一次性目录、`payroll.skill.invalidation.poll-ms=0` 让轮询器不起线程；`@BeforeEach`/`@AfterEach` 都要 `SkillRepositories.resetShared()`，因为 bean 会把实例写进进程级共享槽位。断言四件事：`source=db` 时按类型注入 `AgentSkillRepository` 命中 `CompositeSkillRepository`（内置回退在切库当天还在）、`getBeanProvider(...).getIfAvailable()` 是同一个实例（`AgentAdminController` 用的正是这条调用，**去掉 `@Primary` 它抛 `NoUniqueBeanDefinitionException`**，与真机同形）、`SkillRepositories.shared()` 也是同一个（不是的话读路径会再开一个连接池）、`DbSkillRepository` 仍可按具体类型拿到；加上 `source=nacos` 时按类型 `getIfAvailable()` 为 `null` 且 `DbSkillRepository` 的 bean 数为 0。本条补的是 P0-1…P0-7 七批都漏掉的形态：那些测试手工 `new` 对象，装配歧义只有在真容器里才暴露 |

## 19. 分阶段交付

**P0 存储层与运行期读（可独立上线，默认 `nacos` 不改行为）**
- P0-1 `V22__skill_definition.sql`（+ H2 冒烟）**已完成（2026-11-22，未提交）**：四表 + 六索引按 §5.2 落地，并新增 `SkillIds` 作为 ID 规则单一来源（§8 末）。与草案的偏差：`binary` → `is_binary`、删掉与唯一键同列序的冗余索引。回归顺带修掉一个潜在缺陷：历史 H2 库的幂等补建原先越界重放到 V22，Flyway 随后真正执行同一版本会撞已建对象导致启动失败，现按 `PayrollDatabase.appliedBaseline` 只补到基线为止。全量 `mvn -o test` 1294 用例 0 失败 4 跳过（新增 7 个，跳过含未配置的 MySQL 腿）。补建路径另用本机历史库副本验证过一次，见 §18 T-14。**幂等形态在 P0-3 复核时改过一轮**：首版按「建表建索引统一 `IF NOT EXISTS`」写，`CREATE INDEX IF NOT EXISTS` 在 MySQL 8 上是语法错，改成整表 `IF NOT EXISTS` + 索引折进建表体，理由与两库索引名差异见 §5.2 要点。
- P0-2 `SkillBlobStore` 端口 + `LocalDiskSkillBlobStore` + 完整性/配额——**已完成（2026-11-22，未提交）**：端口与 `BlobRef` 落在 `payroll-common/.../skill/`，本地磁盘适配器按 §6 的布局/原子改名/读时校验实现；配额取 `PAYROLL_SKILL_BLOB_MAX_BYTES`（默认 512KB，与现网单文件上限同值），根目录参数化、Spring 键 `payroll.skill.blob.root` 随 P0-6 接线（**已接完**，§11.1 第 3 条）。签名与失败语义的两处实现差异记在 §6 首段。全量 `mvn -o test` 1303 用例 0 失败 4 跳过（新增 9 个）。
- P0-3 `JdbcSkillStore`（管理面写端口实现：目录/正文/版本/资源的 SQL 与事务）——**已完成（2026-11-23，未提交）**：类名按仓库既有的 `JdbcAgentDefinitionRepository` 风格取 `JdbcSkillStore`（草案叫 `DbSkillStore`，P0-4 的读路径实现才叫 `DbSkillRepository`，避免两个名字只差一个词）。`SkillStore` 全部写方法 + 单查询目录 + 版本追加 + 三个 `resource_*` 同事务重算 + `revision` 乐观并发 + 本轮新 blob 回滚清理，14 个用例见 §18 T-04，与草案的偏差见 §8 末「P0-3 落地情况」。全量 `mvn -o test` 1317 用例（新增 14）。
- P0-4 `DbSkillRepository implements AgentSkillRepository, PayrollSkillCatalog`（含缓存、指标）——**已完成（2026-11-23，未提交）**：目录单查询 + TTL 快照、正文按内容身份 `(version_id, resource_hash)` 命中的 256 条 / 8M 字符 LRU、资源随正文整批装配、`SkillRuntimeReader` 只读端口、断库沿用上次成功快照与冷启动硬失败分治。六处偏差与测试清单见 §7 末「P0-4 落地情况」，被更正的两条草案原文见 §7.1（资源层 LRU 不引入）与 §7.2（装配不按 `isReadable` 过滤）。顺带删掉 `NacosSkillRegistry.isValidId()` 转发方法，`NacosSkillRepository.java:248` 与 `NacosSkillRegistry.java:586` 两处调用点直接指 `SkillIds.isValid`（§8 末预留的那件事）。全量 `mvn -o test` 1342 用例 0 失败 4 跳过（新增 25）。**当时仍未做**：db 源没接进 Spring 上下文（`payroll.skills.source` 开关属 P0-6），本批只保证读路径本身可用。**P0-6 已接完**（§11.1 第 1 条）。
- P0-4a **已完成（2026-11-22，未提交）**：`SkillManifest` 增 `resourceHash` + 快路径指纹纳入（§7.3）+ 管理写触发运行期失效（§2.2-7，把 `SkillRepositories.resetShared()` 接上或改为自刷新）。这两项与存储无关，修完 Nacos 源立刻受益，也降低切库当天的变量数量。实现偏差见 §7.3/§10：`hasResources` 是**替换**而非新增；失效走新增的 `PayrollSkillCatalog.invalidate()`（`resetShared()` 保留原语义未动）；跨实例部分仍属 P0-7。
- P0-5 `SkillStore` 端口抽取，controller/import/health 去具体类依赖——**纯重构，行为零变化，先单独合**。**已完成（2026-11-22，未提交）**：端口为现网语义子集，未做 `NacosSkillStore` 适配类与 `expectedRevision`/`versions`/`rollback` 签名，偏差见 §8 末
- P0-6 `payroll.skills.source` 装配 + `SkillNacosImporter.importAllIfEmpty()` + `SkillMigrationCheck`。**已完成（2026-11-23，未提交）**：单源开关接成 `SkillsSource` bean，db 源四个 bean（读仓库 / 组合仓库 / 轮询器 / 导入 runner）全部 `@ConditionalOnProperty`，Nacos 源一个不加——默认形态行为零变化。`payroll.skill.*` 的 Spring 键接线、`/health` 组件键改名 `skills`、一处装配陷阱（`SkillRepositories.shared()` 会自建连接池）见 §11.1；导入器五条规则三条照做、两条换形状、另补三条见 §11.2；对账门定成「集成测试 + `--skill-migration-check` 命令行」而非管理台接口，输出形状从四个数组扩到 14 个字段，见 §11.3。**本批顺带欠的那件事也做完了**：`SkillInvalidationPoller` 已接成 bean（`poll-ms` 走 `@Value`、`destroyMethod="close"` 交给容器、只挂 db 源），跨实例轮询第一次真在进程里跑；§10 表格与「P0-7 落地情况」第 7 条同步更正。测试：`SkillNacosImporterTest` 7 用例（§18 T-05）+ `SkillMigrationCheckTest` 13 用例（§18 T-16），后者第一次跑就抓到一个生产缺陷——目录整表读不出时会把全表 Nacos 技能级联报成 `missingInDb`，改的是 `SkillMigrationCheck`（加 `Buckets.catalogRead` 与提前返回），不是放宽断言。全量 `mvn -o test` **1374 用例 0 失败 4 跳过**（新增 22：导入器 7 + 对账门 13 + `/health` 的 `skills` 键跨源同名那条 1 + §11.1 第 7 条那条视图顺序 1；跳过仍是既有那 4 条：1 条未配置的 MySQL 腿 + 3 条沙箱/E2E 条件跳过）。**仍未做**：db 源没在任何真实实例上跑过一次导入与对账（只有 JUnit 证据），A-01…A-04 与 V-01/V-12 的实测归 P0-8。
- P0-7 聚合 revision 轮询失效 + 同进程主动失效——**已完成（2026-11-23，未提交）**：`SkillInvalidationPoller`（15s 命名 daemon 线程，比对 `fingerprint()` 的聚合 revision）+ `DbSkillRepository.refresh()`（只丢目录快照）+ `skill.invalidations.count{trigger}` / `skill.invalidation.lag` 两条指标。同进程主动失效那一半**早已随 P0-3/P0-4a 落地**（写事务提交后调 `SkillRepositories.invalidateShared()`），本批只给它补上 `trigger=write` 埋点，让「谁刷的缓存」在指标里可分。七处偏差、滞后口径与仍未验证的双进程测量见 §10 末「P0-7 落地情况」，测试见 §18 T-15（8 个用例）。全量 `mvn -o test` **1352 用例 0 失败 4 跳过**（新增 10：轮询器 8 + `refresh()` 与两条指标各 1；跳过仍是既有那 4 条：1 条未配置的 MySQL 腿 + 3 条沙箱/E2E 条件跳过）。**当时仍未做**：Spring 装配与 `payroll.skill.invalidation.poll-ms` 的 `@Value` 接线（随 P0-6）。**已随 P0-6 完成**，见 §11.1 第 6 条与本节上一条。
- P0-8a 指标前置 + 目录行数埋点——**已完成（2026-11-23，未提交）**：`SkillMetrics` 两个 Timer builder 各加一行 `.publishPercentileHistogram()`（`:166`、`:178`），A-01/V-03/V-07 的 P95 从此有数据来源；`skill.catalog.query.rows` Gauge 接上（`SkillMetrics.catalogQueried()` + `JdbcSkillStore.catalog()` 的 `finally` 里传行数，失败传负数即「不报」，保留上一次好值）。形态选择、弱引用陷阱与那条假绿灯记在 §10 末、§15 埋点清单和 §18 T-17。测试 +3（`SkillMetricsTest` 6→8、`JdbcSkillStoreTest` 14→15），全量 `mvn -o test` **1377 用例 0 失败 4 跳过**（跳过仍是既有那 4 条）。**注意这条只证明「桶会吐出来」，不证明门槛达标**：本机 db 源零技能行，直方图是空的。
- P0-8b 真机项——**已执行（2026-11-23，未提交），结果分「拿到数」「拿到判定但判不过」「拿不到」三类，逐条口径在 §17/§18 的行内**。全量 `mvn -o test` **1380 用例 0 失败 3 跳过**（+3 条 `SkillDbSourceWiringTest`；跳过由 4 降到 3 是本轮唯一一条被真跑掉的：`DdlSyntaxTest.sharedMigrationsApplyOnRealMysql` 配上 `PAYROLL_TEST_MYSQL_*` 后首次执行，于是它同时充当 MySQL 8 上的建表与幂等重放证据，见 §18 T-01）。
  - **拿到数**：A-01（db 侧 18 技能 54 次构建、均值 1.06ms、P50≈1.0ms、P95≤2.1ms；nacos 侧只有均值 14.09ms，那台是 P0-8a 之前的构建，没有桶行）、A-02、A-04、V-07 的 1 次真机样本 10.139s、§18 T-12 的 10 次采样补法。
  - **拿到判定但判不过**：**V-01 退出码 1、硬差异 3 项**（`missingInDb：[ai-coding, user-talk]` + `bodyMismatch：[it-support]`）——三条都由数据引起而不是代码：前两条是 Nacos 里那两份正文 `strip()` 后只有 22 字，过不了 §11 的 200 字门槛（`SkillContentRules.java:16`），导入器按规则拒收；第三条是本批 T-12 测量时真往库里 PUT 的正文。**V-03 证据不足**（没有 200 技能、对照侧没有分位数）。**V-12 按草案口径不通过**，且缺口与内容无关：目录顺序那一维实测相等（顺带补上 §18 T-13 缺的那半边证据），内置角色技能全量注入的块遮掉源标签后逐字节一致，18 条那块差 2 行——目录行渲染正文字节数（db 写入时 `strip()` 掉尾部换行，每条差 1 字）+ 注入正文按字符数截断，差 1 字就把截断点整段推一行。**三条出路要拍一条**，推荐 (b)+(a)：目录行不再渲染 `（NNNB）`，判定口径同步改成「遮源标签、正文按 `strip()` 归一后逐字符一致，超预算截断点允许漂移」。本批未动代码，**已拍 (b)+(a)，实施与重测见本节末「V-12 修复」条**。
  - **拿不到**：**A-03** 与 **T-07 前半条**（`definitionSignature` 端到端「实例只建一次」计数）同一原因——db 源的 `AgentRuntimeApplication` 需要 `PAYROLL_AGENT_RUNTIME_TOKEN`，该令牌只存在于 IDEA 运行配置里，按红线不落文件、不进命令行；本批只做到「db 源进程能起来并完成 18 条技能的目录装配」。T-07 后半条建议判为不需要（理由见 §18 T-07）。
  - **本批抓到的两个新缺陷**：（1）**P0（已修）**：`dbSkillRepository` 与 `skillRepository` 同为 `AgentSkillRepository`，`AgentAdminController` 的 `ObjectProvider.getIfAvailable()` 歧义，db 源进程起不来；修法是组合仓库上的 `@Primary`（`PayrollCoreConfiguration.java:495`），并补 §18 T-18 那条容器装配测试。**七批都没撞见是因为 P0-1…P0-7 全在手工 `new` 对象，没有一次真容器**。（2）**P1（v0.10 已修）**：`SkillMigrationCheck` 的一个 blob 读不回会级联成误诊——用错的 `PAYROLL_SKILL_BLOB_ROOT` 重跑同一份数据，硬差异从 3 项涨到 22 项，其中 `problems` 19 条 = 1 条如实报出的 `读取技能资源失败：技能资源缺失：LICENSE` + **18 条「库内运行期数据缺失（版本行未落库或被删）」，而版本行明明在库里的，错的是 blob 读**。这跟 P0-6 修掉的 `missingInDb` 级联同形（那次改的是 `Buckets.catalogRead`），差别是这次要在**资源整批读不回**时短路掉全部文件包比对、只记一条 blob 侧问题，别让运维去查一张没问题的表。实施、输出形状与重测见本节末「blob 级联误诊修复」条。
  - **两条运维前置（都真踩过）**：`--skill-migration-check` 必须与写入实例用**同一个 `PAYROLL_SKILL_BLOB_ROOT`**，否则文件包这一路整轮比不成（v0.10 前是「上面那 22 项」，现在只剩一条 blob 侧问题 + `checkedFiles=false`）；对账/导入要指向真库时得先绕开 Flyway 校验和冲突（见下）。
  - **两条卡点**：（1）本机 `payroll` 主库上还有**未提交的方案C schema 统一**改过 V6/V13/V19 的内容，任何连真 `payroll` 库的 JVM 都会 `FlywayValidateException: Migration checksum mismatch for migration version 6/13/19`，因此本批全部真机证据取自一次性模式 `payroll_skill_e2e`（同一套迁移、同一台 MySQL 8.0）——**这条等方案C 提交后自动消失**，届时 V-01 该在真 `payroll` 库上重跑一次。（2）`ai-coding`/`user-talk` 那 22 字正文不补到达标（或删除），V-01 这道门永远是红的，切库当天卡的就是它。
  - 原挂在本批的两条 `SkillRepositories` 顺带项（`filterFor()` 缺 `_db`、`scoped()` 用 `Set.copyOf`）**已随 P0-6 改完**，各有一条断言锁住，见 §11.1 第 7 条。真机手法（一次性模式建库、blob 根、`--skill-migration-check` 的命令行形状、`PAYROLL_TEST_MYSQL_*` 门禁）同步写进 README 与交接文档。
- V-12 修复（v0.9，2026-11-23，未提交）——**已拍 (b)+(a) 并实施，重测按新口径通过**。上面那条「18 技能块差 2 行」的成因里唯一可动的变量是目录行渲染了正文字节数：`SkillManifest.catalogLine()`（`:57-66`）现只输出 `- <id>：<描述>`（+ 有版本时的 `（v<版本>）`），`contentLength` 字段与语义不变、管理台那一列照旧（`SkillAdminController.java:767,798`），去掉的只是进提示词那一路。判定口径同步改写进 §17 V-12 行（遮源标签 + 正文 `strip()` 归一后逐字符一致 + 截断点允许漂移）。真机重测同一套数据：目录段逐行一致 = true、归一后正文差异 1/18（`it-support`，仍是 T-12 那次 PUT，属数据）、注入段公共前缀 4111/4134 字符、其后只剩 2 行截断点漂移。测试：`SkillPromptBudgetTest` 9 → 10 用例（新增 `catalogLineOmitsContentLength`），全量 `mvn -o test` **1381 用例 0 失败 3 跳过**。**没做的事**：(c)（不再 `strip()`）明确不采纳；`SkillPromptBudget` 的截断仍按含首尾空白的字符数算——(a) 已把这一维判成允许漂移，改它只会让 `content_length` 与预算口径分叉，不值一批。切库当天剩余确定差异两条，都属设计内：正文首尾空白归一与 `resource_hash` 由空串变实值（§7.3 实例重建）。
- blob 级联误诊修复（v0.10，2026-11-23，未提交）——**上面 P0-8b（2）那条 P1 已修**，改的是 `SkillMigrationCheck` 的短路，不是放宽断言。`Buckets` 新增 `runtimeRead`（与 P0-6 那条 `catalogRead` 同形）：`runtimeData` 是整批一次取回，一个 blob 读不到就整批抛，此时**四桶（`fileMissing`/`fileExtra`/`fileHashMismatch`/`resourceHashMismatch`）保持为空**、只记一条 `读取技能资源失败，本轮未比对文件包（正文与执行开关仍已比对）：<原因>`，不依赖字节的 `bodyMismatch`/`executionMismatch` 照旧逐条比。逐条「库内运行期数据缺失（版本行未落库或被删）」从此只剩一种触发路径：整批读成功、单条版本行确实被并发删掉（`SkillRuntimeReader` 契约）。配套三处：`checkedFiles` 语义从「开关开没开」改成「这一轮有没有真的逐文件比过」（开关关闭与整批读不回都是 false）；`PayrollApplication` 那行 CLI 输出同步不再把原因单指成 `PAYROLL_SKILL_FILES_ENABLED`；`JdbcSkillStore.decode`/`contents` 的「技能资源缺失」文案带上技能 id（`<id>/<path>`，两条读路径同源），否则整批装配抛的是最先撞上的那条技能，只有路径分不清是单条脏数据还是 blob 根指错目录。测试：`SkillMigrationCheckTest` 13 → **15 用例**（`unreadableBlobsReportOnceNotPerSkill` + `missingRuntimeEntryStillReported`）。变异验证：临时摘掉 `runtimeRead` 短路，前者立刻红在 `expected: <1> but was: <5>`（1 条真因 + 4 条误诊），装回去即绿。全量 `mvn -o test` **1383 用例 0 失败 4 跳过**（本轮未挂 `PAYROLL_TEST_MYSQL_*`，`DdlSyntaxTest` 的 MySQL 腿又跳过了；挂上仍是 3 跳过，与 v0.9 的 1381/0/3 同一批用例）。§11.3 第 4/5 条与 §18 T-16 同步改写。**没做的事**：`--skill-migration-check` 里 blob 根与写入实例的一致性没法自证——门禁只能报「读不出」，不知道哪个根才是对的，那条运维前置（必须同 `PAYROLL_SKILL_BLOB_ROOT`）照旧写在 README。

**P1 管理面写路径（切 db 的前置）**
- P1-1 ~~`DbSkillStore.save/replaceFiles/deleteFile/delete` + 409~~ —— **提前随 P0-3 落完**（写端口、409、事务与 blob 清理都在 `JdbcSkillStore`）。P1 只剩两件：管理台回传 `expectedRevision` 走带 revision 的重载（§8 末），以及审计记录带上 `version_no`——**两件都已落（v0.11，2026-11-23，未提交）**。四个写端点接上可选 `?revision=` 并走带 token 的重载、令牌 `markRevision` 进审计列；`SkillStore` 端口新增四个 **默认重载**（委托到无 token 的原方法，`NacosSkillRegistry` 一行不改）。token 类型取 `String` 而不是草案 §8 签名里的 `long`：库里的 token 就是 `revision` 的十进制串，`Long.parseLong("")` 会在「前端没传」这条合法路径上炸，且 C3 的智能体定义那套契约本来就是字符串。审计/响应快照统一补 `revision` + `versionNo`（键名与写后重读的代价、并发偏差见 §13-6）。一处接口事实供 P1-4 参考：**文件包写入也递增行 revision**，所以 `PUT /{id}/files` 与 `DELETE /{id}/files/{*path}` 的响应体回吐写后的新 token。测试见 §18 T-08（控制器 21 → 26、存储 15 → 16、四处变异验证）。**没做的事**：ETag/条件请求与 `versions`/`rollback` 新端点属 P1-2（版本行还没有可读接口）；前端真正回传 token 属 P1-4（本批只把后端契约与响应字段备好，页面仍走「不传 = 不检查」）；`SkillImportService` 走端口后导入路径的 token 语义属 P1-3（现在 `importProject` 仍按「已有行的执行开关」语义写，没有 revision 参数）
- P1-2 版本列表 / 回滚接口 + `change_note` 字段 —— **已落（v0.12，2026-11-23，未提交），§18 T-08 欠的「新端点 + ETag」随本批一起交付**。端口上补 `versions(String)` 与 `rollback(String,int,String)` 两个**默认方法**（列表退化为空、回滚退化为显式失败，`NacosSkillRegistry` 零改动）；`JdbcSkillStore` 侧时间线单查询不读正文、回滚严格按 §9.2 追加版本行 + 复制 `skill_resource` 清单 + 移指针且不改历史，另加一步草案没有的 blob fail-closed；`change_note` 的列 V22 早已建好，**本批不需要迁移**。控制器三个端点（`GET /{id}/versions`、`POST /{id}/versions/{n}/rollback?revision=`、`GET /{id}/files/{*path}` 带 `ETag`/304）全部 `skill:manage`。形状偏差、五条说明列规则与 ETag 现算的理由分别回写在 §8 末「P1-2 落地情况」、§9.2 末「P1-2 落地补充」、§9.3 末「P1-2 已落」。测试：`SkillAdminControllerTest` 26 → **30 用例**、`JdbcSkillStoreTest` 16 → **21 用例**，十一条变异验证见 §18 T-08b。
  **没做的事**：（1）清空一句变更说明没有接口（空串按「不改」处理，见 §9.2 补充第 2 条）；（2）`created_by` 恒空串——store 不收操作者，接线随 P1-3，测试把这条显式断成欠账（**已随 P1-3 收口**，见下条与 §18 T-08c）；（3）版本裁剪与孤儿 blob 回收属 §14/P2-1，本批只保证「回滚需要的字节还在」，不保证「旧版本一定还在」（T-06 未动）；（4）前端时间线抽屉、回滚确认与 304 复用属 P1-4；（5）**真机没跑过一次回滚**——本批证据全是 JUnit，db 源进程里的 rollback/versions 端点还没在 8123 那台实例上按过一次，随 P1-5 的 V-01…V-06 一起做。
- P1-3 `SkillImportService`/`SkillProjectParser` 走 `SkillStore`（Git/ZIP 在 db 源可用）—— **已落（v0.13，2026-11-24，未提交）**。这条的措辞要先更正一处：Git/ZIP **从 P0-5 起就已经走端口**（`SkillImportService` 依赖 `SkillStore`，不是 `NacosSkillRegistry`），本批真正补的是**端口上没有的两样**——行级令牌与操作者，于是「db 源可用」从「能写进去」变成「写得对且查得回来」。交付四件：(1) 端口新增 `create(5)`/`update(7)`/`replaceFiles(4)`/`deleteFile(4)`/`rollback(4)`/`importProject(4)` 六个默认重载（§8 末「P1-3 落地情况」第 1 条，`NacosSkillRegistry` 零改动）；(2) 溯源列真正落库——`skill_definition.created_by/updated_by` 与 `skill_version.created_by`（V22 早建好、一直空着的三列），空白操作者统一落 NULL 而不是假造 `"admin"`；(3) Git/ZIP 两个导入端点接上 `?revision=` 并透传操作者，dryRun 分支既不标令牌也不记审计；(4) 回滚的新版本行归**按下回滚的人**，不是原作者（§9.2 补充第 3 条）。测试：`JdbcSkillStoreTest` 21 → **24 用例**、`SkillAdminControllerTest` 30 → **33 用例**、`SkillImportServiceTest` 12 → **13 用例**，全量 `mvn -o test` **1398 → 1405 用例 0 失败 4 跳过**（§17 V-08），`mvn -o -q -DskipTests package` 通过；十一条变异验证见 §18 T-08c。顺带清掉三处陈旧口径：`SkillVersion.createdBy` 的 javadoc（原写「预留列，当前恒为空串」）、`versionsTimelineFollowsPointer` 的断言消息（原把 P1-3 当未来时）、§13-6 里「新动作 `skill.import.db` 待 P1-3」那条（那个名字不会出现，见 §13-6 更正）。**没做的事**：(1) **导入的 `operator` 只有管理台有**——CLI 侧 `SkillNacosImporter` 传空串，`created_by` 落 NULL，这是有意的；(2) 没有一个「按操作者查技能」的接口，归属只能进详情/时间线看，跨技能的审计查询仍走审计页（§13-6）；(3) `delete()` 没有 operator 变体——定义行整行消失，`updated_by` 无处可留，审计已经记了 actor；(4) 前端把 `created_by`/`updated_by` 显示出来属 P1-4；(5) 真机仍没点过一次带 `revision` 的导入（本批全是 JUnit 证据，随 P1-5）。
- P1-4 前端技能页：版本时间线抽屉、回滚确认、409 冲突提示复用智能体页组件
- P1-5 T-04、T-08、V-01…V-06、双写窗口演练

**P2 规模化、GC 与安全**
- P2-1 `SkillRetentionService`（裁剪 + 孤儿 GC + 巡检）+ 多实例锁
- P2-2 二进制 `assets/`（白名单 + magic 嗅探）与配额提升，**与 P2-3 同批**
- P2-3 沙箱流式投送（`tar.gz` multipart，替换内联 JSON）+ `SkillInitProtocol` 版本协商
- P2-4 Redis 广播失效（可选，D5）
- P2-5 `skill:read`/`skill:manage` 读写分权（若评审同意纳入）
- P2-6 T-06、T-09、V-07…V-09、N-1000 技能压测目录构建

**P3 对象存储与 Nacos 退役**
- P3-1 `S3SkillBlobStore`（MinIO/AWS/OSS 通用客户端）+ 介质迁移工具（本地 → S3，内容寻址可直接 copy）
- P3-2 管理台 blob 介质只读展示（`storage_location`）
- P3-3 移除 Nacos 发布路径、`SkillFilePackage` 写侧、`NacosSkillRepository`（保留一个发布周期的兼容读，再删）
- P3-4 `V10__model_credential.sql` 风格的 D2 加密装饰器（若 D8 选加密）
- P3-5 V-10 恢复演练 + runbook 定稿

依赖顺序：P0-5（纯重构）与 P0-4a（缺陷修复）可立即各自单独成 PR 并全量回归；P1 依赖 P0-3/P0-4；P2-2 与 P2-3 必须同批；P3 依赖 P0/P1 全量稳定运行。

## 20. 风险清单

| 编号 | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| K1 | 切库瞬间 `source` 进入 `skillFingerprint`，所有智能体实例缓存重建 | 一次集中抖动（模型实例不受影响，但实例构造有成本） | 灰度期低峰切换；`AgentCache` 是按需重建非清风暴；V-06 实测 |
| K2 | 切换窗口内在 db 源写的技能，回滚到 nacos 后丢失 | 内容丢失 | 双写窗口演练 + runbook 明确「回滚前必须反向导出」；反向导出器列入 P1-5 交付物 |
| K3 | blob 目录与库不同序恢复 → sha256 大面积校验失败 | 技能资源全部不可用 | 同快照序备份（§11.4）+ 巡检指标 + V-10 |
| K4 | 提高配额与二进制先于流式投送上线 → init 30s 超时 | 技能执行失败 | 强制同批（§12.1）+ `PAYROLL_SKILL_ASSETS_BINARY` 默认关 |
| K5 | `data/` 目录成为新的「无保留策略」存储（重演 KB/附件问题） | 磁盘无界增长 | GC 进 P0/P2 必做，V-10 前设 `skill.blob.orphan.gauge` 告警 |
| K6 | Nacos 服务端体积上限导致存量 envelope 已被截断（历史数据本就损坏） | 导入时才发现 | §11.3 对账提前暴露 `orphanEnvelope`/`fileMissing` |
| K7 | 200 技能时聚合 revision 全表扫描代价 | 轮询放大 DB | 15s 间隔 + 单条覆盖索引查询；>2000 技能换廉价聚合（§9.1） |
| K8 | 一次改动横跨存储/管理接口/前端/沙箱，PR 过大不可评审 | 交付风险 | P0-5 纯重构先合；P0/P1/P2/P3 各自独立可回滚 |
| K9 | 与未提交批次冲突（提示词外置、方案 C schema 统一、setup 回退规则均未提交） | 合并冲突 | 开工前先落那三批；Flyway 号以 `sort -V` 复核（当前 V21 → 本次 V22） |
| K10 | 二进制资源扩大提示注入/供应链面（`assets/` 里的文件可被 `skill_read` 原样注入上下文） | 注入 | `skill_read` 只返回文本类型；二进制仅走沙箱物化，不落 LLM 输入 |

## 21. 需同步更新的文档

`docs/skill-resources-and-sandbox-design.md`、`docs/skill-project-package-design.md`、`docs/model-and-skill-scale-prd.md`（D7 收敛为「已定：MySQL 为真源」）、`docs/nacos-skills/README.md` + 12 份技能正文的发布说明、`docs/agent-management.md`、`docs/agent-runtime-cache-spec.md`、`docs/multi-node-deploy.md`、`docs/deployment-matrix.md`、`docs/mysql-redis-deploy.md`、`docs/restore-runbook.md`（blob 同序恢复）、`README.md`、`AGENTS.md`（新增 Flyway V22 与 blob 目录说明）。

## 22. 决策清单

**2026-11-22 已确认**：用户授权实施，D1–D11 全部按「推荐」列取值生效（D1a / D2a / D3a / D4a / D5a→P2 加 b / D6a / D7a / D8a / D9a / D10a / D11b）。两项现场核实项（Nacos `maxConfigSize`、MySQL `max_allowed_packet`）在 P2-2/P2-3 开工前补测，不阻塞 P0。

| 编号 | 决策 | 候选 | 推荐 | 放弃代价 |
| --- | --- | --- | --- | --- |
| **D1** | 正文与文件包分别放哪 | (a) 正文 MySQL + 文件包对象存储；(b) 全部 MySQL LONGBLOB；(c) 全部对象存储，MySQL 只存清单 | **(a)** | (b) 业务库涨到 GB 级、备份/主从压力；(c) 目录构建退化为 N 次 blob 读，且失去同事务 |
| **D2** | 对象存储介质 | (a) 本地磁盘适配器 + 端口，S3 后置；(b) 现在就上 MinIO；(c) 现在引 AWS/OSS SDK | **(a)** | (b) 多一套部署与运维依赖，P0 交付周期变长；(c) 引 SDK 但无真实多节点需求 |
| **D3** | 版本化形态 | (a) 不可变 `skill_version` + `current_version_id`；(b) 单行覆盖 + 变更日志表；(c) 不版本化（只靠 DB 备份） | **(a)** | (b) 回滚要重放日志，资源清单难对齐；(c) 直接失去 §9.3 回滚能力，审计只能回答「谁改过」不能回答「改成什么」 |
| **D4** | 切换语义 | (a) 单源开关 + 空表幂等导入（同 M5.1）；(b) 双写 + 对账后切；(c) 直接切断不留回退 | **(a)** | (b) 双写一致性代码长期留存、两个真源；(c) 违反 R2 |
| **D5** | 跨节点失效 | (a) 聚合 revision 轮询 15s；(b) 轮询 + Redis 广播；(c) 只靠 60s 目录 TTL | **(a) 起步，P2 加 (b)** | (c) 现网已有「保存后不生效」的观测缺口，等于不解决 §2.2-7 |
| **D6** | 文件包粒度 | (a) per-file blob + `skill_resource` 清单；(b) 整包一个 blob | **(a)** | (b) 读单文件仍需取整包，`fileContent` 与注入代价不变，二进制/增量更新无收益 |
| **D7** | 沙箱投送 | (a) P0/P1 保持内联 JSON，P2 改 `tar.gz` 流式；(b) 直接改流式；(c) 沙箱按 sha 逐文件回源拉取 | **(a)** | (b) P0 就要动 `SkillInitProtocol`，风险面叠加；(c) 沙箱需网络回源与凭据下发，破坏当前「禁网 + 只读」边界 |
| **D8** | 静态加密 | (a) 默认不加密（与 KB 附件、聊天附件一致）；(b) 复用 `ModelCredentialCipher` 加密正文与 blob | **(a)** | (b) 多一个必须双进程注入的 `PAYROLL_SECRET_KEY` 前提（模型凭证已有），且丢主密钥 = 技能全不可读（fail-closed） |
| **D9** | 技能 `data/` 大数据集（poetry 291MB/1909 文件类） | (a) 不纳入，继续 setup 生成；(b) 纳入对象存储 + 挂载 | **(a)** | (b) 需要配额、去重、冷启动下载时间与沙箱 tmpfs 上限重算，属另一份规格 |
| **D10** | 内置技能（`RoleSkills`）地位 | (a) 导入为 `builtin=TRUE` 行 + 保留 `BuiltInSkillRepository` 作为 composite 兜底；(b) 完全落库，删除代码兜底；(c) 不落库，仅代码内置 | **(a)** | (b) DB 故障时智能体无技能可用（裸奔）；(c) 两个真源并存，目录构建仍需合并两侧 |
| **D11** | 是否把 `resource_hash` 纳入智能体指纹（§7.3 现存缺陷修复） | (a) 纳入，与迁移同批；(b) 纳入，但作为独立 PR 先上（P0-4a）；(c) 不纳入，维持现状 | **(b)** | (c) 「改了参考文件模型还读旧文件」是**已发生的线上缺陷**（§2.2-9），留着它会让切库后的任何行为差异都多一个不可解释变量；(a) 能做但把两个变量捆在同一次发布里，出问题难归因 |

---

## 附录 A：与 M5.1（智能体定义落库）的逐项对照

| 维度 | M5.1 已实现 | 本规格 |
| --- | --- | --- |
| 表 | `agent_definition(id, payload_json, revision, updated_by, updated_at)` | `skill_definition` + 版本表 + 资源表 + blob 表（技能有二进制，故多两张） |
| 开关 | `payroll.definitions.source=nacos|db` | `payroll.skills.source=nacos|db` |
| 导入 | `@ConditionalOnProperty(...import-from-nacos=true)` + `importFromNacosIfEmpty()` | 同形态 `importAllIfEmpty()` |
| 并发 | 行级 revision + `SELECT ... FOR UPDATE` 聚合 + 409 | 同（`JdbcAgentDefinitionRepository.java:206-217,274-290` 直接照搬） |
| 遗留 | `activeAgent` 与全局设置在 db 模式下仍读 `NacosRuntimeSettings` | 本规格不动运行时设置面（N1/N5），但需在 §20 之外单列说明：**技能切库不等于配置面全面切库**，`NacosRuntimeSettings` 仍是设置真源 |
