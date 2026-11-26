# 技能管理台体验重构规格（新增 / 导入 / 运营）

| 项 | 内容 |
| --- | --- |
| 版本 | v0.1（评审稿） |
| 日期 | 2026-11-25 |
| 状态 | 待拍板（D1–D9 未确认，P0 可直接排期） |
| 范围 | 管理台「平台能力 → 技能」全部页面（`frontend/src/views/admin/SkillsView.vue`）及其后端契约 |
| 关联 | `docs/skill-project-package-design.md`、`docs/skill-sandbox-data-flow.md`、`docs/model-and-skill-scale-prd.md`、`docs/frontend-ia.md` |
| 读者 | 产品、设计、前端、后端、测试 |

> 本文回答一个问题：**「新增技能」这个动作，怎样才能让用户少踩坑、快看到结果、出错能救回来。**
> 结论：现在的页面把技能当成「一段 Markdown 文本」来管，而用户实际交付的是「一个能被智能体调用、带文件、带数据、带版本、要绑定的运行单元」。页面的信息架构、写路径和反馈都要按后者重排。

---

## 0. 结论先行

1. **最痛的不是缺功能，是「能用的功能被静默关掉」。** 本机已切到 db 源，但前端 `canEditFiles` 仍要求 `source === 'nacos'`，导致**新增文件、编辑文件、删除文件、脚本执行开关全部置灰**，旁边还挂着一句指向 Nacos 的误导文案（`SkillsView.vue:80-87`、`SkillsView.vue:788-790`）。这是 P0 缺陷，不是体验问题。
2. **写路径没有并发保护，多人协作会静默丢改动。** 后端早就支持 `revision` + 409、`changeNote`、版本时间线、回滚（`SkillAdminController.java:169-204`、`:267-288`），但技能页一个都没接（`SkillsView.vue:383-407`、`:409-419`、`:509-520`）。同页的智能体/流程早已接好（`AgentsView.vue:158`），属于「同仓库内的一致性债」。
3. **单文件保存被做成了 N 次读 + 整包替换。** 编辑一个文件要把其余文件正文逐个 GET 回来再整体 PUT（`SkillsView.vue:474-507`），20 个文件就是 20 次往返，且与别人改另一个文件时必冲突。
4. **列表不能运营。** 没有「被几个智能体引用」「数据是否就绪」「脚本是否可执行」；类型筛选只有 Nacos/内置，没有 db；`fileCount` 在 Nacos 源恒为 0，badge 永远不出现（`SkillStore.java:33-37`）。
5. **「新建」缺少闭环。** 建完就关窗回列表，用户不知道下一步要上传文件、初始化数据、绑定智能体；正文质量门槛（≥200 字、含标题、含角色/流程词）只会在保存时以 400 出现，用户已经写了几百字才被退回。

**P0 三件事（0.5–1 人日，止血）**：修写入能力判定 → 保存/删除接 `revision` 与 409 → 修正来源筛选与误导文案、ZIP 导入统一走封装。
**P1 骨架（3–5 人日）**：全屏工作台 + 版本/回滚 + 单文件写接口 + 新建三步 + 导入预检树 + 列表运营列 + 数据页可诊断。
**P2 运营化**：只读权限、调用观测、批量健康、数据初始化取消、Markdown 渲染（需批准新依赖）。

---

## 1. 背景、目标与非目标

### 1.1 这个模块现在实际承担什么

技能是智能体的「可复用作业指导书」：正文（Markdown）+ 参考文件（`references/`）+ 可执行脚本（`scripts/`）+ 初始化数据（`setup` 生成、运行期只读挂载 `/data`）+ 绑定关系 + 版本历史。管理台是这个交付物的**唯一生产入口**（Git/ZIP 导入也在这里）。

但当前页面的组织方式是「列表 + 抽屉 + 四个页签」，信息层级按**存储概念**排（来源、Nacos、长度、正文），而不是按**用户任务**排（能不能用、谁在用、跑没跑通）。

### 1.2 使用者与诉求

| 角色 | 典型动作 | 真正关心 | 现在的障碍 |
| --- | --- | --- | --- |
| 业务配置者（HR/运营） | 新建或改一段政策说明，绑给智能体 | 写的东西是否合规、会不会被模型用错 | 质量门槛不前置；不知道绑定在哪 |
| 平台管理员 | 导入项目、开脚本、初始化数据 | 导入后是否可用、失败原因 | 导入预览只有数字；初始化失败只有一行 message |
| 研发/交付 | 调脚本、回滚、排障 | 谁改了什么、怎么退回 | 无版本入口；无 diff；无 last used |
| 审计/主管（只读） | 查看现状 | 哪些技能没人用、哪些坏了 | 没有只读权限，也没有健康视图 |

### 1.3 目标 / 非目标

| 目标（本期） | 非目标（明确不做） |
| --- | --- |
| 让「新建 → 可用」路径不断点，创建后有明确下一步 | 不做技能市场、跨租户、评级体系 |
| 让写操作可并发、可回滚、可解释 | 不做管理台直接执行脚本（必须走对话确认，见 S10） |
| 让列表能回答「谁在用、能不能用、坏在哪」 | 不改技能 ID 生成规则、不做展示名迁移（另立项） |
| 让失败可定位（导入、保存、初始化） | 不替换现有样式体系/引入组件库 |
| 保持 db / Nacos 双源一致体验 | 不做多语言 |

---

## 2. 用户旅程与断点

### 2.1 端到端七步

```
①导入/新建 ──▶ ②正文校验 ──▶ ③文件包 ──▶ ④试运行 ──▶ ⑤初始化数据 ──▶ ⑥绑定智能体 ──▶ ⑦观察/迭代/回滚
   现在：         现在：         现在：        现在：         现在：            现在：            现在：
 能建，但       只有保存时      db 源被       没有入口       能跑，但          只能去智能体页     无版本/无观测
 无引导         才报 400        置灰           （见 S10）     不可诊断          自己找绑定         无 diff
```

### 2.2 断点表（按用户意图）

