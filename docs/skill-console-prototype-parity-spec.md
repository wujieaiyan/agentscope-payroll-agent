# 技能管理台「原型严格对齐」重设计规格

> 状态：待评审（规格，不含实现）  
> 上游文档：`docs/skill-console-ui-redesign.html`（视觉与交互原型，下称「原型」）、`docs/skill-console-ui-redesign.md`（诊断、令牌、批次）、`docs/skill-console-ux-spec.md`（F/S/P 编号与验收）  
> 本文回答一个问题：**怎样把技能控制台做到与原型一致，并列出必须同步修改的后端契约。**

---

## 0. 结论与边界

### 0.1 一句话结论

现有实现保留了原型的信息，但**页面形态是旧的**：列表没有汇总行、没有数据状态/版本/绑定列；工作台缺少概览与绑定页签；编辑器与导入仍是弹窗；新建只有两张卡。严格对齐需要 **5 屏前端重写 + 1 组后端读模型/迁移**，其中后端新增 6 个读接口、2 张辅助表、1 套模板接口，并扩展列表与导入预览的返回结构。

### 0.2 「严格」的定义

| 维度 | 本文承诺 |
| --- | --- |
| 五屏结构 | 列表、工作台、编辑器、新建、导入的**层级、区块、列、页签、步骤**与原型一致 |
| 视觉 | 画布宽度、栅格、间距、字号、圆角、状态色、按钮形态按原型；令牌沿用 `theme-v2`，只做差异修正 |
| 文案 | 标题、说明、空态、危险区、提示按原型（数据相关文案按真实值替换） |
| 数据 | 所有数字、状态、头像、时间来自真实接口；禁止硬编码演示值 |
| 交互 | 路由、页签、搜索/筛选/排序、分页、步骤流、冲突处理、脏数据守卫可用 |
| 不做 | 用假数据「看起来像」；不得为了截图通过而写死 24/21/2/3 这类演示数字 |

### 0.3 外壳决策（沿用，不重新讨论）

`skill-console-ui-redesign.md` §5.1 已拍板 **方案 B**：保留现有 232px 文本侧栏与工作区切换，不换成原型的 56px 图标 rail。原型画 rail 只是为了展示「内容优先」的极限形态。因此：

- **技能五屏的「画布」严格对齐原型**（页面头部、面板、栅格、表格、页签、步骤条）。
- 外壳只做与原型一致的细节：顶栏 56px 保持、内容区最大宽 **1280**、页面内边距 **18px 24px 64px**、面包屑 `平台能力 / 技能 / <id>`。
- 若后续要把全局导航换成 rail，属于独立的信息架构改造（影响 9+ 个非技能页面），不在本期。

### 0.4 范围外（明确不做）

- 不改权限模型、审计模型、审批链；沿用 `skill:manage`。
- 不改 Nacos 发布通道；Nacos 源按「无版本、不可写」降级展示。
- 不做模板编辑器（模板内容随代码发布）。
- 不做「在对话中试用」的执行链路（见 D9，进 P2 backlog）。
- 不做移动端原生布局；只保证 820px 以上可用。

---

## 1. 需要拍板的决策

> 推荐项已加粗。未拍板的项不阻塞 R0/R1，但 D3/D4/D9 会影响 R3 排期。

| 编号 | 决策 | 选项 | 推荐 | 影响 |
| --- | --- | --- | --- | --- |
| D1 | 严格范围 | A. 五屏画布严格对齐 + 外壳沿用 B；B. 连全局 rail 一起换 | **A** | B 会改 9+ 页面导航，回归面大 |
| D2 | 编辑器形态 | A. 改为路由页面 `/skills/:id/edit`；B. 保留全屏弹窗 | **A** | 原型是页面；文件树/预览/对比需要页面空间 |
| D3 | 新建第三张卡「从模板创建」 | A. 纳入本期；B. 保持两张卡，模板列 P2 | **A（R3）** | 需要模板接口 + 4 个代码内置模板 + 使用记录表 |
| D4 | 工作台页签 | A. `参考文献 + 脚本` 合并为「文件」；B. 保持两个页签 | **A** | 原型是 6 页签；合并后文件树统一 |
| D5 | 409 冲突出口 | A. 查看差异 / 重新加载 / 另存为 .md，不提供「覆盖为 v9」；B. 按原型提供强制覆盖 | **A** | 强制覆盖会绕过 revision 语义，且丢对方改动 |
| D6 | 列表列 | A. 按原型：技能/状态/绑定/版本/最近更新/操作；来源与文件数进「列设置」 | **A** | 后端需补 versionNo/updatedBy/needsData/绑定/数据状态 |
| D7 | 导入形态 | A. 三步骤页面 + 逐文件「新增/覆盖/不变」；B. 保留弹窗 | **A** | 后端预览需返回逐文件 hash/status、总量、耗时 |
| D8 | 列表数据状态 | A. 落库缓存 `skill_data_state`，列表只读缓存；B. 列表实时问沙箱 | **A** | B 会让列表 N 次调沙箱，规模化后不可用 |
| D9 | 「在对话中试用」 | A. 本期隐藏，P2 单独做最小试用会话；B. 本期实现 | **A** | 需要临时智能体/会话生命周期，属于新能力 |
| D10 | 「谁在编辑」 | A. 改为「最近保存：X · 时间」；B. 做 presence 服务 | **A** | 实时 presence 需要长连接或心跳，成本高 |
| D11 | Markdown 预览/差异 | A. 引入 `marked` + `dompurify` + `diff`；B. 手写最小渲染与 LCS | **A** | 手写渲染在表格/代码块上质量差；A 需过 XSS 用例 |
| D12 | 主色 | A. `theme-v2` 已生效，仅把 `--primary` 从 `#2d8cf0` 调到 `#2f6feb`；B. 不动 | **A** | 全站微调蓝色，截图基线需重拍 |
| D13 | 删除确认 | A. 保留「输入技能 ID」确认；B. 按原型仅一个按钮 | **A** | 删除不可恢复；输入 ID 是现有安全基线 |

---

## 2. 差距总表（证据 → 处理）

### 2.1 五屏差距

| 屏幕 | 原型 | 现状（证据） | 差距 | 处理 |
| --- | --- | --- | --- | --- |
| 列表 | 页头 + 汇总行 + 面板（搜索/chips/排序/列设置）+ 6 列 + 页码 | `SkillsListView.vue:214` 页头、`:284` 表格；列只有 技能/来源/状态/绑定/文件/更新；工具栏是分段视图 + 三个下拉 | 无汇总行；列不同；无头像/版本/更新人；分页只有上一页/下一页；无列设置 | R1 重写 |
| 工作台 | 面包屑 + 大标题 + 6 页签 + 主栏（健康/下一步/最近变更）+ 右栏（引用方/标识/危险区） | `SkillWorkbench.vue:25` 只有 body/references/scripts/data/versions；`:433` 页签条；`:529` 右侧只有质量检查 | 缺概览、文件合并、绑定页签；缺健康卡/时间线/引用方/标识；危险区在菜单里 | R2 重写 |
| 编辑器 | 路由页面；源码/预览/对比；三栏 196/1fr/288；文件树；行号；保存条；冲突条；变更说明 | `SkillEditor.vue:160` 是全屏 `role=dialog` 弹窗；`:192` 两栏；`:236` textarea；无文件树/预览/差异/行号 | 形态、栏数、能力都不同；冲突只有「重新加载/另存为 .md」 | R3 重写为页面 |
| 新建 | 三步条 + 三张路径卡（含「从模板创建」推荐）+ 最近模板 + 提示 | `SkillCreateChooser.vue` 两卡（空白/导入），无模板、无最近模板 | 缺第三卡与模板面板 | R3 重写 |
| 导入 | 面包屑 + 三步条 + 来源卡 + 文件树（新增/覆盖/不变）+ 摘要 + 预检结果 | `SkillImportDialog.vue:204` 弹窗；`:215` Git/ZIP 分段；`:259` 预览只有新增/覆盖/其余计数，无逐文件状态 | 形态、步骤、逐文件状态都不同；无来源耗时/SHA | R3 重写 + R0 扩展预览契约 |

### 2.2 原型能力取舍表

| 能力 | 处理 | 说明 |
| --- | --- | --- |
| 五屏结构与页签 | **保留** | 本规格主体 |
| 参考文件 + 脚本 | **合并** | 统一为「文件」页签，内部按目录分组 |
| 版本时间线 | **保留并拆分** | 概览显示最近 5 条；版本页签显示完整列表与回滚 |
| 数据状态 | **保留并落库** | 列表、概览、数据页共用同一状态 |
| 列设置 | **恢复** | 来源、文件数为可选列（默认隐藏） |
| 模板创建 | **恢复** | 第三张卡 + 最近模板（D3） |
| 三步骤导入 | **恢复** | 来源 → 预检 → 确认导入 |
| 文件树 / 预览 / 差异 | **恢复** | 编辑器能力 |
| 56px rail | **放弃** | 沿用 redesign.md D1-B |
| 「在线 / 主入口」 | **放弃** | 系统没有在线态；显示智能体名称与描述 |
| 「谁在编辑」 | **放弃** | 改为「最近保存」 |
| 「覆盖为 v9」 | **放弃** | 安全出口替代（D5） |
| 「在对话中试用」 | **暂缓** | P2 backlog（D9） |
| 演示数字 24/21/2/3 | **放弃** | 全部替换为真实统计 |

---

## 3. 路由与信息架构

### 3.1 目标路由

| 路径 | 页面 | 说明 |
| --- | --- | --- |
| `/platform/skills` | 列表 | 查询参数：`keyword/page/size/view/source/sort/dataStatus` |
| `/platform/skills/new` | 新建选择页 | 三张路径卡 + 最近模板 |
| `/platform/skills/new/editor` | 新建编辑器 | `mode=create`，保存后跳工作台概览 |
| `/platform/skills/import` | 导入向导 | 三步；从选择页/列表进入 |
| `/platform/skills/:id` | 工作台 | 重定向到 `/overview` |
| `/platform/skills/:id/:tab?` | 工作台 | `tab ∈ overview|body|files|data|versions|bindings`，默认 `overview` |
| `/platform/skills/:id/edit` | 编辑器 | `mode=edit`；可带 `?path=` 定位文件 |

