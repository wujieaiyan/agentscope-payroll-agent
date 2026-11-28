---
name: payroll-db-migration
description: 新增或修改数据库表、列、索引、约束时使用。只维护一份 Flyway 迁移（db/migration/common），H2 MODE=MySQL 与 MySQL 共用；版本号按合并顺序取，DDL 必须可重放，并在 DdlSyntaxTest 与 H2/MySQL 两侧验证。
---

# 数据库迁移

## 适用场景

- 加表 / 加列 / 加索引 / 加唯一约束；
- 给已有表补默认值或回填历史数据；
- 排查「新库能起、老库起不来」「H2 通过、MySQL 失败」。

## 铁律

1. **只有一份迁移目录**：`payroll-common/src/main/resources/db/migration/common/V{n}__描述.sql`。
   禁止再写 H2 内联 DDL，也禁止在 Java 里 `CREATE TABLE`。
2. **只增不改**：已合并的迁移文件一个字都不能改（Flyway 校验和会失败）。改结构 = 新增一个版本。
3. **版本号按合并顺序取**：先 `ls db/migration/common | sort -V | tail -1` 看当前最大值，再 +1。
   不要按需求文档的工作流编号排序——低版本后到会让 Flyway 乱序失败。
4. **DDL 在 MySQL 上非事务**：脚本中途失败会留下半套结构，必须能安全重跑。

## 步骤

### 1. 写迁移

```sql
-- V{n}__add_xxx.sql
-- 说明：为什么加（不是做了什么）
CREATE TABLE IF NOT EXISTS xxx (...);
-- 已有表加索引：先查存在性，避免重跑失败
```

- 双方言可执行：避免 MySQL 专有语法（`ENGINE=`、`COMMENT=` 之外慎用）；JSON 列在 H2 用 `CLOB`/`TEXT` 等价类型。
- 索引与唯一键命名带表名前缀，便于排障。
- 大表加索引用可重入写法（`information_schema.statistics` 预检），不要 26 条裸 `CREATE INDEX` 一把梭。

### 2. 接存储层

- `db/` 下新增或扩展 Store，SQL 全部 `PreparedStatement` 占位符；
- 连接只从 `PayrollDatabase.connection()` 取，不自己建数据源（会绕过指标与方言适配）；
- 多表写入注意：**本仓库没有装配事务管理器**（`PayrollDatabaseDataSource` 已存在但未接线），需要原子性时先读 `docs/architecture-remediation-2026-11-27.md` W3。

### 3. 数据回填

- 单独一个可重跑的作业，带 `dryRun` 计数；
- 回填前留快照表或导出文件；
- 回填幂等：`INSERT ... ON DUPLICATE KEY` / `MERGE` 或用主键冲突跳过，不要 `TRUNCATE` 后重灌。

### 4. 测试

```bash
mvn -o -pl payroll-tests -am test -Dtest=DdlSyntaxTest -Dsurefire.failIfNoSpecifiedTests=false
```

- 无 `PAYROLL_TEST_MYSQL_*` 时该用例会**跳过**——跳过不等于通过，改了 DDL 要本地配一次真实 MySQL 再跑。
- 补一条 H2 侧的行为测试（写入 → 读回 → 字段类型/精度正确）。

## 检查表

- [ ] 新文件是 `V{n+1}`，没有改动任何历史迁移。
- [ ] 索引/约束可重跑（有存在性预检或 `IF NOT EXISTS`）。
- [ ] 列类型在 H2 与 MySQL 都能建，金额列是 `DECIMAL(18,2)` 级别而不是 `DOUBLE`。
- [ ] 新表有主键、`created_at`，必要的唯一键。
- [ ] `DdlSyntaxTest` 在真实 MySQL 上跑过一次。
- [ ] `docs/architecture.md` 或对应规格的表清单已回写。

## 反例

- 在 `PayrollDatabase` 里加一段 `CREATE TABLE IF NOT EXISTS` 兜底。
- 为了让 H2 跑通，在迁移里写 H2 专有语法（生产是 MySQL）。
- 给已有大表加索引时直接 `CREATE INDEX`，失败后靠 `flyway repair` 手抠。