| 步骤 | 用户意图 | 现状 | 证据 | 后果 |
| --- | --- | --- | --- | --- |
| ① 新建 | 「我想做一个能回答诗词问题的技能」 | 弹窗先要 ID，正文空白 16 行，无模板、无提示 | `SkillsView.vue:361-376`、`:801+` | 卡在命名与「写什么」 |
| ① 导入 | 「我有现成仓库」 | 预览只有 ID/说明/文件数/大小 | `SkillsView.vue:320-359` | 不知道会覆盖哪些文件 |
| ② 校验 | 「我写够了吗」 | 无字数、无结构提示，保存后才 400 | `JdbcSkillStore.java:1043-1044` | 白写一遍再退回 |
| ③ 文件 | 「加一份参考表」 | db 源下按钮禁用 | `SkillsView.vue:80-87` | 功能不可达 |
| ③ 文件 | 「只改一个文件」 | 串行 GET 全部 + 整包 PUT | `SkillsView.vue:474-507` | 慢；易覆盖他人 |
| ④ 试运行 | 「它真的会用吗」 | 无入口 | — | 只能去对话里碰运气 |
| ⑤ 数据 | 「下载失败在哪一步」 | 只有状态/大小/日志尾巴 | `SkillsView.vue:679-745` | 无法自助排障 |
| ⑥ 绑定 | 「谁在用它」 | 列表/详情都没有 | — | 删除时才发现被引用 |
| ⑦ 迭代 | 「上周那版更好」 | 后端有版本与回滚，前端未接 | `SkillAdminController.java:169-204` | 只能手抄恢复 |

---

## 3. 现状问题清单（F1–F13）

### 3.1 严重度总表

| # | 问题 | 严重度 | 修复成本 | 归属 |
| --- | --- | --- | --- | --- |
| F1 | db 源下文件编辑与执行开关被静默禁用，文案误导 | **P0** | 0.2 人日 | 前端 |
| F2 | 保存/删除/删文件不带 `revision`，无 409 分支 | **P0** | 0.5 人日 | 前端 |
| F3 | 单文件保存 = N 次读 + 整包替换 | P1 | 1 人日（前后端） | 全栈 |
| F4 | 列表缺健康/绑定信息；来源筛选缺 db | P1 | 1 人日 | 全栈 |
| F5 | 详情是抽屉，无深链，刷新/分享丢失 | P1 | 2 人日 | 前端 |
| F6 | 正文只有 textarea 与 `<pre>`，无预览/校验/草稿 | P1 | 1.5 人日 | 前端 |
| F7 | 数据页无配额、耗时、结构化失败、取消 | P1 | 1 人日 | 前后端 |
| F8 | 导入预览无文件树/覆盖差异；ZIP 走裸 fetch | P1 | 0.5 人日 | 前端 |
| F9 | 危险操作全靠 `window.confirm`，不可撤销 | P1 | 0.5 人日 | 前端 |
| F10 | 只有 `skill:manage`，无只读角色 | P2 | 0.5 人日 | 前后端 |
| F11 | 无调用观测（最近使用/次数/失败率） | P2 | 1.5 人日 | 后端 |
| F12 | 编辑正文是「抽屉上叠弹窗」，保存后上下文丢失 | P1 | 随 F5 解决 | 前端 |
| F13 | 列表筛选状态不进 URL，返回后丢失 | P2 | 0.3 人日 | 前端 |

### 3.2 逐条说明

**F1 · 写入能力判定写错了维度**
`canEditFiles` 用「来源是不是 Nacos」来判断「能不能写文件」，而正确的维度是「存储是否可写、且这条技能是否有可写的落库行」。db 源下 `source` 是 `"db"`（`JdbcSkillStore.java:45`），于是整个文件区与执行开关被禁用；未覆盖的内置技能 `source` 是 `"builtin"`（`JdbcSkillStore.java:1599-1617`），提示语却让用户「先保存到 Nacos」（`SkillsView.vue:788-790`），与 db 源事实不符。
影响：本机/生产 db 源下，**技能文件包与脚本执行在 UI 上完全不可运营**。

**F2 · 乐观锁只做了一半**
后端写接口全部接受 `revision`：更新（`SkillAdminController.java:267-288`）、删文件（`:231-249`）、回滚（`:185-204`）；端口层也有条件写默认方法（`SkillStore.java:50-57`、`:74-84`）。前端只读列表拿 `revision`，保存时一个都不传（`SkillsView.vue:387-397`、`:412`、`:512`），等于关掉了冲突检测。`changeNote` 同样未采集。
影响：两个管理员同时编辑，后保存者**静默覆盖**前者，且审计里看不出「覆盖了谁改的哪一版」。

**F3 · 单文件编辑被做成整包替换**
`saveFile()` 先把除目标外的每个文件逐个 GET 回正文，再 `PUT /files` 整包提交（`SkillsView.vue:484-496`）。这是为了迁就端口层只有 `replaceFiles`（`SkillStore.java:47-48`）。
影响：往返次数 = 文件数 + 1；并发时不同文件的修改互相覆盖；请求体随文件包线性增大。

**F4 · 列表缺运营字段**
`SkillSummary` 只有 `id/description/source/builtin/overridden/updatedAt/contentLength/fileCount/revision`（`SkillSummary.java:15-24`）。没有 `executionEnabled`、没有绑定数、没有数据就绪。`fileCount` 在 db 源可由 `resource_count` 得出（`JdbcSkillStore.java:1776-1801`），Nacos 源恒 0（`SkillStore.java:33-37`）——所以「文件数」badge 只在部分源出现（`SkillsView.vue:597`）。来源筛选只有 `nacos` / `builtin` 两项（`SkillsView.vue:547-551`），db 源下形同虚设。

**F5 · 抽屉没有地址**
详情是 `v-if="detail"` 的遮罩抽屉（`SkillsView.vue:634`），URL 不变。刷新、分享、从智能体页跳回来都做不到。全页 1138 行，单文件同时承载列表、详情、四个页签、三个弹窗。

**F6 · 写作体验**
正文只读态是 `<pre>`（`SkillsView.vue:676`），编辑态是 16 行 textarea（`SkillsView.vue:819`）；没有 Markdown 预览、字数统计、结构检查、草稿保存、离开拦截。前后端也没有 Markdown 渲染依赖（`frontend/package.json` 无 marked/dompurify）。

**F7 · 数据页只能「看」，不能「判断」**
现有信息：状态、setup 路径、大小、最后时间、日志尾（`SkillsView.vue:679-745`）。缺：配额上限与占用比、初始化耗时、失败分类（超时/网络/脚本非零/被 kill）、「可以关页面，稍后回来」的承诺。轮询 2.5s，关抽屉即停（`SkillsView.vue:184-187`、`:206-209`），重开不会自动续上。