兼容与重定向：

- `references|scripts` → `files`（保留旧链接）。
- 旧的 `/platform/skills/:id/body` 继续有效。
- 旧弹窗相关入口（列表「编辑」、工作台「编辑正文」）改为 `router.push(.../edit)`。
- 静态路由 `/new`、`/import` 优先于 `:id`（Vue Router 静态段天然更高优先级）。仍建议在 `SkillIds` 校验中**保留 `new`、`import` 两个 ID**，避免与路由冲突（若库内已存在，实施前先查一次并改名）。

### 3.2 文件与组件

```
frontend/src/views/admin/
├── SkillsView.vue                     # 容器：列表 / 工作台 / 编辑器 / 新建 / 导入路由出口
└── skills/
    ├── SkillsListView.vue             # R1 重写
    ├── SkillWorkbench.vue             # R2 重写（壳 + 页签路由）
    ├── tabs/
    │   ├── SkillOverviewTab.vue       # 新
    │   ├── SkillBodyTab.vue           # 从原 body 分支抽出
    │   ├── SkillFilesTab.vue          # 新（合并 references/scripts）
    │   ├── SkillVersionsTab.vue       # 从原版本分支抽出
    │   └── SkillBindingsTab.vue       # 新
    ├── SkillDataPanel.vue             # 保留，补状态徽标与配额条
    ├── SkillEditorPage.vue            # 新（替换 SkillEditor.vue）
    ├── SkillCreatePage.vue            # 新（替换 SkillCreateChooser.vue）
    ├── SkillImportPage.vue            # 新（替换 SkillImportDialog.vue）
    ├── SkillTemplatePicker.vue        # 新（模板选择弹窗）
    ├── MarkdownPreview.vue            # 新
    ├── DiffView.vue                   # 新
    ├── SkillTile.vue                  # 新（首字方块，t1–t4 配色）
    ├── SkillStatusPill.vue            # 新（数据状态）
    ├── AgentAvatars.vue               # 新
    └── rules.js                       # 保留，补充状态映射与相对时间辅助
```

`components/ui/` 继续复用 `ConfirmDialog / EmptyState / SkeletonRows`；新增 `SegmentedTabs.vue`、`PanelCard.vue` 两个通用件。

---

## 4. 设计令牌与画布

### 4.1 令牌差异（只列需要改的）

| 令牌 | 现值 | 目标 | 说明 |
| --- | --- | --- | --- |
| `--primary` | `#2d8cf0` | `#2f6feb` | 原型 accent；白字对比 4.5:1 |
| `--primary-dark` | `#1f7ae0` | `#2457c4` | hover |
| `--primary-soft` | `#eaf4fe` | `#edf3ff` | chip/tab 选中底 |
| `--muted` | `#6b7484`（theme-v2） | 不变 | 原型 `#7e8798` 对比不足，**不跟** |
| `--bg` / `--border` / `--text` | 已按 V0 新值 | 不变 | — |

状态语义沿用 `--st-ok/-warn/-bad` 及其 `-soft`。

### 4.2 画布与栅格

**不改全局 `.page` 的 1400px**（其它 20 条路由会受影响），在技能容器上加 `skills-canvas`：

| 项 | 值 | 来源 |
| --- | --- | --- |
| 画布最大宽 | 1280px | 原型 `.canvas` |
| 页面内边距 | 18px 24px 64px | 原型 |
| 页头下间距 | 16px；汇总行下 14px | 原型 |
| 面板 | 白底、1px `--border`、圆角 12、`shadow-1` | 原型 |
| 列表表格 | th `9px 14px`，td `11px 14px`，表头 sticky | 原型 |
| 工作台栅格 | `1fr 300px`，gap 16 | 原型 |
| 编辑器栅格 | `196px 1fr 288px`，gap 12 | 原型 |
| 新建路径卡 | `repeat(3, 1fr)`；1080→2 列；820→1 列 | 原型 |
| 导入栅格 | `1fr 308px`；1080 以下单列 | 原型 |

响应式断点：1080（工作台右栏下移、导入单列、路径卡 2 列）、820（路径卡 1 列、编辑器右栏下移、表格横向滚动）。

---

## 5. 屏幕规格

### 5.1 列表页

**页头**

- `h1 技能`（22/600），副标题：`让智能体按固定流程和资料工作。保存即生成版本，可随时回滚。`
- 右侧：`导入`（ghost，上传图标）+ `新建技能`（primary，加号图标）。
- 无权限（`!writable`）时两个按钮禁用，并在页头下显示只读提示。

**汇总行**（真实统计，全部来自 list `stats`）

```
24 个技能 · ● 21 可用 · ● 2 需处理 · 3 未绑定 · [查看需处理 →]
```

- 技能数、可用、需处理、未绑定四段，段间用 3px 圆点。
- 「查看需处理」→ `view=attention` 筛选；若 `unknownData > 0`，追加 `· N 未知`（沙箱未启用时）并给 tooltip。
- 点击「需处理」数字等价于筛选。

**工具栏**

- 搜索框：宽 ≥250，placeholder `搜索名称、ID 或说明`；按 `/` 聚焦（输入框内不触发）。
- chips：`全部 / 内置 / 自定义 / 已覆盖`，映射现有 `view`；数字来自 `stats.views`。
- 右侧：`数据状态` 下拉（全部 / 就绪 / 需处理 / 无需数据 → `dataStatus`）、`排序` 下拉（最近更新 / 名称 / 大小 → `sort=updated|id|size`）、列设置图标。
- 列设置：弹出复选，`来源`、`文件数` 默认关闭；选择写 `localStorage('skills.columns')`。

**表格**

| 列 | 内容 | 交互 |
| --- | --- | --- |
| 技能（min 330） | `SkillTile` 32px（首字，按 id 哈希 t1–t4）+ 粗体 ID + 副行描述（省略号） | 点击整行打开概览 |
| 状态 | 数据状态 pill：`数据就绪 / 未初始化 / 初始化中 / 初始化失败 / 无需数据 / 沙箱未启用` | 点击进 `data` 页签；失败行主操作变「查看原因」 |
| 绑定 | `AgentAvatars`（最多 3 个 + `+N`）+ `N 个`；无绑定显示「未绑定」 | 点击进 `bindings` 页签 |
| 版本 | `v7`（mono）；Nacos/无版本显示 `—` | — |
| 最近更新 | 相对时间 + `· 更新人`；hover 显示绝对时间；无更新人显示「系统导入」 | — |
| 操作（120） | `打开` 链接 + `⋯` 菜单（编辑 / 导入新版本 / 复制 ID / 删除） | 菜单用 `role=menu`，Esc 关闭并归还焦点 |

**页脚**

- 左：`共 N 个 · 每页 20`；右：页码按钮（当前页高亮，首尾省略号）。切换页保留筛选。
- 列表页大小默认 **20**（仅技能列表；后端 `size` 上限仍 200）。

**状态**

- 加载：`SkeletonRows` 6 行。
- 空：无筛选 → 「还没有技能」+ 新建/导入；有筛选 → 「没有匹配的技能」+ 清空筛选。
- 错误：面板内 `role=alert` + 重试；不整页白屏。
- 只读：操作菜单禁用编辑/删除，提示来源只读。

### 5.2 工作台

**面包屑与头部**

- `技能 / <id>`；`技能` 可点回列表。
- 头部：`SkillTile lg`（42px）+ `h1`（ID，mono 21）+ 描述 + 元信息行：`自定义|内置`、`来源 db|Nacos|builtin`、`vN`、数据状态、执行状态（`脚本已开启 / 脚本未开启 / 无需脚本`）。
- 右侧：`编辑正文`（primary）+ `⋯`（导入新版本 / 删除）。`在对话中试用` 按 D9 暂不显示。

**页签**（`SegmentedTabs`，`role=tablist`）

`概览 | 正文 | 文件 (N) | 数据 (●) | 版本 (N) | 绑定 (N)`

- `文件`/`版本`/`绑定` 显示数字；`数据` 在需处理时显示圆点（不是数字）。
- URL 同步；键盘 ←/→/Home/End 切换；刷新保持当前页签。

**布局**：主栏 `1fr` + 右栏 `300px`，gap 16。

**概览页签**

1. `健康` 面板（3 列）
   - 数据：状态、`已用 / 上限`（bar）、`上次初始化 <时间> · 用时 <时长>`；操作「重建数据」→ 数据页签。
   - 脚本：`执行已开启/关闭`、`N 个脚本`、`setup 命令`（有则显示）、提示「执行仍需在对话中确认」。
   - 绑定：头像 + 智能体名列表 + `N 个`、提示「删除前需先解绑」、链接到绑定页签。
2. `下一步` 面板：4 项 checklist，显示 `完成数 / 4`
   - 正文通过质量检查（服务端 `SkillContentRules`，展示版本号）
   - 已添加参考文件（文件数 > 0）
   - 数据已就绪 / 无需数据（`needsData` 为 false 时直接完成）
   - 已绑定至少 1 个智能体
   - 未完成项给动作按钮（去编辑 / 去文件 / 去数据 / 去绑定）。
3. `最近变更` 面板：最近 5 个版本（vN、变更说明、作者、相对时间）；`全部版本 →` 进版本页签。

**右栏**

- `引用方`：绑定智能体列表（名称 + 描述），链接「去智能体页」；空态「还没有智能体引用」。
- `标识`：技能 ID、来源（`db · 可写` / `Nacos · 只读`）、创建人/时间、更新人/时间、内容指纹前 8 位（点击复制完整 hash）。
- `危险区`：`删除技能`、被引用时说明「被 N 个智能体引用，需先解绑」；按钮在有绑定或只读时禁用；点击打开输入 ID 的确认框。

