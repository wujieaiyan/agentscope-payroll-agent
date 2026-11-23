# 工作偏好 P0-0 基线度量

- 规格来源：`docs/user-profile-memory-spec.md` §2.2 / §13（P0-0）
- 目的：在投入画像功能前，先用现有对话数据量出「澄清成本」基线，据此决定是否继续。
- 脚本与词表：
  - 词表：[`scripts/profile-baseline-keywords.txt`](../scripts/profile-baseline-keywords.txt)
  - MySQL：[`scripts/profile-baseline.sql`](../scripts/profile-baseline.sql)
  - H2：[`scripts/profile-baseline-h2.sql`](../scripts/profile-baseline-h2.sql)
- 全部语句只读（仅 SELECT），不改表结构、不写数据。

## 1. 指标口径

| 指标 | 取数方式 | 判定用途 |
| --- | --- | --- |
| 每会话平均用户轮数 | `chat_messages` 按 `session_id` 统计 `role='USER'` | 会话是否普遍多轮，多轮才有澄清成本 |
| 首问未含范围词比例 | 每会话 `message_id` 最小的用户消息未命中部门/城市/区域/周期词表 | 核心基线：首问缺范围导致追问 |
| 需要补充口径的会话比例 | 会话内 ≥2 轮用户消息且出现「不是 / 按…口径 / 我说的是 / 改成」等修正词 | 修正频率 |
| 单轮成功率 | `chat_turns.status='SUCCEEDED'` 占比 | 确认样本质量 |
| 团队路径占比 | `agent_team_runs.status='SUCCESS'` ÷ `chat_turns` 成功数 | 决定是否给团队路径做注入（D11） |

词表与库内受控值域对齐：部门取 `departments` 种子，城市取 `city_policies` 种子，周期与
`UserProfileTaxonomy` 的 `default_period` 固定枚举一致；区域补充华东/华南/华北口语说法。
改词表时需同步两个 SQL 中内联的正则。

## 2. 运行方式

### MySQL（生产/正式取数）

```bash
mysql -u <user> -p <db> < scripts/profile-baseline.sql
```

### H2 文件库（本机默认 `./data/payroll`）

H2 文件库为单写者，运行前先停掉 Web / runtime；或复制 `data/payroll.mv.db` 到临时目录对副本执行：

```bash
java -cp <h2.jar> org.h2.tools.RunScript \
  -url "jdbc:h2:file:./data/payroll;ACCESS_MODE_DATA=r" \
  -user sa -password "" \
  -script scripts/profile-baseline-h2.sql -showResults
```

## 3. 本机样本结果（2026-11-20）

数据源：`data/payroll.mv.db` 副本（数据截至 2026-11-14，仅供脚本验证）。

| 指标 | 结果 | 样本量 |
| --- | --- | --- |
| 每会话平均用户轮数 | 1.50 | 2 个会话 |
| 首问未含范围词比例 | 2 / 2 = 100% | 2 个会话 |
| 需要补充口径的会话比例 | 0 / 2 = 0% | 2 个会话 |
| 单轮成功率 | 无法计算（该文件库为旧版本，无 `chat_turns` 表） | — |
| 团队路径占比 | 无法计算（同上） | — |

结论：本机文件库停留在旧 schema（无 `chat_turns`），且仅 2 个会话，**不足以形成结论**。
正式基线需在 runtime 实际使用的库（MySQL，或已迁移到 V17 的 H2 库）上执行。

脚本本身已用同 schema 的内存库回放验证：五项指标均能正确输出
（平均轮数、首问漏词、修正会话、成功率、团队占比），且对 CLOB `content` 的
`REGEXP` 匹配在 H2 2.3.232 下可用。

## 4. 正式取数记录（待填）

| 指标 | 值 | 样本量 | 取数时间 | 备注 |
| --- | --- | --- | --- | --- |
| 每会话平均用户轮数 | | | | |
| 首问未含范围词比例 | | | | |
| 需要补充口径的会话比例 | | | | |
| 单轮成功率 | | | | |
| 团队路径占比 | | | | |

## 5. 是否继续

按规格 §2.3：

- 主证据是历史 50 题离线回放（开/关画像各跑一次），线上占比只作护栏。
- 目标：回放评分提升 ≥10pp；同时 用户删除率 <10%、抽取解析失败率 <5%、注入冲突率 <5%。
- 任一不达标，回退默认关闭并复盘，不继续投入 P1。

本文件在正式取数后补充第 4 节并给出「继续 / 收敛 / 停止」结论。