**F8 · 导入**
`importPreview` 只渲染 ID/说明/文件数/总大小/setup/上限/警告（`SkillsView.vue:891-919`）。接口其实返回了 `files[]`（`SkillAdminController.java:327`、`:364`）。ZIP 分支用裸 `fetch`（`SkillsView.vue:293-309`），绕过了 `api()` 的 401 跳转与 `ApiError.status`，错误处理与 Git 分支不一致；`http.js` 已有 `uploadForm` 封装（`frontend/src/api/http.js:59-67`）。

**F9 · 危险操作**
删除技能、删除数据、重建数据、删除文件全部 `window.confirm`（`SkillsView.vue:234`、`:258`、`:410`、`:510`）。不可撤销、无输入确认、无「被引用」前置提示（删除绑定中的技能只能等后端 400）。

**F10–F13**
无 `skill:read`（`Permissions.java:34`，路由 `frontend/src/router/index.js:53`）；无调用观测；编辑正文先开抽屉再叠弹窗（`SkillsView.vue:378-381`），保存后 `detail.value = null` 直接关掉（`:400`）；筛选状态不进 URL（`keyword/source/sort/page` 均为组件内 `ref`）。

---

## 4. 产品与设计原则

| # | 原则 | 落到本模块的具体约束 |
| --- | --- | --- |
| 1 | **技能是运行单元，不是文档** | 工作台必须把「正文 / 文件 / 数据 / 版本 / 绑定 / 健康」放在同一上下文 |
| 2 | **禁用必须解释，且给出口** | 任何灰按钮都要有原因与下一步（如「内置技能需先保存正文以生成覆盖版本」） |
| 3 | **默认安全，失败可逆** | 写操作带 `revision`；版本可回滚；危险操作二次确认并说明影响面 |
| 4 | **校验前置，反馈就地** | 质量门槛在输入时提示，不等提交；错误定位到字段/行 |
| 5 | **渐进披露** | 列表轻；工作台按页签分载；脚本执行、配额、来源等「高级」默认折叠 |
| 6 | **同仓库一致** | 复用 `withRevision`、`ApiError.status`、现有表格/抽屉/弹窗样式，不另造交互 |

设计自检（5 秒测试）：进入列表，5 秒内应能回答——这是什么、有几个、哪些坏了、我下一步该点哪。当前只能回答「有哪些」。

---

## 5. 目标信息架构

### 5.1 列表页（`/platform/skills`）

- 顶部一句话说人话：「技能让智能体按固定流程和资料工作。」去掉「正文以 Markdown 存入 Nacos」这类存储描述（`SkillsView.vue:534`）。
- 概览条保留规模指标，但增加**可行动项**：`未绑定 N`、`数据异常 N`（点击即筛选）。
- 表格列：`ID / 说明 / 类型 / 版本 / 绑定 / 数据 / 脚本 / 更新 / 操作`。
  - `类型`：内置 / 自定义 / 已覆盖（合并现有三个 badge）。
  - `版本`：db 源显示 `v7`，Nacos 显示 `—`。
  - `绑定`：`2 个智能体`，点击进工作台「绑定」页签。
  - `数据`：`就绪 / 未初始化 / 失败 / 无需`（P1 用静态声明 + 详情懒加载，见 D4）。
  - `脚本`：`开 / 关`。
- 操作：主操作「打开」；次操作收进 `⋯`（复制 ID、删除）。
- 筛选器：关键词、类型（全部/内置/自定义/已覆盖）、数据状态、排序；**筛选写入 URL query**，返回可恢复。

### 5.2 新建：三条路径（本期重点）

```
┌ 新建技能 ─────────────────────────────────────────────────────────┐
│  你要从哪里开始？                                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                  │
│  │ 从空白创建  │  │ 从模板创建  │  │ 导入项目    │                  │
│  │ 写正文即可  │  │ 政策/流程/  │  │ Git 或 ZIP  │                  │
│  │            │  │ 写作/数据   │  │ 带文件与脚本 │                  │
│  └────────────┘  └────────────┘  └────────────┘                  │
│  内置技能可直接覆盖：在列表打开后保存正文即生成覆盖版本。            │
└───────────────────────────────────────────────────────────────────┘
```

创建表单改动（相对现状）：

| 字段 | 现状 | 目标 |
| --- | --- | --- |
| 技能 ID | 自由输入，无说明 | 输入 + 自动建议（由说明生成 slug）；固定说明「用于智能体引用，创建后不可修改」 |
| 说明 | 单行输入 | 200 字上限 + 实时计数（与 `SkillContentRules.MAX_DESCRIPTION_LENGTH` 对齐） |
| 正文 | 16 行 textarea | 模板选择（空白 / 政策说明 / 处理流程 / 写作规范 / 数据分析）+ 实时质量清单 |
| 质量清单 | 保存后 400 | 三项实时勾选：≥200 字、含 Markdown 标题、含角色/流程关键词 |
| 创建后 | 关窗回列表 | 直接进入工作台「概览」，顶部显示下一步清单 |

「下一步清单」（创建后的引导，完成即自动消失）：
`① 上传参考文件（可选） → ② 初始化数据（若声明 setup） → ③ 绑定智能体 → ④ 在对话中试用`

### 5.3 技能工作台（`/platform/skills/:id/:tab`）

```
技能 / poetry-notes                                          [编辑正文] [⋯]
诗词创作规范：解释平仄、押韵与意象                      自定义 · 来源 db · v7
──────────────────────────────────────────────────────────────────────────
[概览] [正文] [文件 3] [数据 ●] [版本 7] [绑定 2]
──────────────────────────────────────────────────────────────────────────
概览：健康四卡（数据 / 脚本 / 版本 / 绑定）+ 下一步清单 + 最近变更
正文：编辑 ⇄ 预览；字数与质量清单；保存时填「变更说明」；冲突条常驻顶部
文件：左侧树（references/ · scripts/），右侧编辑器；单文件保存与删除
数据：状态卡 + 配额条 + setup 路径 + 耗时 + 日志折叠 + 重建/删除
版本：时间线（v7 当前）+ 选中版本 diff + 回滚（需确认，回滚生成新版本）
绑定：引用该技能的智能体列表 + 「去智能体页」
```

交互细则：