**正文页签**

- 只读 `<pre>` 正文 + 字符数 + 复制按钮 + `编辑正文`。
- Nacos/内置只读时提示；内置可覆盖，文案按原型提示。

**文件页签**

- 左：目录树（`SKILL.md` 不在此列，正文在正文页签；`references/`、`scripts/`、根目录其他文件），文件行显示大小与类型图标。
- 右：选中文件内容（只读文本预览，二进制显示占位），操作为 `编辑`（进编辑器 `?path=`）、`删除`（确认）、`替换/新增文件`（上传）。
- 空态：`还没有文件` + 上传/导入提示；文件数 badge 与列表一致。

**数据页签**

- 复用 `SkillDataPanel`：状态、用量条、初始化脚本、最后初始化、初始化/重建/删除、日志尾部、策略说明。
- 配额条改「声明上限」，取 `metadata.data.max-bytes`（`declaredMaxBytes`，缺省 2 GiB、硬顶 5 GiB），标注“声明值，非沙箱配额”；增「上次同步」= `observedAt`；状态映射与列表一致。

**版本页签**

- 列表项：`vN`、当前标记、变更说明、作者、时间、正文长度；操作：`查看`（打开只读弹层或编辑器对比）、`回滚`（确认，带 revision）。
- Nacos 源：显示空态「当前来源不提供版本时间线」。

**绑定页签**

- 智能体列表（名称、描述、跳转智能体页）；空态 + 「去智能体页」。
- 头部提示「删除技能前需先解绑」。

### 5.3 编辑器页（`/skills/:id/edit`、`/skills/new/editor`）

**头部**

- 返回（回工作台/列表）、`h2 编辑正文`、副标题 `<id> · 基于 vN 编辑 · 草稿保存于 12:04`（无草稿则不显示）。
- 右侧分段：`源码 / 预览 / 对比 vN`（Nacos 无版本时隐藏「对比」）；`取消`、`保存为 vN+1`（创建时为 `保存`）。

**冲突条**（仅 409）

```
<作者> 在 <时间> 把该技能更新到了 v8。你的修改基于 v7，直接保存会覆盖对方的改动。
[查看差异] [重新加载] [另存为 .md]
```

- 「查看差异」：拉取 v8 正文，展示 draft vs v8 的统一差异。
- 「重新加载」：丢弃本地修改并载入最新（二次确认）。
- 「另存为 .md」：下载草稿。
- 不提供强制覆盖（D5）。

**三栏**

1. 文件树（196）：`SKILL.md` + 文件分组；当前文件高亮；修改过的文件标记；创建模式只显示 `SKILL.md` 并提示「保存后可添加文件」。
2. 编辑区（1fr）：
   - 文件标签 + 草稿状态。
   - 行号栏（`aria-hidden`）+ 文本域；行号与滚动同步；Tab 插入两个空格（可配置），Ctrl/Cmd+S 保存。
   - 预览：`marked` 渲染 + `DOMPurify` 清洗；禁止 `script/iframe/object/embed/img`，代码块/表格有样式。
   - 对比：`DiffView` 渲染当前内容 vs 基线（正文=打开的版本；文件=最近保存内容），显示 `+N -M`。
   - 保存条：字符数、检查摘要（`检查全部通过` / `N 项待改`）、取消、保存。
3. 检查栏（288）：
   - `检查`（复用 `rules.js` 的 5 项，实时）。
   - `变更说明`（正文保存时写进版本；文件保存不产生版本，隐藏该项）。
   - `基本信息`：ID（创建时可填，编辑时只读）、说明（限长）、执行开关。
   - `最近保存`：作者、时间、版本（替代原型的「谁在编辑」）。

**行为**

- 正文保存：`PUT /api/admin/skills/{id}`，带 `revision` + `changeNote`；成功后版本 +1，基线更新，提示「已保存为 v8」。
- 文件保存：`PUT /api/admin/skills/{id}/files/{path}`，带 `revision`；不产生版本，刷新文件树。
- 新建：`POST /api/admin/skills`；成功后跳 `/skills/{id}/overview`。
- 草稿：每 2s 或失焦写 `localStorage('skill.draft.<id|new>')`；重开时若草稿晚于 `updatedAt`，提示「恢复草稿 / 丢弃」。
- 离开守卫：脏数据时 `onBeforeRouteLeave` + `beforeunload` 二次确认；保存中禁止离开。
- 错误：409 进入冲突条；413/长度超限/校验失败就地提示；网络错误保留草稿。

### 5.4 新建页

- 头部 `h1 新建技能` + 副标题 `先选起点，之后都能改：正文、文件、数据、绑定都在工作台里。`；右上 `取消`。
- 三步条：`1 选择起点`（当前）/ `2 填写与检查` / `3 完成`。
- 三张路径卡：
  1. `从空白创建`：约 1 分钟；可稍后补文件与数据；按钮「开始填写」。
  2. `从模板创建`（推荐徽标）：带结构的骨架与合格标准；列 4 类模板；按钮「选择模板」。
  3. `导入项目`：Git/ZIP 完整技能包；预检文件树与覆盖差异；按钮「开始导入」。
- `最近使用的模板` 面板：当前用户最近 3 个（名称、使用次数/时间、`使用` 按钮）；链接「管理模板」（本期跳模板列表对话框，只读）。
- 提示条：内置技能可覆盖、不改原始内置内容。
- 模板选择：弹窗列出模板（名称/说明/分类/正文字数/使用次数），选择后进入新建编辑器，ID 为空、正文预填、说明预填；保存后写 `skill_creation_origin`。

### 5.5 导入页

- 面包屑：`← 新建技能 / 导入项目`。
- 头部：`h1 检查导入内容`，副标题 `预检不会写入任何数据，确认后再导入。`
- 三步条：`来源` → `预检`（当前）→ `确认导入`。
- 第一步「来源」：
  - Git：地址、分支/标签、技能 ID（可选）、覆盖开关；ZIP：文件选择、技能 ID（可选）、覆盖开关。
  - 校验 URL/后缀；`下一步：预检` 触发 dry-run。
- 第二步「预检」：
  - `来源` 卡：Git/ZIP 图标、`Git 仓库 · main` 或文件名、解析出的 commit SHA、耗时（ms→`0.8s`）、`修改` 链接。
  - `将写入的文件`：总数与总字节；按目录分组；每行 `新增 / 覆盖 / 不变` 徽标；同名技能存在时显示警告条「当前 vN，导入会生成 v(N+1) 并覆盖 M 个文件」。
  - 右栏 `导入摘要`：ID、说明、新增/覆盖/不变数量、setup 命令、数据上限、`确认导入并生成 vN+1`、`返回修改来源`。
  - `预检结果`：SKILL.md 结构合法、脚本后缀/大小合规、未发现敏感文件、setup 联网提示；失败项红色并可回到来源。
- 第三步「确认导入」：提交 dry-run=false；进度态（导入中不可重复提交）；成功进工作台概览并提示版本；失败保留预检结果并显示原因；409（同名且 revision 过期）提示重新预检。
- 空/错/超限（ZIP 2k 条目、64MB 解压、敏感文件）沿用现有规则。

---

## 6. 后端契约

> 所有新接口沿用 `@RequirePermission(Permissions.SKILL_MANAGE)`；读接口不写审计，写接口写审计。错误沿用 `PayrollException` / `PayrollConflictException`（409）。

### 6.1 V24 迁移（只加表，不改列）

`payroll-common/src/main/resources/db/migration/common/V24__skill_console_parity.sql`

```sql
-- 技能数据状态的读模型缓存：列表/概览只读此表，数据页实时查询后回写。
-- 状态值沿用 SandboxGateway.SkillData：UNKNOWN/INITIALIZING/READY/FAILED/BUSY/UNAVAILABLE/INVALID；
-- NOT_REQUIRED 不落库（由 needsData=false 派生），避免与声明变更后不同步。
-- 不存 quota：SandboxGateway.SkillData 没有配额字段，界面展示的「声明上限」取自 metadata.data.max-bytes。
CREATE TABLE IF NOT EXISTS skill_data_state (
    skill_id     VARCHAR(64) PRIMARY KEY,
    status       VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    size_bytes   BIGINT NOT NULL DEFAULT 0,
    exit_code    INT,
    started_at   BIGINT NOT NULL DEFAULT 0,
    finished_at  BIGINT NOT NULL DEFAULT 0,
    message      VARCHAR(512) NOT NULL DEFAULT '',
    observed_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_skill_data_status (status, observed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 模板创建来源：支撑「最近使用的模板」，同时留下可追溯的模板血缘。
CREATE TABLE IF NOT EXISTS skill_creation_origin (
    skill_id     VARCHAR(64) PRIMARY KEY,
    created_from VARCHAR(64) NOT NULL,
    created_by   VARCHAR(128),
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_creation_origin_template (created_from, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

> 不写 `ALTER TABLE`：H2/MySQL 对 `ADD COLUMN IF NOT EXISTS` 支持不一致，迁移重放时容易在真库报错。新能力一律用新表承载。

### 6.2 DTO 变更

| 类型 | 变更 | 说明 |
| --- | --- | --- |
| `SkillSummary` | 追加 `Integer versionNo`、`String updatedBy`、`Boolean needsData`；`fileCount` 沿用真实 `resource_count`（`db` 源本就有值） | 三态字段一律可空，`null` 表示源无此信息；保留旧 9 参构造器委托 null，减少测试改动 |
| `SkillDetail` | 追加 `String createdBy`、`String updatedBy`、`Boolean needsData`（`createdAt/updatedAt` 已在） | 与 DB 列对齐；Nacos 源为 null |
| `SkillVersion` | 不变 | 作者显示名由返回体的 `users` 字典解析 |
| 新增 `SkillDataState` | `skillId,status,sizeBytes,exitCode,startedAt,finishedAt,message,observedAt`（**无 quota**） | 读模型；`NOT_REQUIRED` 由 `needsData=false` 派生 |
| 新增 `SkillBinding` | `agentId,name,description` | 不造「在线」 |
| 新增 `SkillOverview` | `identity,health,checklist,timeline,users` | 见 6.3 |
| 新增 `SkillTemplate` | `id,title,description,category,bodyLength,usedCount,lastUsedAt` | 代码内置 |
| `SkillImportService.ImportResult` | **保持 `files: List<Summary>` 不变**，仅追加 `source("git"\|"zip")`；逐文件状态、耗时、`currentVersionNo` 由 Controller 用 `resourceHashes` + `detail` 组装 | 避免破坏 `importMeta`（`Summary::size`） |

`needsData` 的判定（与 `SkillInitRequirements.setupPath(declared, files)` 同口径）：

```
db 源：needsData = meta_json.setup 非空
                || 资源存在 setup.sh 或 scripts/setup.sh（fileSettings.enabled() 时）