- 顶部 chips 是**状态**不是装饰：`数据 ●` 的圆点颜色与数据状态一致；失败时 chip 文案直接给「查看原因」。
- 「编辑正文」是主操作，进入正文页签的编辑态；保存成功后就地更新版本号与 `revision`，不关闭页面。
- 未保存离开（切页签/返回列表/关标签）拦截：`保存` / `放弃` / `取消`。
- 冲突条（409）：`该技能已被 {人} 更新到 v8` + `查看差异` + `用我的版本覆盖`（显式二次确认）+ `放弃我的修改`。
- 删除技能前先查绑定：有绑定则禁用并给出「先去智能体页解绑」的跳转，而不是提交后吃 400。

### 5.4 深链与返回

- 新路由：`/platform/skills/:id/:tab`（`tab ∈ overview|body|files|data|versions|bindings`），放在 catch-all 之前；参考知识库详情深链写法（`frontend/src/router/index.js:57`）。
- 列表筛选进 query：`/platform/skills?keyword=&type=&data=&sort=&page=`。
- 工作台面包屑「技能」回列表时保留 query。
- 智能体页 → 技能：按 `skillIds` 反查用现有后端过滤 `GET /api/admin/agents?skill=<id>`（`AgentAdminController.java:152-155`），工作台「绑定」页签直接消费。

---

## 6. 方案详述

### S1 · 正确判定「能否写文件 / 能否开脚本」
**规则**：`data.writable && (detail.source !== 'builtin' || detail.overridden)`。
理由：db 源下新建/已覆盖技能 `source` 为 `"db"`，未覆盖内置为 `"builtin"`（`JdbcSkillStore.java:45`、`:1599-1617`）；Nacos 源同理。这个规则不依赖存储实现名，两个源都成立。
同时改写提示（`SkillsView.vue:788-790`）为：「内置技能尚未覆盖。先保存正文生成覆盖版本，之后可管理文件与脚本。」
P2 可选：由 `SkillDetail` 直接下发 `filesWritable` / `executionWritable`，前端不再推断（记录加兼容构造器，模式同 `SkillDetail.java:36-52`）。

### S2 · 写路径接乐观锁与变更说明
- 更新：`PUT /api/admin/skills/{id}?revision={detail.revision}`，body 增加 `changeNote`。
- 切换执行开关：同更新接口，带 `revision`，成功后用响应里的新 `revision` 回写本地。
- 删除技能/删除文件：`DELETE ...?revision=`（后端已支持，`SkillAdminController.java:231-249`）。
- 409 分支：用 `ApiError.status === 409` 判定（禁止匹配消息文本），展示冲突条，保留用户输入，提供显式覆盖。
- `withRevision` 抽到前端公共函数（现有实现见 `AgentsView.vue:158`），三处复用。

### S3 · 单文件写接口（去掉 N 次读）
新增 `PUT /api/admin/skills/{id}/files/{*path}?revision=`，body `{content}`。
实现（P1 先做控制器级组合，不动端口）：
1. `store.detail(id)` 取快照（含 `revision` 与 `files`）；
2. 服务端读取现有文件正文（`store.fileContent`），替换目标路径；
3. 走 `store.replaceFiles(id, files, expectedRevision, operator)`——一次写的原子性、409、审计都复用；
4. 返回 `{id, path, revision, files}`。

这样浏览器只发 **1 次**请求；并发保护仍在。P2 再把读改写下沉到 `SkillStore` 的单文件方法，减少服务端读放大。
`DELETE /{id}/files/{*path}` 已支持 `revision`（`SkillAdminController.java:231-249`），前端只需传。

### S4 · 新建流程（模板 + 校验 + 引导）
- 空态/新建入口改成三条路径卡（见 5.2），导入复用现有 Git/ZIP 面板。
- 模板：4 个内置模板（政策说明 / 处理流程 / 写作规范 / 数据分析），每个带 3 行骨架与一句「合格标准」。
- 质量清单实时校验，规则来源：
  - 现有门槛在 `SkillContentRules`（≥200 字、需 Markdown 标题、需角色/流程关键词），但它是**包级私有**（`SkillContentRules.java:12`）。
  - 方案 A（推荐）：把该类提为 `public` 或在 `payroll-common` 增加 `SkillContentValidator` 门面，后端提供 `POST /api/admin/skills/validate`（不落库），前端防抖调用；规则单一来源，不漂移。
  - 方案 B：前端复制常量（`MIN_CONTENT=200` 等）做即时提示，提交后仍以后端为准；成本低但双份。
- 草稿：`localStorage` 按 `skill-draft:{id|new}` 存，失焦/30s 保存，成功后清除；离开弹窗提示。
- 创建成功 → `router.push('/platform/skills/{id}/overview')`，展示下一步清单。

### S5 · 导入向导
- 三步：`来源 → 预检 → 确认`。
- 预检渲染 `files[]`（接口已返回，`SkillAdminController.java:327`）为树：按 `references/`、`scripts/`、其他分组，标注 `新增 / 覆盖 / 不变`、单文件大小、合计；已有同名技能时对比「将新增 3 个、覆盖 2 个、删除 0 个」。
- 明确区分两类 409（`SkillAdminController.java:290-296`）：
  - 「已存在，需确认覆盖」→ 勾选 `overwrite` 后重试；
  - 「令牌过期」→ 提示刷新后重试。
- ZIP 分支改用 `uploadForm('/api/admin/skills/import/zip', formData)`（`frontend/src/api/http.js:59-67`），统一 401/409/错误体。
- 导入完成后进入工作台，不停在列表。

### S6 · 正文编辑器
- 双态：`编辑`（textarea + 质量清单）/ `预览`（Markdown 渲染）。
- 排版：等宽字体、行号可选、`Tab` 插入两空格、`Ctrl/Cmd+S` 保存。
- 保存区固定底部：字数、质量清单状态、`变更说明` 输入（仅编辑已有技能时出现）、保存按钮。
- 渲染依赖决策见 D7；未决定前预览可先按「标题/列表/代码块」做轻量只读渲染，或保持 `<pre>`。

### S7 · 版本与回滚
- 时间线数据已有：`GET /{id}/versions`（`SkillAdminController.java:169-178`），行内含 `versionNo/changeNote/contentLength/createdBy/createdAt/current`（`SkillVersion.java`）。
- diff 需要正文：新增 `GET /{id}/versions/{versionNo}` 返回 `{versionNo, content, contentHash}`。
  - 端口增加默认方法 `versionContent(id, versionNo)`，默认抛「该来源不支持版本正文」；`JdbcSkillStore` 用现成 `SELECT_VERSION_BY_NO` 实现（`JdbcSkillStore.java:132-135`、`:1438-1450` 已有同款查询）。
  - 前端做**行级 diff**（本地 LCS，约 60 行），不引入依赖；Nacos 源 `versions` 为空，页签显示「该来源无版本历史」。
- 回滚：`POST /{id}/versions/{versionNo}/rollback?revision=`（已有，`:185-204`），确认弹窗写明「将生成新版本 vN+1，不删除历史」。

### S8 · 数据页
- 信息：状态、setup 路径、配额（上限/已用/占比条）、本次耗时、最后成功时间、失败分类。
- 失败分类来自现有字段映射：`exitCode 124` 超时、`137` 被 kill（多为超配额）、非零其他为脚本失败、`UNAVAILABLE` 为沙箱不可达；`message` 原样折叠展示，不吞。
- 失败卡给三个动作：`查看日志`、`重新初始化`、`复制诊断信息`。
- 轮询：`INITIALIZING` 时 2.5s；离开页签提示「初始化在后台继续，可稍后回来查看」；重进工作台自动查一次状态（不依赖上次轮询）。
- 取消：沙箱暂无取消接口，见 D8/D9。

### S9 · 列表运营化
- 后端在 list 响应里补：`executionEnabled`、`versionNo`、`boundAgents`。
  - `boundAgents` 不必查库：控制器已注入 `agentRegistry`，`scaleStats` 已遍历（`SkillAdminController.java:542-563`），按 `skillIds` 计数即可。
  - `SkillSummary` 增字段时加兼容构造器，避免改动所有调用点（模式同 `SkillDetail.java:36-52`；Nacos `fileCount=0` 的语义见 `SkillStore.java:33-37`）。
- 前端加「数据」列时**不要逐条调沙箱**：P1 只显示「已声明 setup / 无需数据」，实际状态进工作台再查。P2 再做当前页批量 health + 短 TTL 缓存。

### S10 · 试运行：走对话，不走管理台
管理台直接执行脚本会绕开「提案 → 用户确认」的安全模型（`docs/skill-sandbox-data-flow.md` §3）。本期做法：
- 工作台「绑定」页签列出引用该技能的智能体，提供「去对话」；
- 概览提供「复制试用提示词」（把技能用途 + 一个示例问题拼好，粘到对话框）；
- 真正的「按技能一键试跑」需要对话页支持按智能体/技能开启会话，属独立需求（`ChatView` 当前不解析 `agent` 查询参数），不在本期。

### S11 · 危险操作统一确认
- 用一个确认弹窗替换 4 处 `window.confirm`：标题 + 影响面（如「将删除 3 个文件、清空 data/」「该技能被 2 个智能体引用，删除前需解绑」）+ 破坏性按钮色 + 需要时输入技能 ID 确认。
- 删除技能：前端先查绑定（`GET /api/admin/agents?skill=`），有绑定直接禁用并给跳转。

### S12 · 一致性与可访问性
- 表格操作列统一为「主操作 + ⋯」；badge 颜色附文字，不单靠颜色表意。
- 所有按钮可 Tab 聚焦；弹窗焦点陷阱与 Esc 关闭；错误提示用 `role="alert"`。
- 空态、加载态、错误态三件套补齐（现状大量 `暂无…`，无重试）。

---

## 7. 关键状态矩阵

| 场景 | 界面状态 | 主操作 | 文案要点 |
| --- | --- | --- | --- |
| 存储只读（`writable=false`） | 顶部橙色条 | 只读浏览 | 「当前存储只读，写操作已禁用」+ 原因 |
| db 源 + 未覆盖内置 | 文件区只读 | 编辑正文 | 「先保存正文生成覆盖版本」 |
| 保存冲突 409 | 顶部冲突条 + 输入保留 | 查看差异 / 覆盖 / 放弃 | 写明对方版本号 |
| 正文未达门槛 | 保存按钮禁用 | 查看缺什么 | 三项清单逐条给出 |
| 技能被引用 | 删除按钮禁用 | 去解绑 | 列出智能体名 |
| 数据 `INITIALIZING` | 进度卡 + 轮询点 | 可离开 | 「后台继续，稍后回来」 |
| 数据 `FAILED` | 红色状态 + 诊断卡 | 重试 / 看日志 | 分类原因 + exitCode |
| 沙箱未启用 | 数据页黄色说明 | — | 「沙箱未部署，无法初始化」 |
| Nacos 源无版本 | 版本页签空态 | — | 「该来源为整包发布，无版本历史」 |
| 导入同名冲突 | 预检警告 + 覆盖勾选 | 覆盖导入 | 列出将被覆盖的文件 |

---

## 8. 接口契约变更

| # | 方法 & 路径 | 类型 | 说明 | 兼容性 |
| --- | --- | --- | --- | --- |
| A1 | `GET /api/admin/skills` | 变更 | 每行新增 `executionEnabled`、`versionNo`、`boundAgents` | 纯新增字段 |
| A2 | `PUT /api/admin/skills/{id}/files/{*path}?revision=` | 新增 | 单文件写；body `{content}`；返回 `{id,path,revision,files}`；409 带 `currentRevision` | 新接口，保留整包 `PUT /files` |
| A3 | `DELETE /api/admin/skills/{id}/files/{*path}?revision=` | 既有 | 前端补传 `revision`，处理 409 | 无后端改动 |
| A4 | `PUT /api/admin/skills/{id}?revision=` | 既有 | 前端补传 `revision` 与 `changeNote` | 无后端改动 |
| A5 | `GET /api/admin/skills/{id}/versions/{versionNo}` | 新增 | 返回版本正文，供 diff | 新接口；Nacos 返回不支持 |
| A6 | `POST /api/admin/skills/validate` | 新增（P1，可选） | 校验 `{description, content}`，返回结构化问题；不落库 | 新接口 |
| A7 | `GET /api/admin/agents?skill={id}` | 既有 | 工作台「绑定」页签数据源 | 无改动 |

错误契约统一：`ApiResponse{status:"ERROR", message, data}`；409 的 `data.currentRevision` 用于提示；前端按 `status` 分支，不读 message。

---

## 9. 决策记录