nacos 源：needsData = null（列表阶段拿不到 meta 与资源，显示「数据未知」）
```

列表批量计算（单页 ≤20 行）：`SELECT skill_id, meta_json FROM skill_version WHERE id IN (…)` + `SELECT version_id, path FROM skill_resource WHERE version_id IN (…) AND path IN ('setup.sh','scripts/setup.sh')`，不在行上逐条查询；`NacosSkillRegistry` 返回 `versionNo=null/updatedBy=null/needsData=null`，UI 统一显示「—」。

### 6.3 接口清单

#### 6.3.1 `GET /api/admin/skills`（扩展）

查询参数新增：`dataStatus=all|ready|attention|none`；`view` 新增 `attention`、`unbound`。

响应（示例，字段顺序无关）：

```json
{
  "writable": true,
  "location": "db",
  "sandboxEnabled": true,
  "total": 24,
  "page": 1,
  "size": 20,
  "stats": {
    "totalSkills": 24,
    "usable": 21,
    "needsAttention": 2,
    "unbound": 3,
    "unknownData": 0,
    "boundAgents": 9,
    "boundSkills": 21,
    "contentBytes": 48210,
    "catalogBuildMillis": 12,
    "cacheHitRatio": 0.93,
    "views": { "all": 24, "custom": 16, "builtin": 8, "overridden": 3, "bound": 21, "attention": 2, "unbound": 3 }
  },
  "skills": [{
    "id": "poetry-notes",
    "description": "诗词创作规范：平仄、押韵与意象",
    "source": "db",
    "builtin": false,
    "overridden": false,
    "updatedAt": 1763971200000,
    "updatedBy": "wangmin",
    "versionNo": 7,
    "contentLength": 214,
    "fileCount": 3,
    "revision": "12",
    "needsData": true
  }],
  "bindings": {
    "poetry-notes": [
      { "agentId": "poetry-assistant", "name": "诗词助手", "description": "主入口" }
    ]
  },
  "dataStates": {
    "poetry-notes": {
      "skillId": "poetry-notes", "status": "READY", "sizeBytes": 2576980377,
      "declaredMaxBytes": 5368709120, "exitCode": 0, "startedAt": 1763950000000,
      "finishedAt": 1763950180000, "message": "", "observedAt": 1763950180000
    }
  },
  "users": { "wangmin": "王敏", "lizhe": "李哲" }
}
```

统计口径（全部基于 `all`，不是当前页）：

```
usable        = READY + NOT_REQUIRED
needsAttention= needsData 且 status ∈ {UNKNOWN, FAILED, INVALID, UNAVAILABLE}
initializing  = status ∈ {INITIALIZING, BUSY}（不计入 usable/attention，单独显示）
unknownData   = !sandboxEnabled 且 needsData（不计入 usable/attention）
unbound       = totalSkills - boundSkills
```

`dataStatus` 过滤：`ready`=usable、`attention`=needsAttention、`none`=!needsData。沙箱未启用时 `dataStatus=attention` 返回空并在响应头/字段标注 `sandboxEnabled=false`。

`bindings` 来自 `AgentRegistry`，按技能聚合；`users` 只包含本页出现的 `updatedBy`。

#### 6.3.2 `GET /api/admin/skills/{id}/overview`（新）

```json
{
  "id": "poetry-notes",
  "identity": {
    "source": "db", "writable": true, "versionNo": 7, "contentHash": "ab12…",
    "createdBy": "wangmin", "createdAt": 1763000000000,
    "updatedBy": "lizhe", "updatedAt": 1763971200000
  },
  "health": {
    "data": { "needsData": true, "status": "READY", "sizeBytes": 2576980377, "declaredMaxBytes": 5368709120,
              "startedAt": 1763950000000, "finishedAt": 1763950180000, "message": "" },
    "scripts": { "executionEnabled": true, "count": 3, "setupCommand": "bash scripts/setup.sh" },
    "bindings": { "count": 2, "agents": [{ "agentId": "poetry-assistant", "name": "诗词助手", "description": "主入口" }] }
  },
  "checklist": [
    { "key": "body", "label": "正文通过质量检查", "ok": true, "detail": "v7", "action": "body" },
    { "key": "files", "label": "已添加参考文件", "ok": true, "detail": "2 个", "action": "files" },
    { "key": "data", "label": "数据已就绪", "ok": true, "detail": "2.4 / 5 GiB", "action": "data" },
    { "key": "binding", "label": "已绑定至少 1 个智能体", "ok": true, "detail": "2 个", "action": "bindings" }
  ],
  "timeline": [
    { "versionNo": 7, "changeNote": "补充押韵判定规则", "createdBy": "wangmin", "createdAt": 1763971200000, "current": true }
  ],
  "users": { "wangmin": "王敏", "lizhe": "李哲" }
}
```

- **overview 不实时调用沙箱**：`health.data` 读 `skill_data_state`；数据页签再实时查询并回写。
- `checklist.body` 用 `SkillContentRules` 判定；`detail` 给版本号或字数。
- `timeline` 最多 5 条；失败不阻断整个概览（对应字段返回空并带 `warnings`）。

#### 6.3.3 `GET /api/admin/skills/{id}/bindings`（新）

```json
{ "id": "poetry-notes", "bindings": [{ "agentId": "poetry-assistant", "name": "诗词助手", "description": "主入口" }] }
```

#### 6.3.4 `GET /api/admin/skills/{id}/versions/{versionNo}`（新）

```json
{
  "id": "poetry-notes",
  "versionNo": 7,
  "content": "# …",
  "changeNote": "补充押韵判定规则",
  "createdBy": "wangmin",
  "createdAt": 1763971200000,
  "contentLength": 214,
  "current": true,
  "files": [{ "path": "references/格律入门.md", "size": 12288, "kind": "text" }]
}
```

- 只支持 db 源；Nacos/内置无版本行 → `404 PayrollNotFoundException`（响应体沿用 `{status,message}`，无自定义错误码）；前端按 HTTP 状态隐藏「对比」。
- 读取历史版本正文只读，不写审计。

#### 6.3.5 模板接口（D3）

`GET /api/admin/skills/templates`

```json
{
  "templates": [
    { "id": "policy-note", "title": "政策口径说明", "description": "把政策原文转成可执行口径", "category": "说明", "bodyLength": 820, "usedCount": 4, "lastUsedAt": 1763900000000 }
  ],
  "recent": [
    { "id": "process-flow", "title": "处理流程与输出格式", "description": "…", "category": "流程", "bodyLength": 760, "usedCount": 2, "lastUsedAt": 1763800000000 }
  ]
}
```

- 模板来自代码资源 `payroll-common/src/main/resources/skill-templates/*.md` + `SkillTemplates` 注册表（4 个：政策口径、处理流程、写作规范、数据分析）；不写库、不可编辑。
- `recent` 为当前操作者最近 3 条（`skill_creation_origin.created_by` + `created_at`）。

`POST /api/admin/skills/from-template`

```json
{ "templateId": "policy-note", "id": "my-policy", "description": "…", "executionEnabled": false }
```

- 校验 ID/长度/重复（409）；复制模板正文；写 `skill_creation_origin`；审计 `skill.create` 携带 `templateId`；返回 `SkillSummary`。

#### 6.3.6 导入预览扩展（D7）

`POST /api/admin/skills/import/git`、`/import/zip`（`dryRun=true` 时零写入）：

```json
{
  "id": "poetry-skills",
  "commitSha": "0f1c…",
  "description": "诗词创作技能包",
  "metadata": {},
  "source": { "kind": "git", "ref": "main", "resolvedSha": "0f1c…" },
  "elapsedMillis": 812,
  "files": [
    { "path": "SKILL.md", "size": 4096, "kind": "text", "sha256": "…", "status": "REPLACED" },
    { "path": "references/格律入门.md", "size": 12288, "kind": "text", "sha256": "…", "status": "ADDED" },
    { "path": "scripts/setup.sh", "size": 1228, "kind": "script", "sha256": "…", "status": "UNCHANGED" }
  ],
  "totals": { "files": 5, "bytes": 31744, "added": 3, "replaced": 2, "unchanged": 0 },
  "setupCommand": "bash scripts/setup.sh",
  "dataMaxBytes": 2147483648,
  "warnings": [],
  "existing": true,
  "currentVersionNo": 7,
  "dryRun": true
}
```

- 逐文件状态：与当前版本 `skill_resource.sha256` 比对；新技能全部 `ADDED`；无历史或无法读取时 `UNKNOWN`（前端显示 `—`，不当作新增）。
- `elapsedMillis` 覆盖克隆/解压 + 解析；`resolvedSha` Git 取 `ObjectId`，ZIP 为 `null`。
- 真实导入（`dryRun=false`）沿用 revision/审计/失败回滚；响应同结构，`dryRun=false`。

#### 6.3.7 数据状态写回

- `GET /{id}/data`：实时查沙箱后 `SkillDataStateStore.upsert`（best-effort；失败只记 warn，不影响响应）。
- `POST /{id}/data/init`、`DELETE /{id}/data`：提交/删除后同样 upsert。
- 列表与概览只读 `skill_data_state`；**列表请求绝不调用沙箱**。
- upsert 用 `INSERT … ON DUPLICATE KEY UPDATE`（H2 MODE=MySQL 兼容），字段与 6.1 一致。

#### 6.3.8 已有接口保持

`GET /{id}`、`GET /{id}/versions`、`POST /{id}/versions/{n}/rollback`、`PUT/DELETE /{id}/files*`、`POST /{id}`、`PUT /{id}`、`DELETE /{id}` 的路径与语义不变；只追加字段：
- `POST /{id}` 仍只接受 `{id,description,content,executionEnabled}`；模板创建走独立接口。
- `GET /{id}/versions`、`GET /{id}` 响应追加 `users` 字典（可选）。
- `PUT /{id}` 继续要求 `revision` + 可选 `changeNote`；409 行为不变。

### 6.4 服务与存储

- 新增 `SkillDataStateStore`（端口）+ `JdbcSkillDataStateStore`（实现），方法：`loadAll()`、`find(id)`、`upsert(state)`、`delete(id)`。
- `JdbcSkillStore.list()` 扩展查询；`summary(row)` 填新增字段；`NacosSkillRegistry` 补默认值。
- `SkillTemplates` + 资源文件；`SkillTemplateService` 负责列表/最近/克隆（复用 `SkillStore.create`）。
- `SkillImportService` 计算逐文件状态；`PreviewFile` 用 `SkillManifests.contentHash` 与资源行比对。
- 不给 `SkillStore` 增加「沙箱」依赖；数据状态由 web 层编排，保持 common 层纯净。

### 6.5 并发、权限、安全

- 所有写接口必须带 `revision`（导入/文件/回滚/删除）；409 统一由 `PayrollConflictException` 映射，前端按状态码走冲突条。
- 模板 ID 与技能 ID 同规则；模板正文为代码资源，创建时重新校验 `SkillContentRules`。
- 导入 dry-run 不落库、不写审计；真实导入写审计；文件编辑写 `skill.file.write`（沿用现有）。
- 数据状态缓存写入不写审计（读缓存，非业务变更）。
- 不新增外部网络调用；Git 导入白名单、ZIP 解压限制、脚本路径校验保持原状。

---

## 7. 前端实现要点

### 7.1 数据流

- 列表：`GET /skills` 一次拿齐 `skills/stats/bindings/dataStates/users`，避免 N+1。
- 工作台：`GET /{id}` + `GET /{id}/overview` 并行；数据页签懒加载 `GET /{id}/data`；版本/绑定页签懒加载。
- 编辑器：`GET /{id}` + `GET /{id}/versions/{n}`（基线）+ `GET /{id}/files/{path}`（按需）。
- 导入：`dryRun=true` → 用户确认 → `dryRun=false`；两次请求携带同一表单快照。
- 所有请求复用 `api()`，错误 toast 与就地提示分开：可恢复错误（409/422）就地，网络错误 toast。

### 7.2 共享件与规则

- `SkillStatusPill`：状态 → 文案/色调/tooltip 的唯一映射；列表、概览、数据页共用。
- `AgentAvatars`：最多 3 个，颜色按索引；`+N` 用中性底。
- `SegmentedTabs`：`role=tablist`、键盘导航、`aria-controls`。
- `rules.js`：补充 `dataStatusMeta`、`formatRelative`、`displayUser`、`diffSummary`，与后端枚举共用常量（写在注释里，避免漂移）。
- `SkillTile`：首字取描述首字（无描述取 ID 首字母大写），颜色哈希稳定。

### 7.3 依赖（D11）

`package.json` 追加（lockfile 锁定，不引入传递依赖过多的包）：

- `marked`（≥15）：Markdown → HTML。
- `dompurify`（≥3）：清洗；配置禁止 `script/iframe/object/embed/img`，链接加 `rel="noopener noreferrer"`。
- `diff`（≥7）：行级 diff；`DiffView` 以文本渲染，禁止 `v-html`。

体积预算：新增 gzip 后 ≤ 45KB（编辑页懒加载路由，不影响列表首屏）。

### 7.4 可访问性

- 页签、菜单、步骤条、弹窗的 ARIA 与键盘行为（←/→/Home/End、Esc 关闭并归还焦点、Tab 不逃逸）。
- 路由切换后焦点落到 `h1`（`tabindex=-1`），并播报页面标题。
- 保存状态用 `role=status`；冲突条 `role=alert`。
- 状态不只靠颜色：点 + 文案；危险操作有文字说明。
- 辅助文字 11.5–12px 且对比 ≥4.5:1（`--muted #6b7484`）。
- `prefers-reduced-motion` 下关闭位移与淡入。

### 7.5 状态与异常

- 每屏：加载（骨架/局部 spinner）、空态、错误（重试）、只读（禁用 + 提示）、Nacos 降级（隐藏版本/回滚/对比）。
- 409：编辑器冲突条；其他写操作 toast + 刷新最新数据。
- 沙箱未启用：列表「沙箱未启用」，数据页禁用初始化并给出环境变量提示。
- 删除：有绑定禁用并给原因；无绑定需输入 ID。

---

## 8. 批次与工作量

> 估算按 1 名熟悉代码库的前端 + 1 名后端并行；总关键路径约 **14–16 人日**（依 §15 复核上调：R0 增批量查询/刷新器、R3 增逐文件预览与模板门禁）。

### R0 后端契约与迁移（3 人日）

**文件**

- `payroll-common/src/main/resources/db/migration/common/V24__skill_console_parity.sql`
- `payroll-common/.../skill/SkillSummary.java`、`SkillDetail.java`、`SkillDataState.java`、`SkillDataStateStore.java`、`JdbcSkillDataStateStore.java`、`SkillBinding.java`、`SkillOverview.java`、`SkillTemplate.java`、`SkillTemplates.java`
- `payroll-common/src/main/resources/skill-templates/*.md`（4 个）
- `payroll-common/.../skill/JdbcSkillStore.java`、`NacosSkillRegistry.java`、`SkillImportService.java`
- `payroll-backend/.../web/SkillAdminController.java`

**测试**

- `SkillAdminControllerTest`：list 新增字段/统计/过滤、overview、bindings、version content 404、templates/from-template、导入预览状态。
- `JdbcSkillStoreTest`：versionNo/updatedBy/needsData/fileCount。
- `SkillImportServiceTest`：`source` 字段、`files` 保持 `Summary`；预览状态（ADDED/REPLACED/UNCHANGED/UNKNOWN）、totals、耗时由 `SkillAdminControllerTest` 覆盖。
- 新增 `SkillDataStateStoreTest`：upsert/loadAll/delete、坏数据降级。

**DoD**

- `mvn -o -q -DskipTests package` 通过。
- 定向测试全绿；列表响应 golden JSON 与 6.3.1 一致。
- 列表请求无沙箱调用（用 gateway spy 断言 0 次）。

### R1 外壳、令牌与列表（2 人日）

**文件**

- `frontend/src/styles.css`：`--primary/-dark/-soft` 新值；`.skills-canvas` 与技能列表样式（不动全局 `.page`）。
- `SkillsView.vue`、`SkillsListView.vue`、`components/ui/SegmentedTabs.vue`、`PanelCard.vue`、`skills/SkillTile.vue`、`SkillStatusPill.vue`、`AgentAvatars.vue`。

**测试**

- Playwright：汇总行、chips、搜索 `/` 聚焦、排序、数据状态过滤、列设置持久化、分页、失败行「查看原因」、空态、只读。
- 视觉：`skills-list.png` 重拍（1440/1280）。

**DoD**

- 列表与原型结构一致；所有数字可由 mock 响应驱动；无硬编码演示值。
- `npm run build` + `npm run test` 通过。

### R2 工作台（3 人日）

**文件**

- `SkillWorkbench.vue`、`tabs/*`、`SkillDataPanel.vue`、`AgentAvatars.vue`（复用）、路由 `router/index.js`（tabs + 重定向）。
- `useSkillOverview` / `useSkillBindings`（小型 composable，可选）。

**测试**

- Playwright：6 页签切换与 URL 同步、健康卡、checklist、时间线、引用方、标识、危险区禁用原因、数据页状态、版本回滚（mock）、绑定空态、Nacos 降级。
- 视觉：`skills-overview / files / data / versions / bindings` 基线。

**DoD**

- 刷新任意页签可恢复；删除需输入 ID；409 不吞错。
- 后端无新增接口缺口（overview 不调沙箱）。

### R3 编辑器、新建、导入（4–5 人日）

**文件**

- `SkillEditorPage.vue`、`SkillCreatePage.vue`、`SkillImportPage.vue`、`SkillTemplatePicker.vue`、`MarkdownPreview.vue`、`DiffView.vue`、`router/index.js`、`package.json`。
- 删除 `SkillEditor.vue`、`SkillCreateChooser.vue`、`SkillImportDialog.vue`（在测试迁移完成后）。

**测试**

- Playwright：新建三卡 → 空白编辑保存 → 概览；模板选择 → 预填 → 保存；导入三步 + 逐文件徽标 + 确认 → 新版本；编辑器源码/预览/对比；409 冲突条三出口；脏数据离开守卫；草稿恢复；Markdown XSS 用例（`<img onerror>`、`<script>` 被清洗）。
- 视觉：`skills-editor / editor-preview / create / import` 基线。
- 单元：`displayUser`、`diffSummary`、`dataStatusMeta`、草稿存取。

**DoD**

- 正文保存生成新版本；文件保存不生成版本；冲突不提供强制覆盖。
- `npm run build`、vitest、Playwright 全绿；新增依赖 gzip 预算达标。

### R4 验收与收口（2 人日）

- 重跑全量：后端定向/全量相关测试；前端 build/unit/Playwright。
- 视觉基线全量重拍（含 20 条既有路由，确认 `--primary` 调整无回归）。
- 可访问性手工检查（键盘、焦点、对比、200% 缩放）。
- 文档回写；清理旧组件、旧测试、旧截图。

---

## 9. 验收清单

### 9.1 结构断言（可自动化）

| 项 | 断言 |
| --- | --- |
| 画布 | 1440 视口下内容最大宽 1280；左右留白 ≥80 |
| 列表 | th `9px 14px`、td `11px 14px`；6 列顺序与原型一致；表头 sticky |
| 工作台 | 页签 6 个；主栏/右栏 `1fr 300px`；健康卡 3 列 |
| 编辑器 | 三栏 `196 / 1fr / 288`（±1）；行号列固定宽；保存条贴底 |
| 新建 | 路径卡 3 列；1080 时 2 列；820 时 1 列 |
| 导入 | `1fr 308px`；步骤条 3 步；文件树徽标 3 种 |

### 9.2 数据真实性

- 汇总行数字与 `stats` 完全一致；切换筛选后「需处理」链接生效。
- 绑定头像数与 `bindings` 长度一致；点击进绑定页签。
- 版本号来自 `versionNo`；Nacos 显示 `—` 且无回滚。
- 数据状态与 `GET /{id}/data` 实时结果一致（数据页打开后回写，再回列表仍一致）。
- 导入预览的「新增/覆盖/不变」与数据库最终 `skill_resource` 变化一致。

### 9.3 行为

1. 新建空白 → 保存 → 概览 → 列表出现 → 版本 v1。
2. 模板创建 → 正文预填 → 保存 → `skill_creation_origin` 有记录 → 新建页「最近模板」出现。
3. 导入 dry-run 前后 `skill_definition` 行数不变；确认后版本 +1，覆盖文件哈希变化，未变文件哈希不变。
4. 编辑器保存时他人先保存 → 409 冲突条 → 查看差异/重新加载/另存为；无强制覆盖。
5. 有绑定删除被拦截并显示智能体名；解绑后可删（输入 ID）。
6. 数据初始化中 → 列表显示「初始化中」，完成后「数据就绪」；未开页也在 ≤10s 内由刷新器收敛，终态后刷新器不再访问沙箱。
7. 沙箱关闭 → 列表显示「沙箱未启用」，不计数为可用/需处理，数据页禁用。
8. Nacos 源：文件数/版本/更新人/数据态均显示 `—`，不出现「无需数据」「文件包为空」等误导文案。
9. 新建模板三篇均能保存成功（正文过 `SkillQuality` 零 error），空正文模板不可发布。

### 9.4 回归与质量门

- 后端：`mvn -o -q -DskipTests package`；`SkillAdminControllerTest`、`JdbcSkillStoreTest`、`SkillImportServiceTest`、`SkillDataStateStoreTest` 全绿。
- 前端：`npm run build`、`npm run test`、Playwright 全量（login + skills + visual）。
- 视觉：23 张既有基线保持不变（配色作用域在 `.skills-canvas`）+ 新增技能页基线；`maxDiffPixelRatio: 0.001`；仅允许时钟类差异。
- 可访问性：键盘走通五屏；无焦点陷阱；冲突/保存状态可播报；对比度抽检通过。
- 性能：200 技能列表 P95 ≤ 300ms；列表接口沙箱调用 0；概览接口沙箱调用 0。

---

## 10. 偏离清单（有意为之）

| # | 原型 | 本文 | 理由 | 若要 1:1 |
| --- | --- | --- | --- | --- |
| 1 | 56px 图标 rail | 保留 232px 侧栏（D1-B） | 已有跨模块导航与权限；rail 需重做 IA | 独立「全局外壳」项目 |
| 2 | 标题为中文展示名 + mono ID | 标题为 ID，副行为描述 | 数据模型只有 ID 与描述，`name` 列等于 ID | 新增 `display_name` 列、编辑字段、导入映射（跨层改造） |
| 3 | 引用方显示「在线 / 主入口」 | 显示智能体名称 + 描述 | 系统没有在线态 | 新增 presence/健康度服务 |
| 4 | 「谁在编辑」presence | 「最近保存」 | 同上 | 同上 |
| 5 | 冲突条「覆盖为 v9」 | 不提供 | 绕过 revision、丢对方改动 | 显式 force-save + 审计（不建议） |
| 6 | 「在对话中试用」 | 隐藏，P2 | 需要临时智能体/会话生命周期 | 单独能力 |
| 7 | checklist 含「试用一次」 | 改为数据检查项 | 与 #6 一致 | 同 #6 |
| 8 | `--muted #7e8798` | `#6b7484` | 对比度 4.7:1 | 降低可读性，不建议 |
| 9 | 删除一个按钮 | 输入 ID 确认 | 不可恢复操作的安全基线 | 降低误删保护 |
| 10 | 来源/文件数在表中 | 默认隐藏，列设置可选 | 与原型列顺序一致，同时保留信息 | 默认显示 |
| 11 | 每页 20 | 20（仅技能列表） | 与原型一致；后端默认 50 保留给其它调用 | — |
| 12 | 无「沙箱未启用」状态 | 增加该状态 | 本地/降级环境真实存在 | 无 |
| 13 | 「数据状态」排序按钮 | 改为筛选下拉 | 原型未明确排序语义；筛选更有用 | 服务端按状态排序 |
| 14 | 模板 3 类示例 | 4 个内置模板 | 覆盖政策/流程/写作/分析 | 内容可调 |

---

## 11. 风险与回退

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 数据状态缓存与沙箱不一致 | 列表状态滞后 | 数据页实时查并回写；显示「上次同步」；初始化/删除后立即 upsert |
| 列表新增 JOIN 变慢 | 规模化后列表延迟 | 单查询 + 索引 `current_version_id`；200 技能基准；必要时物化 `needs_data` |
| 模板克隆产生重复内容 | 技能膨胀 | 记录 `created_from`；模板只读；列表按来源/时间可查 |
| 路由改动破坏书签 | 旧链接 404 | `references/scripts` 重定向；保留 `body`；`new/import` ID 保留 |
| 编辑器页面丢失弹窗焦点管理 | 键盘用户迷路 | 路由后聚焦标题；离开守卫；Tab 顺序测试 |
| Markdown XSS | 脚本执行 | DOMPurify 严格配置 + 用例；禁止 img/iframe |
| 409 处理不当 | 覆盖他人改动 | 服务端 revision 强制；前端只在显式「重新加载」后换基线 |
| 视觉基线大面积变化 | 回归噪音 | R1–R3 分批重拍；`--primary` 单独提交；全量 20 路由截图对比 |
| V24 迁移重放 | 启动失败 | 只建表 + `IF NOT EXISTS`；不做 ALTER；H2/MySQL 双跑测试 |

**回退**

- R0 只加表/加字段，旧前端仍可用；后端可单独上线。
- 前端路由切换前保留旧组件，R4 再删；出问题按路由回退（`/body` 仍可用）。
- 数据状态写入失败仅降级为 `UNKNOWN`，不影响列表与工作台主流程。

---

## 12. 文档回写与交接

实施完成后必须回写：

1. `docs/skill-console-ui-redesign.md` §11.1：批次证据、测试数、截图清单；补充 V24 与模板说明。
2. `docs/skill-console-ux-spec.md`：将 F8（工作台为页面）、S 系列页签、P0/P1/P2 状态与本文对齐；标注 D9/D10 的降级。
3. `docs/frontend-ia.md`：技能路由表（new/import/edit/tabs）与重定向。
4. README/交接：V24 迁移说明、模板资源位置、数据状态缓存语义、列表不再调用沙箱的约束。
5. 本文末尾追加「实施结果」表（每批 commit/测试/截图路径），未完成项不得标记完成。

---

## 13. 可执行性自检

| 检查 | 结论 |
| --- | --- |
| 每条差距是否有证据路径/行号 | 是（§2.1） |
| 每个新接口是否有请求/响应与错误语义 | 是（§6.3） |
| 每个新字段是否有来源与兜底 | 是（`versionNo/updatedBy/needsData`、数据状态、绑定） |
| 迁移是否可重放 | 是（只建表，无 ALTER） |
| 是否引入沙箱到列表 | 否（§6.3.7 明确禁止） |
| 是否有不可实现的原型元素被静默忽略 | 否（§10 逐条列出替代） |
| 测试是否覆盖主路径与关键异常 | 是（§9.3/§9.4） |
| 是否给出回退路径 | 是（§11） |
| 是否可在不确认 D3/D9/D10 的情况下先做 R0–R2 | 是（R3 才依赖模板/编辑器决策） |

---

## 14. 待确认（请逐项勾选）

- [ ] D1 范围：五屏画布严格对齐，外壳沿用 B（推荐）
- [ ] D2 编辑器改路由页面（推荐）
- [ ] D3 新建「从模板创建」纳入本期（推荐）
- [ ] D4 参考文件 + 脚本合并为「文件」（推荐）
- [ ] D5 冲突不提供强制覆盖（推荐）
- [ ] D6 列表列与「列设置」（推荐）
- [ ] D7 导入三步骤 + 逐文件状态（推荐）
- [ ] D8 数据状态落库缓存（推荐）
- [ ] D9 「在对话中试用」暂缓 P2（推荐）
- [ ] D10 「谁在编辑」改「最近保存」（推荐）
- [ ] D11 引入 marked/dompurify/diff（推荐）
- [ ] D12 `--primary` 调为 `#2f6feb`（推荐）
- [ ] D13 删除保留输入 ID（推荐）

确认后按 R0 → R1 → R2 → R3 → R4 执行；未确认项默认按推荐值，D3/D9 需在 R3 开始前给出结论。

---

## 15. 架构可行性复核（2026-11-25）

**结论**：五屏严格对齐的方向可行，原型元素均有落点；但本文 §2/§6 有 8 处契约与代码现状不符，必须先改规格再写码，否则实现到一半会返工。修正后总工作量由 12–13 人日上调为 **14–16 人日**（R0 3、R1 2、R2 3、R3 4–5、R4 2）。

### 15.1 必须修正的契约（B 系列）

| # | 问题 | 证据 | 修正 |
| --- | --- | --- | --- |
| B1 | 「列表 fileCount 恒为 0」只对 Nacos 成立 | `JdbcSkillStore.java:1827` 已用 `resource_count`；`NacosSkillRegistry.java:220/236` 传 `0` | 列表「文件」列按 `source` 区分：`db` 显示真实值，`nacos/builtin` 显示「—」；不新增字段 |
| B2 | `needsData=false` 会把 Nacos 带 setup 的技能误判为「无需数据」 | `NacosSkillRegistry` 列表无 meta/资源；`SkillInitRequirements.setupPath` 需要两样输入 | `needsData` 定为 `Boolean` 三态（true/false/null=未知）；DB 源批量算：`SELECT skill_id, meta_json FROM skill_version WHERE id IN (…)` + `SELECT version_id, path FROM skill_resource WHERE version_id IN (…) AND path IN ('setup.sh','scripts/setup.sh')`；Nacos 恒 null → 徽章「数据未知」，统计里单列 `unknownData` |
| B3 | `quota_bytes` 无数据来源 | `SandboxGateway.java:52-60` 的 `SkillData` 无配额字段 | 从 V24 表中删除 `quota_bytes`；界面显示「声明上限」，取 `metadata.data.max-bytes`（默认 2 GiB、硬顶 5 GiB，`SkillProjectParser`），文案标注“声明值，非沙箱配额” |
| B4 | 概览清单无法调用内容规则 | `SkillContentRules` 是包私有且 `requireBody` 抛异常 | 同包新增 public `SkillQuality.review(...)` 返回 `List<Finding>`（rule/severity/message/hint），复用常量与私有校验片段；`requireBody` 行为不变 |
| B5 | `SKILL_VERSION_UNAVAILABLE` 错误码不存在 | `ApiExceptionHandler` 仅映射 404/409/400/503，响应体只有 `{status,message}`；`PayrollNotFoundException` 已存在 | 删掉错误码，改为 404 + message；前端按 HTTP 状态分支（符合“按类型不按文案”的项目约定） |
| B6 | 数据状态永不收敛：`recordFinish` 只写审计 | `SkillAdminController.java:684-698`；全仓库无 `@EnableScheduling/@Scheduled` | 两段式：①数据页每次 GET/init/delete 后 upsert `skill_data_state`（含 `observed_at`）；②`SkillDataStateRefresher` 用 daemon `ScheduledExecutorService`（10s tick，仅 `gateway.enabled()`，单轮 ≤20 条，只处理 INITIALIZING/BUSY 且 `observed_at` 超 10s，终态即停，`@PreDestroy` 关闭）；验收标准改为“状态在 10s 内收敛或打开页面即收敛” |
| B7 | 导入逐文件状态缺哈希来源 | `SkillStore` 无按文件哈希接口；`ImportResult.files` 只有 `Summary(path,size,kind)` | `SkillStore` 增 `Map<String,String> resourceHashes(id)`（DB：`skill_resource` JOIN `current_version_id`；Nacos：由 `fileContent` 现算并缓存在 snapshot）；预览状态在 Controller 侧与新包逐路径比对得出 `added/changed/same/removed`；`ImportResult` 只增 `source` 字段，`importMeta` 继续用 `Summary::size` 不动 |
| B8 | 列表若改 `catalog()` 会波及运行期 | `JdbcSkillStore.java:85-86` `SELECT_CATALOG` 被 `catalog()` 复用（运行时热路径） | 新增独立 `SELECT_SUMMARIES`（`ROW_COLUMNS` + `created_by, updated_by` + LEFT JOIN `skill_version` 取 `version_no`）与新记录 `SummaryRow`；`ROW_COLUMNS`/`readRow`/`catalog()` 一律不动 |

### 15.2 实施风险与对策（R 系列）

| # | 风险 | 对策 |
| --- | --- | --- |
| R1 | `SkillSummary` 有 10 处、`SkillDetail` 有 11 处构造点（含 `SkillAdminControllerTest:1004` 的 9 参 helper） | 记录保留规范构造器，另加旧参数委托构造器（新增字段给 null）；先编译全绿再改逻辑 |
| R2 | `new/import/templates/edit` 是合法技能 id（`SkillIds` 正则），会与静态路由/端点相撞 | 创建/导入时拒绝并返回 400，补两条用例；`GET /templates` 与 `GET /{id}` 由 Spring 字面量优先，无需额外处理 |
| R3 | 改 `:root --primary` 会让 20+ 路由与 23 张基线全部失效 | `body.theme-v2` 不覆盖 primary（`styles.css:13`），故把新色写在 `.skills-canvas` 作用域内；只重拍技能相关截图；若要全局改色，R1 必须列全量重拍清单 |
| R4 | 前端路由改造破坏菜单高亮/菜单元数据 | 高亮是按前缀判断（`AppLayout.vue:60-62`），子路由天然命中；`menuFromRoutes` 只读顶层 `meta.menu`（`workspaces.js:57`），父路由保留 `workspacePage` 产出的 meta 即可；子路由继承 `meta.workspace` |
| R5 | 编辑器行号与 textarea 换行错位 | 编辑区 `white-space: pre; overflow:auto`，行号列按 `content.split('\n').length` 生成，`scrollTop` 双向同步；预览 debounce 150ms |
| R6 | 模板正文过不了内容门禁（≥200 字、含标题、角色关键词） | 模板放 `payroll-backend/src/main/resources/skill-templates/*.md`，单测逐篇调用 `SkillQuality.review` 断言 0 error |
| R7 | 新增依赖体积与 XSS | `marked`/`dompurify`/`diff` 锁版本；预览与 diff 输出统一过 DOMPurify；`npm run build` 断言 gzip 增量 ≤45KB |
| R8 | 列表若逐行查库（bindings/dataStates/users/needsData）会 N+1 | 全部批量：`version_id IN`、`skill_id IN`，单页 ≤20 行、最多 4 条查询；禁止列表触碰沙箱（沿用 §6.3.7） |

### 15.3 明确不改（防范围蔓延）

`catalog()` 与 `ROW_COLUMNS`、运行期装配与缓存键、Nacos 写路径与快照语义、技能正文/资源的上传协议、`DEFAULT_PAGE_SIZE=50`（前端显式传 `size=20`）、其余 20+ 路由的视觉。

### 15.4 规格就地修订点

- §2.1：`fileCount` 结论改为“DB 有值、Nacos 为 0”。
- §6.2：V24 只建两表且不含 `quota_bytes`；`skill_data_state` 增 `observed_at`、`message` 截断 512。
- §6.5：所有新增字段一律可空，JSON 输出 `null`，前端显示「—」。
- §6.3：删除错误码；数据状态增补刷新器语义。
- §8：工时按 15.1/15.2 调整。
- §9：增补刷新收敛、模板门禁、兼容构造器三类用例。
- §10：增补“声明上限≠沙箱配额”“Nacos 数据态未知”两条偏离。

---

## 16. 文件级实施步骤

依赖关系：R0 与 R1 可并行（先冻结 §15.1 契约）；R2 依赖 R0+R1；R3 依赖 R2；R4 收口。

### R0 后端地基（3 人日）

| 步骤 | 文件 | 内容 | 验证 |
| --- | --- | --- | --- |
| 0.1 | `payroll-common/src/main/resources/db/migration/common/V24__skill_console_state.sql` | 建 `skill_data_state(skill_id PK, status, size_bytes, exit_code, message, started_at, finished_at, observed_at)` 与 `skill_creation_origin(skill_id PK, origin, template_id, created_by, created_at)`；仅 `CREATE TABLE IF NOT EXISTS`，索引内联 | H2/MySQL 双跑迁移、重放两次 |
| 0.2 | `JdbcSkillStore.java` | 加 `SELECT_SUMMARIES`、`SummaryRow`、`summaries()`、`resourceHashes(id)`、`requirements(ids)`（批量 setup 判定） | 仓储单测：有/无 setup、fileCount、versionNo、updatedBy |
| 0.3 | `NacosSkillRegistry.java` | `summaries()` 返回 `fileCount=0/versionNo=null/updatedBy=null/needsData=null`；`resourceHashes` 用 `fileContent` 算 sha256 并随 snapshot 缓存 | 单测断言未知态语义 |
| 0.4 | `SkillStore.java` | 接口增三方法（默认实现：`list()` 映射 + 空 map，保证第三方/测试实现可编译） | 编译 + 现有实现全绿 |
| 0.5 | `SkillSummary.java` / `SkillDetail.java` | 扩到 12/14 字段（`versionNo`、`updatedBy`、`needsData`；Detail 另加 `createdBy`），旧参数委托构造器 | `SkillAdminControllerTest`、运行期模块编译通过 |
| 0.6 | `SkillQuality.java`（新）+ `SkillContentRules.java` | 抽出包内静态 `checks(...)`；`SkillQuality.review` 输出 Finding，不抛异常 | 新单测：短正文、缺标题、超长描述、无 setup |
| 0.7 | `SkillDataStateStore.java` + `JdbcSkillDataStateStore.java` + `SkillDataStateRefresher.java`（新） | upsert/read/batch/delete；daemon 单线程刷新器（B6 语义） | 单测：终态停止、网关关闭不跑、超时丢弃 |
| 0.8 | `SkillAdminController.java` | 列表改 `summaries()` + 批量装配；`data`/`binding` 过滤；stats 公式；`/overview`、`/bindings`、`/versions/{n}`、`/templates`、`/from-template`；导入预览 + 状态 upsert | 控制器/集成测试 |
| 0.9 | `SkillImportService.java` | `ImportResult` 增 `source`；其余不动 | 导入用例回归 |

R0 出口：`mvn -o -q -DskipTests package` 通过；`mvn -o -q -pl payroll-tests test -Dtest=Skill*Test` 全绿；`GET /api/admin/skills` 响应含新字段且 Nacos 行为未知态。

### R1 前端地基（2 人日）

1. `frontend/src/styles/` 增 `skills-canvas.css`（作用域令牌 + 1280 画布 + 栅格），`main.js` 引入。
2. `router/index.js`：`platform/skills` 改为布局路由（`SkillsView.vue` 渲染 `<router-view>`），子路由 `''`、`new`、`new/editor`、`import`、`:id/:tab?`、`:id/edit`；tab 别名 `references|scripts → files`。
3. `views/skills/` 新建共享件：`CanvasHead`、`Toolbar`、`StatStrip`、`StatusBadge`、`Pager`、`EmptyState`。
4. 列表页六列 + 列设置 + 过滤 + 统计 + 分页；查询参数与 URL 双向同步，前端固定 `size=20`。
5. 单测更新（navigation/路由解析）；Playwright 列表冒烟。

R1 出口：`npm run build`、`npm run test` 通过；列表五类统计与 §5 文案一致。

### R2 工作台（3 人日）

1. `views/skills/workbench/` 拆概览/文件/数据/版本/绑定五个 pane；页头面包屑 + 六页签。
2. 概览：健康三卡（正文/资源/数据）+ `SkillQuality` 清单 + 版本时间线；无权限项灰显。
3. 文件：树 + 预览 + 行内编辑（R5 方案）。
4. 数据：状态徽章、声明上限、初始化/清空二次确认；非终态时 5s 轮询（仅当前页，不全局）。
5. 版本：时间线仅读，回滚沿用现有 409 契约。
6. 绑定：智能体头像/名称/可见性，空态「未绑定」。

R2 出口：五页签在 DB 源与 Nacos 源各走一遍；数据初始化从 INITIALIZING 到 READY 可观测（刷新器或开页）。

### R3 编辑器/创建/导入（4–5 人日）

1. 装依赖 `marked`、`dompurify`、`diff`；`npm run build` 记录基线体积。
2. `views/skills/editor/` 三栏页（196/1fr/288）：文件菜单、frontmatter 表单、Markdown 预览、行号编辑器、冲突栏（409 + `currentRevision`）、变更说明（仅编辑态）。
3. 新建三卡 + `POST /from-template`；`resources/skill-templates/*.md` 三篇起，单测过 `SkillQuality`。
4. 导入三步骤：地址/文件 → 预览（逐文件 added/changed/same/removed + 耗时 + warnings）→ 写入；`dryRun=true` 复用同一接口。
5. 导入完成跳 `/:id/overview`；旧三个弹窗组件删除。

R3 出口：新建-编辑-预览-保存-回滚全链路；三条模板均可创建；导入预览与落库结果一致。

### R4 验收与回写（2 人日）

1. Playwright 五屏走查 + 截图（只重拍技能相关基线；若 R1 决定全局改色则全量重拍）。
2. 删除死代码（旧列表/弹窗/未用样式）；`grep` 确认无残留引用。
3. 回写 §12 所列 5 份文档；本文件追加“实施结果”表。
4. 收口命令：`mvn -o -q -DskipTests package`、定向 `Skill*Test`、全量 `mvn -o -q test`、`npm run build`、`npm run test`、`npm run test:e2e`。

---

## 17. 下一步

先按 §15.1 就地修订 §2/§6 的 8 处契约（不写码），再启动 R0 的 0.1–0.2（迁移 + `SELECT_SUMMARIES`/`resourceHashes`）；R1 可在契约冻结后并行。未获授权的 commit/push 一律搁置。

---

## 18. 实施结果（2026-11-26）

> 执行口径：按 R0 → R1 → R2 → R3 → R4 连续实施，同一工作树、无并行分支。**本表只记录已验证事实；commit / push 一律未执行，等待授权。**
> 契约以 §15.1 修正后的版本为准；实现与 §16 任务清单的差异逐条记在 §18.4。

### 18.1 批次结果

| 批次 | 状态 | 关键落点 | 验证证据 | 偏差 / 未做 |
| --- | --- | --- | --- | --- |
| R0 读模型与接口 | 完成 | `V24__skill_console_state.sql` 两表；`SkillDataState*`、`SkillCreationOrigin*`、`SkillTemplates`、`SkillQuality`、`SkillVersionContent`；`SkillSummary` / `SkillStore.summaries()` / `resourceHashes()`；控制器新增 `overview`、`bindings`、`templates`、`from-template`、版本正文；列表读缓存不调沙箱 | `mvn -o -q -DskipTests package` 通过；定向 `Skill*Test` 98/0/0（`SkillAdminControllerTest` 45） | 未新增 `SkillBinding`/`SkillOverview`/`SkillTemplate` 独立 DTO，绑定/概览/模板用控制器内 `Map` 组装；迁移文件名为 `state` 而非 §16 草拟的 `parity` |
| R1 路由 / 画布 / 列表 | 完成 | `skills-canvas.css`；`SkillsView.vue` 降为布局路由；`SkillsListView.vue` 六列 + 汇总行 + 列设置 + 筛选 + 分页，查询与 URL 双向同步；`components/ui/*` 共享件 | `npm run build`；vitest 44/44；`skills-smoke` 列表用例 | 外壳沿用 232px 侧栏（D1-B），未采用 56px rail |
| R2 工作台六页签 | 完成 | `SkillWorkbench.vue` + `tabs/Workbench{Overview,Body,Files,Data,Versions,Bindings}.vue`；数据页实时查 + 回写 `skill_data_state`；后台 Refresher 收敛非终态 | `skills-smoke` 深链、页签、数据状态用例 | presence 降级为「最近保存」（D10）；数据初始化不提供取消（P2-5 未做） |
| R3 编辑器 / 新建 / 导入 | 完成 | 三栏 `SkillEditorPage.vue`（196/1fr/288）、`MarkdownPreview`（marked + DOMPurify）、`DiffView`、409 冲突条；`SkillCreatePage` 三路径 + 4 模板 + `POST /from-template`；`SkillImportPage` 三步骤 + 逐文件状态 | 编辑器/新建/导入 e2e；`SkillTemplatesTest`、`SkillQualityTest` | 「在对话中试用」未做（D9）；模板 4 篇（§10 #14）；新增 `marked`/`dompurify`/`diff` 依赖 |
| R4 验收与回写 | 完成 | 31 张视觉基线；`skills-smoke` / `visual-smoke`；V24 引起的迁移/DDL 断言同步；4 份文档回写与本表 | 见 §18.2 | 见 §18.3 未验证项 |

### 18.2 测试与证据

| 项 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| 后端构建 | `mvn -o -q -DskipTests package` | 通过 |
| 后端定向 | `-Dtest=Skill*Test`（含控制器、仓储、导入、质量、模板、数据状态） | 98 / 0 / 0 |
| 后端全量 | `mvn -o test` | 1506 / 0 / 0 / 4；其中 `DdlSyntaxTest.sharedMigrationsApplyOnRealMysql` 因未配置 `PAYROLL_TEST_MYSQL_*` 跳过 |
| 前端构建 | `npm run build` | 通过 |
| 前端单测 | `npm run test` | 44 / 44 |
| 功能 e2e | `npx playwright test tests/e2e/login-smoke.spec.js tests/e2e/skills-smoke.spec.js` | 18 / 18（login 2 + skills 16） |
| 视觉 e2e | `npx playwright test tests/e2e/visual-smoke.spec.js` | 4 / 4（28 条路由 × 1280/1440/1920 截图与横向溢出断言） |
| 视觉基线 | `frontend/tests/e2e/__screenshots__/` | 31 张：登录 + 18 条非技能路由共 19 张哈希未变；技能旧基线 3 张更新、`skills-references.png` 由 `skills-files.png` 取代；新增 9 张 |

**截图清单（技能页 12 张）**：`skills-list`、`skills-overview`、`skills-body`、`skills-files`、`skills-data`、`skills-versions`、`skills-bindings`、`skills-editor`、`skills-editor-preview`、`skills-create`、`skills-import`、`skills-import-preview`。

### 18.3 未验证项与原因

| 项 | 原因 |
| --- | --- |
| 真 MySQL 结构腿 | 未配置 `PAYROLL_TEST_MYSQL_JDBC/_USER/_PASSWORD`，`DdlSyntaxTest.sharedMigrationsApplyOnRealMysql` 按设计跳过；H2(MySQL 模式) 腿已执行 |
| Nacos 源人工走查 | 本机已切 db 源；Nacos 源仅由单测与前端 mock 覆盖，未在真机点击五屏 |
| 沙箱在线初始化的终态 | e2e 用 mock 状态；真实沙箱链路沿用 `docs/skill-sandbox-data-flow.md` 的既有证据，本轮未重跑 |
| 820px 以下布局 | 规格明确不做移动端原生布局 |

### 18.4 与 §16 任务清单的差异

1. **DTO 形态**：未落 `SkillBinding` / `SkillOverview` / `SkillTemplate` 三个独立类，控制器以 `Map` 返回同名字段；接口路径与字段名不变，调用方不受影响。
2. **迁移文件名**：`V24__skill_console_state.sql`（不是 `V24__skill_console_parity.sql`）。
3. **迁移断言同步**：V24 使表数 50→52、历史版本 23→24、`skill_id` 列表增加两张表；`SchemaMigrationTest`、`DdlSyntaxTest`、`MigrationRecoveryTest` 已按新结构更新，并让 V23 故障模拟同时回退 V24 记录以避免 Flyway out-of-order。
4. **P2 项**：`skill:read` 拆分、批量 health、`lastUsedAt` 聚合、数据初始化取消均未做，保持 §16 P2 状态；Markdown 渲染（P2-4）随 R3 提前落地。
5. **提交状态**：R0–R4 全部文件未提交，等待明确授权后再按批次拆分 commit。