| # | 议题 | 选项 | 推荐 | 理由 |
| --- | --- | --- | --- | --- |
| D1 | 详情形态 | A 保留抽屉 + 加深链；B 全屏工作台替换抽屉 | **B** | 六个页签 + 版本 diff 在抽屉里放不下；深链是分享/排障前提 |
| D2 | 写入能力判定 | A 后端下发 capability；B 前端按 `source/overridden` 推断 | **B（P0）/ A（P2）** | P0 一行修复即可止血；长期避免前端猜存储语义 |
| D3 | 单文件写 | A 新增接口；B 保留整包 + 前端增量 | **A（控制器级组合）** | 去掉 N 次往返，同时复用 `replaceFiles` 的原子性与 409 |
| D4 | 列表健康字段 | A 逐条查沙箱；B 只放静态字段 + 详情懒查；C 批量 health + 缓存 | **B（P1）→ C（P2）** | 沙箱查询是重操作，列表不能 N 次调用 |
| D5 | 版本 diff 计算 | A 后端返回 unified diff；B 后端返回正文、前端本地 diff | **B** | 不新增依赖；版本正文接口本来就需要 |
| D6 | 试运行 | A 管理台直接执行；B 对话深链；C 复制提示词 | **C（P1）→ B（需对话页支持选智能体）** | 不绕开确认模型 |
| D7 | Markdown 渲染 | A 引入 marked + DOMPurify；B 保持 `<pre>`；C 轻量只读渲染 | **A（需批准新依赖）/ B 兜底** | 预览是写作体验核心；必须 sanitize |
| D8 | 数据初始化取消 | A 新增沙箱 cancel；B 不做取消，只给「后台继续」与重试 | **B（P1）/ A（P2）** | 沙箱侧无 cancel 契约，新增面较大 |
| D9 | 只读权限 | A 新增 `skill:read`；B 维持 `skill:manage` | **A（P2）** | 审计/主管需要看，不该给写权限 |

---

## 10. 分期与任务

### P0（止血，0.5–1 人日）

| ID | 任务 | 涉及文件 | 验收 |
| --- | --- | --- | --- |
| P0-1 | 修正 `canEditFiles` / `canToggleExecution` 判定；改写内置提示 | `frontend/src/views/admin/SkillsView.vue:80-87`、`:788-790` | db 源下自定义/已覆盖技能可增删改文件、可切执行开关；未覆盖内置给出正确原因 |
| P0-2 | 保存/切执行/删技能/删文件带 `revision`；409 冲突条；成功后回写 `revision` | `SkillsView.vue:383-443`、`:509-520`；新增 `withRevision` 工具（参照 `AgentsView.vue:158`） | 双标签页并发保存，后保存者见 409 且输入不丢；覆盖需二次确认 |
| P0-3 | 来源筛选补 `db`；修正页头与空态文案 | `SkillsView.vue:534`、`:547-551` | 三种来源均可筛选；文案不再出现「存入 Nacos」误导 |
| P0-4 | ZIP 导入改走 `uploadForm`；统一错误对象 | `SkillsView.vue:293-309`；`frontend/src/api/http.js:59-67` | 401 跳登录、409 可分支、错误提示与 Git 分支一致 |

回归：`cd payroll-backend && mvn -q -DskipTests package` 不涉及后端；前端 `cd frontend && npm run build`；手工验证 db 源主链路（见 §11）。

### P1（体验骨架，3–5 人日）

| ID | 任务 | 涉及文件 | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| P1-1 | 新建 `SkillWorkbenchView.vue` + 路由 `/platform/skills/:id/:tab`；列表跳转；移除抽屉 | `frontend/src/router/index.js`、`frontend/src/views/admin/SkillWorkbenchView.vue`、`SkillsView.vue` | P0 | 刷新/分享工作台 URL 可还原；浏览器后退回列表并保留筛选 |
| P1-2 | 版本时间线 + diff + 回滚 | `SkillAdminController.java`（新 A5）、`SkillStore.java`、`JdbcSkillStore.java`、工作台版本页签 | P1-1 | 能看到 v1..vN、变更说明、行级 diff；回滚生成新版本 |
| P1-3 | 单文件写接口 A2 + 前端单次保存 | `SkillAdminController.java`、`SkillsView.vue`/工作台文件页签 | P0-2 | 20 个文件时保存仍为 1 次写请求；并发改不同文件不再互相覆盖 |
| P1-4 | 列表运营列（A1） | `SkillSummary.java`、`SkillAdminController.java:107-131`、列表页 | — | 绑定数/执行状态/版本正确；无 N 次沙箱调用 |
| P1-5 | 新建三路径 + 模板 + 质量清单 + 草稿 + 创建后引导 | 工作台、新建弹窗（抽为组件） | P1-1 | 空态三卡；门槛实时提示；创建后进入概览并显示下一步 |
| P1-6 | 数据页增强（配额/耗时/失败分类/离开提示） | 工作台数据页签、`SkillAdminController.java:379-483` 返回字段补齐 | P1-1 | 失败能定位到分类与 exitCode；重进页面自动刷新状态 |
| P1-7 | 导入向导 + 文件树 + 覆盖差异 | 导入弹窗（抽为组件） | P0-4 | 预检能看到文件树与新增/覆盖数量；两类 409 分开处理 |
| P1-8 | 统一危险操作确认弹窗 | 新组件 + 4 处调用 | P1-1 | 删除/重建均有影响面说明；被引用时禁用并给跳转 |
| P1-9 | 列表筛选写 URL | `SkillsView.vue` | — | 返回/刷新保留 keyword/type/sort/page |

测试落点：后端 `payroll-tests/src/test/java/io/agentscope/examples/payroll/web/SkillAdminControllerTest.java`（A1/A2/A5 契约）、`payroll-tests/.../skill/JdbcSkillStoreTest.java`（版本正文）；前端新增 Playwright 冒烟 `frontend/tests/e2e/skills-smoke.spec.js`（列表 → 工作台 → 改正文 → 冲突提示）。

### P2（运营化）

| ID | 任务 | 说明 |
| --- | --- | --- |
| P2-1 | `skill:read` 权限拆分 | 路由/菜单/接口三处；写接口仍要求 `skill:manage` |
| P2-2 | 当前页批量 health + 短 TTL 缓存 | `GET /api/admin/skills/health?ids=`，沙箱不可用降级为「未知」 |
| P2-3 | 调用观测 | 由审计/执行记录聚合 `lastUsedAt`、近 7 天调用次数、失败率 |
| P2-4 | Markdown 渲染 | `marked` + `DOMPurify`（需批准新增依赖）；仅预览态渲染 |
| P2-5 | 数据初始化取消 | 需沙箱新增 cancel 契约（当前无），先评估 |
| P2-6 | 排序表头、批量操作、键盘快捷键 | 列表规模化后的效率项 |

---

## 11. 验收标准

**功能（P0）**
1. Given db 源、自定义技能，When 打开工作台文件页签，Then 可新增/编辑/删除文件，执行开关可切换。
2. Given 未覆盖的内置技能，When 打开文件页签，Then 按钮禁用且提示「先保存正文生成覆盖版本」，不再提 Nacos。
3. Given 两个标签页同时打开同一技能，When A 先保存、B 后保存，Then B 收到 409 冲突条，B 的输入仍在，选择「查看差异/覆盖/放弃」后行为符合预期。
4. Given ZIP 包导入遇到 401，Then 跳转登录页（与 Git 导入一致）。

**功能（P1）**
5. Given 工作台 URL `/platform/skills/poetry-notes/versions`，When 直接打开，Then 还原该技能该页签。
6. Given 版本 v5/v6，When 选中两版，Then 显示行级 diff；回滚 v5 后生成 v7 且 v6 仍在时间线。
7. Given 文件包有 20 个文件，When 保存 1 个，Then 只发出 1 次写请求。
8. Given 技能被 2 个智能体引用，When 点删除，Then 按钮禁用并列出引用方。
9. Given 数据初始化失败，When 打开数据页，Then 显示失败分类与 exitCode，并可一键重试。
10. Given 导入 Git 项目，When 预览，Then 看到文件树与「新增/覆盖」数量。

**非功能**
- 列表首屏不产生沙箱请求；工作台切页签不重复拉取详情。
- 所有禁用态有原因文案；所有错误有可执行出口。
- 文案中不出现内部存储名（Nacos、db）作为主叙述，仅作为「来源」技术字段。

**回归**
- `mvn -q test` 全绿；`npm run build` 通过；`frontend/tests/e2e/login-smoke.spec.js` 与新增冒烟通过。

---

## 12. 指标与埋点

数据源优先用现有审计表（已有 `skill.create/update/import/init.start/init.finish/version.rollback`），不新建埋点基础设施。

| 指标 | 定义 | 数据来源 | 目标 |
| --- | --- | --- | --- |
| 创建→可用转化率（北星） | 创建后 14 天内：正文存在且绑定 ≥1 智能体 | `audit_log` + `agent_definition.skill_ids` | ≥60% |
| 首次保存成功率 | 首次保存成功 / 新建技能数 | 审计 `skill.create` + 前端错误 | ≥85% |
| 保存冲突率 | 409 次数 / 保存次数 | 后端 409 计数 | <2% |
| 导入完成率 | 预览后确认导入 / 预览次数 | 审计 `skill.import`（dryRun 不计） | ≥80% |
| 初始化成功率 | 成功 / 发起（排除沙箱未启用） | `skill.init.finish` 状态 | ≥90% |
| 技能周活 | 近 7 天被读取/执行的技能数 / 技能总数 | 运行期技能访问日志 | 观察项 |

前端交互量（点击、停留）本期不采集，避免新增依赖。

---

## 13. 风险与回滚

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 工作台重构面大 | P1 延期、回归面扩大 | 先落路由壳与只读页签，再逐页签迁移；P0 不动结构 |
| `SkillSummary` 记录签名变更 | 序列化/测试大面积红 | 加兼容构造器 + 契约测试；或列表用控制器内视图对象 |
| 单文件接口与整包替换并发 | 互相覆盖 | 统一 `revision`；两类 409 分开提示 |
| 新增前端依赖 | 供应链与体积 | D7 未批准前不引入；预览先做轻量渲染 |
| 列表健康列误报 | 用户误判 | P1 只放静态/本地可得字段；未知显示「—」不显示「失败」 |
| 数据页轮询 | 压力 | 仅 `INITIALIZING` 时轮询，离开即停，重进单查 |

回滚策略：所有 P1 改动按页签/接口分组提交，单组可独立 revert；数据库无新增 DDL（本期不改表结构）。

---

## 14. 可执行性自检

| 规格断言 | 证据 | 结论 |
| --- | --- | --- |
| 文件编辑被 `source==='nacos'` 卡住 | `SkillsView.vue:80-82` | ✅ |
| db 源 `source` 为 `db` | `JdbcSkillStore.java:45` | ✅ |
| 未覆盖内置 `source` 为 `builtin`、`overridden=false` | `JdbcSkillStore.java:1599-1617` | ✅ |
| 保存未传 revision/changeNote | `SkillsView.vue:387-397` | ✅ |
| 后端更新支持 revision + changeNote | `SkillAdminController.java:267-288` | ✅ |
| 删文件支持 revision | `SkillAdminController.java:231-249`、`SkillStore.java:74-84` | ✅ |
| 版本列表/回滚已存在 | `SkillAdminController.java:169-204` | ✅ |
| 版本行不含正文（diff 需新接口） | `SkillVersion.java`、`JdbcSkillStore.java:132-135` | ✅ |
| 单文件写只有整包 `replaceFiles` | `SkillStore.java:47-57` | ✅ |
| `SkillContentRules` 包级私有 | `SkillContentRules.java:12` | ✅ |
| 质量门槛在存储层抛错 | `JdbcSkillStore.java:1043-1044` | ✅ |
| 前端无 Markdown 依赖 | `frontend/package.json` | ✅ |
| 仅 `skill:manage` 权限 | `Permissions.java:34`、`router/index.js:53` | ✅ |
| 智能体按技能过滤可用 | `AgentAdminController.java:152-155` | ✅ |
| 对话页不解析 agent 查询参数 | `ChatView.vue`（无 route.query/agent 选择） | ✅（故 D6 推荐 C） |
| ZIP 导入返回 files[] | `SkillAdminController.java:349-364`、`importMeta` | ✅ |

未决项：D1–D9 需拍板，其中 D7（新依赖）、D8（沙箱 cancel）、D1（工作台范围）影响排期。

---

## 15. 附录

### 15.1 证据索引

| 主题 | 位置 |
| --- | --- |
| 前端技能页全部逻辑 | `frontend/src/views/admin/skills/`（列表、工作台、编辑器、新建、导入与五个页签组件）；`SkillsView.vue` 仅剩 `<router-view>` |
| 列表/详情/文件/版本/导入/数据接口 | `payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java` |
| 存储端口与并发语义 | `payroll-common/src/main/java/io/agentscope/examples/payroll/skill/SkillStore.java` |
| db 实现（source、内置回退、fileCount） | `payroll-common/src/main/java/io/agentscope/examples/payroll/skill/JdbcSkillStore.java` |
| 摘要/详情/版本记录 | `SkillSummary.java`、`SkillDetail.java`、`SkillVersion.java` |
| 质量门槛 | `SkillContentRules.java` |
| 前端请求封装 | `frontend/src/api/http.js` |
| 权限 | `payroll-common/src/main/java/io/agentscope/examples/payroll/support/Permissions.java` |
| 路由与菜单 | `frontend/src/router/index.js`、`frontend/src/router/workspaces.js` |
| 既有 revision 用法 | `frontend/src/views/admin/AgentsView.vue:158` |
| 后端测试落点 | `payroll-tests/src/test/java/io/agentscope/examples/payroll/web/SkillAdminControllerTest.java`、`.../skill/JdbcSkillStoreTest.java` |

### 15.2 文案对照（改前 → 改后）

| 位置 | 改前 | 改后 |
| --- | --- | --- |
| 页头 | 技能正文以 Markdown 形式存入 Nacos… | 技能让智能体按固定流程和资料工作。保存即生成新版本，可随时回滚。 |
| 内置提示 | 内置技能尚未覆盖到 Nacos，请先在「正文」页签编辑并保存… | 内置技能尚未覆盖。先保存正文生成覆盖版本，之后即可管理文件与脚本。 |
| 文件保存 | （无提示，转圈） | 仅保存这一个文件；他人同时修改会提示冲突。 |
| 数据初始化 | 已提交初始化，完成后可刷新状态 | 初始化在后台进行（通常 1–10 分钟），可以离开，稍后回来查看。 |
| 冲突 | （无） | 该技能已被更新到 v8。请查看差异、另存为 .md 或重新加载；不提供强制覆盖。 |
| 删除 | 删除技能「x」？ | 删除后无法从列表恢复；该技能被 2 个智能体引用，请先解绑。 |

---

## 16. 实施状态（2026-11-26）

本文的 P0/P1 与 S1–S12 已随 `docs/skill-console-prototype-parity-spec.md` 的 R0–R4 落地；页面结构以该规格为准，本文保留体验目标与决策背景。逐项状态如下。

### 16.1 P0 / P1

| ID | 状态 | 落点 |
| --- | --- | --- |
| P0-1 写文件/开脚本判定 | 完成 | 判定改由后端 `writable` + `source` 下发；Nacos 源整体只读 |
| P0-2 revision 与 409 | 完成 | 编辑器 409 冲突条三出口：查看差异 / 另存为 .md / 重新加载，成功写回 `revision` |
| P0-3 来源筛选与文案 | 完成 | 列表支持 db/nacos/builtin/覆盖；主叙述去掉内部存储名 |
| P0-4 ZIP 导入统一错误 | 完成 | Git/ZIP 共用 `uploadForm` 与同一错误分支 |
| P1-1 工作台路由 | 完成 | `/platform/skills/:id/:tab?`，六页签，`references|scripts → files` 别名 |
| P1-2 版本时间线与回滚 | 完成 | 版本页签 + 行级 diff（`DiffView.vue`）+ 回滚生成新版本行 |
| P1-3 单文件写 | 完成 | `PUT/DELETE /{id}/files` 单文件接口；保存只发 1 次写请求 |
| P1-4 列表运营列 | 完成 | 列表只读 `skill_data_state` 缓存，不逐条查沙箱 |
| P1-5 新建三路径 | 完成 | 空白创建 / 模板创建（4 个内置模板）/ 从技能包导入；草稿与质量清单 |
| P1-6 数据页 | 完成 | 状态徽章、声明上限、`exitCode`、失败分类；非终态 5s 轮询且仅当前页 |
| P1-7 导入向导 | 完成 | 三步骤 + 逐文件 added/replaced/unchanged + 覆盖二次确认 |
| P1-8 危险操作确认 | 完成 | 删技能（输入 ID）、删文件、重建数据共用确认框 |
| P1-9 列表筛选写 URL | 完成 | keyword / type / dataStatus / view / sort / page 双向同步 |

### 16.2 P2 与决策降级

| 项 | 状态 | 说明 |
| --- | --- | --- |
| P2-1 `skill:read` 拆分（本文 D9） | 未做 | 仍用 `skill:manage`；见 parity spec §12 与待办 |
| P2-2 批量 health 接口 | 改为落库缓存 | 由 `SkillDataStateRefresher` 后台刷新 `skill_data_state`，列表读取缓存；无批量接口 |
| P2-3 调用观测 | 未做 | 仍靠审计/执行记录 |
| P2-4 Markdown 渲染 | 已完成（提前） | `marked` + `DOMPurify`，预览态 sanitize，e2e 覆盖脚本/图片清洗 |
| P2-5 初始化取消 | 未做 | 沙箱无 cancel 契约，仅支持「后台继续」与重试 |
| P2-6 排序表头/批量操作 | 部分 | 排序已支持（URL `sort`）；批量操作未做 |
| D6 试运行（parity D9） | 暂缓 | 管理台不提供「在对话中试用」，避免绕过确认模型 |
| 「谁在编辑」（parity D10） | 降级 | 显示「最近保存」与 `updatedBy`，不做 presence |

### 16.3 验证

- 后端：`mvn -o -q -DskipTests package`；定向 `SkillAdminControllerTest`、`JdbcSkillStoreTest`、`SkillImportServiceTest`、`SkillDataStateStoreTest`、`SkillQualityTest`、`SkillTemplatesTest` 共 98 条通过；全量 `mvn -o test` 1506 / 0 / 0 / 4（MySQL 腿未配置环境变量跳过）。
- 前端：`npm run build`；vitest 44/44。
- Playwright：`login-smoke` 2 + `skills-smoke` 16 + `visual-smoke` 4，全绿；视觉基线 31 张，其中非技能 19 张哈希未变。
