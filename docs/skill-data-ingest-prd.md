# 技能数据注入 PRD（往 data/ 里加数据）

> 状态：v1.0 已实施（2026-11-27），方案 C（独立 `imports/`）落地并完成真机验收。
> **v2.0 已取代 v1.0（2026-11-27，同日）**：按最新需求，上传直接写入 `data/`，与「重建数据」同目录；`imports/` 目录与 `/data/_imports` 挂载已移除；上传不再要求技能声明 setup。下文 §4–§7 为 v1.0 决策与验收记录，仅作历史参考，**现行契约以 `docs/skill-sandbox-data-flow.md` §5 为准**。v2.0 相对 v1.0 的关键变化见 §0。
> 关联：`docs/skill-sandbox-data-flow.md`、`docs/skill-data-browser-prd.md`
> 一句话：现在「加数据」只有两条路——改 `setup.sh` 再重建，或在数据页直接上传文件到 `data/`。

## 0. v2.0 关键决策（取代 §4）

| 编号 | v1.0 决策 | v2.0 现行 |
|---|---|---|
| D1 | 独立 `imports/` 区 | 取消独立区，上传写入 `data/` |
| D2 | 运行期 `/data/_imports` | 只有 `/data`，无第二挂载点 |
| D5 | 重建 `data/` 时保留 `imports/` | 重建 / 删除 `data/` 会清空上传文件（页面提示 + 二次确认） |
| D7 | 无 setup 技能不支持上传 | 无 setup 也可上传；初始化仍要求 setup |
| 门控 | db 源 + setup + 网关 | db 源 + 网关（不看 setup、不看初始化状态） |
| 接口 | `/api/admin/skills/{id}/imports` | `GET/POST/DELETE /api/admin/skills/{id}/data/file` |
| 审计 | `skill.data.import*` | `skill.data.upload` / `skill.data.upload.delete` |
| 环境变量 | `PAYROLL_SANDBOX_IMPORT_MAX_*` | `PAYROLL_SANDBOX_UPLOAD_MAX_FILE_BYTES`、`PAYROLL_SANDBOX_UPLOAD_MAX_BYTES` |

## 1. 问题

用户原话：「技能里的数据怎么才能添加进去，没有入口。」

**根因不是缺一个按钮，而是数据模型里没有「人工写入」这一层。** 当前 `data/` 被设计成 `setup.sh` 的派生产物（可丢弃、可重建），而用户直觉把它当成资产目录（只增不丢）。两者冲突，所以界面上只出现「初始化 / 重建 / 删除」。

### 1.1 现状证据

| 事实 | 证据 |
|---|---|
| 数据页只提供 初始化 / 重建 / 删除 / 刷新，没有上传或导入 | [SkillDataPanel.vue:230](file:///D:/workspace/agentscope-payroll-agent/frontend/src/views/admin/skills/SkillDataPanel.vue#L230) |
| 数据相关接口只有 状态 / 初始化 / 删除 / 浏览树 / 预览文件，无写接口 | [SkillAdminController.java:666](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L666)、[:682](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L682)、[:747](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L747)、[:780](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L780)、[:823](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L823) |
| `data/` 只由初始化容器里的 setup 写入，运行容器只读挂载 | [SkillWorkspaceService.java:216](file:///D:/workspace/agentscope-payroll-agent/payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/SkillWorkspaceService.java#L216) |
| 初始化包不接受 `data/`、`references/`、`assets/`，只收根固定文件与 `scripts/**` | `docs/skill-sandbox-data-flow.md` §4.2 |
| 单文件 512 KiB、总量 4 MiB、最多 200 文件 | [SkillInitProtocol.java:24](file:///D:/workspace/agentscope-payroll-agent/payroll-protocol/src/main/java/io/agentscope/examples/payroll/sandbox/SkillInitProtocol.java#L24) |
| 初始化容器不能读宿主机本地文件（只挂包目录与 `data/`） | `docs/skill-sandbox-data-flow.md` §4.3 |
| 用户已能编辑 `setup.sh`（文件页签，db 源可写） | [WorkbenchFiles.vue:55](file:///D:/workspace/agentscope-payroll-agent/frontend/src/views/admin/skills/tabs/WorkbenchFiles.vue#L55)、[SkillAdminController.java:485](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L485) |
| 既有产品决策明确禁止人工编辑 `data/` | `docs/skill-data-browser-prd.md` §3 非目标 1、§4 D2=A |

## 2. 用户场景

| 编号 | 角色 | 场景 | 今天能否完成 |
|---|---|---|---|
| J1 | 技能维护人 | 手里有公司导出的 CSV / JSON，没有可下载 URL，网络也访问不到 | 不能（容器读不到本机文件） |
| J2 | 技能维护人 | 数据集已有，不想为它写下载脚本 | 不能 |
| J3 | 试用者 | 先塞两个样例文件跑通技能，再决定要不要正式接数据源 | 只能内嵌进 setup.sh（512 KiB 上限） |
| J4 | 运维 | setup 生成的大目录里补一个修正文件 | 不应支持（会被重建覆盖，见 D2） |

## 3. 今天唯一的可行路径（不改码）

1. 技能工作台 →「文件」→ 编辑 `setup.sh`；
2. 在脚本里用 `git clone` / `curl` / heredoc 把内容写到 `/skill/data/...`；
3. 「数据」→「重建数据」，等状态变 READY。

限制：初始化容器**不能读取宿主机本地文件**，所以本地大文件本质上无路可走；把文件 base64 塞进 `setup.sh` 也只受单文件 512 KiB 限制。这就是需要产品化通道的硬依据。

## 4. 方案对比

| 方案 | 做法 | 成本 | 优点 | 代价 / 风险 |
|---|---|---|---|---|
| **A 引导增强** | 不新增写能力：数据页加「数据来源」说明 + 「编辑 setup」「脚本模板」入口 | 0.5–1 人天（纯前端 + 文案） | 零数据模型改动，可复现性不变 | 仍需写脚本，非技术用户门槛高；J1/J2 无解 |
| **B 受管直写 `data/`** | 新增上传接口直接写 `<skillRoot>/<id>/data` | 3–4 人天 | 心智最直白 | 每次「重建」即清空（需明确警告）；推翻数据浏览器 D2；审计口径变成「产物目录被人工改过」 |
| **C 独立导入区 `imports/`**（推荐目标态） | 新增 `<skillRoot>/<id>/imports`，运行期只读挂载到 `/data/_imports`；数据页新增「导入文件」 | 5–6 人天 | 人工数据持久、重建 `data/` 不丢；`data/` 仍只由 setup 生成，不违反 D2 | 技能脚本需知道 `/data/_imports`；新增写接口、审计、配额、运行时挂载 |
| **D 数据进技能包** | 把数据集放进导入包 | 1–2 人天 | 随技能版本化 | 包上限 512 KiB/文件、4 MiB 总量，只能当样例；`references/`/`assets/` 不参与初始化 |

**推荐：A 立即做（P0，解「没入口」的感知）；C 作为目标态（P1，解 J1/J2）。B 仅当明确接受「重建即丢」且单人维护时才考虑。**

## 5. 推荐路线

### 5.1 P0：把入口和真相说清楚（0.5–1 人天，不改后端）

1. 数据页说明区改为「数据来源」块，三行：
   - 数据由 `setup.sh` 在初始化容器中生成，重建会清空；
   - 主按钮「编辑 setup.sh」（跳文件页签并定位到该文件）；
   - 次按钮「查看下载/写入模板」（抽屉里给 `git clone`、`curl -o /skill/data/...`、heredoc 三个片段）。
2. 空态区分两种：`未初始化`（给「初始化数据」+「编辑 setup」）与 `已初始化但空`（提示「setup 没有写出文件，查看日志尾部」）。
3. 概览「数据」卡补一行「数据来源：setup / 仅导入」（为 P1 预留字段位）。

P0 不改任何接口，也不改变「`data/` 只由 setup 生成」的语义。

### 5.2 P1：受管导入区（目标态）

**目录与挂载**

```
<skillRoot>/<skillId>/
├── data/        # setup 生成，重建/删除只影响这里
└── imports/     # 人工导入，初始化与删除 data 都不影响
```

- 初始化容器：`imports/` 以只读挂载到 `/skill/imports`，setup 可选择读取；
- 运行容器：`data/` → `/data`（只读，不变），`imports/` → `/data/_imports`（只读，新增嵌套挂载）；
- 技能脚本取数口径：`/data` 是产物，`/data/_imports` 是人工补充；技能文档与模板同步更新。

**上传链路**

```
浏览器（数据页「导入文件」）
  └─ POST /api/admin/skills/{id}/imports?path=...   （session + skill:manage）
      └─ SkillAdminController：权限、配额预检、审计
          └─ SandboxGateway/SandboxClient：HMAC + 流式转发
              └─ SandboxImportService：路径护栏 + 写 <skillRoot>/<id>/imports
```

**限制**

| 项 | 值（可配） |
|---|---|
| 单文件 | ≤ 200 MiB（流式，不整包进内存；`PAYROLL_SANDBOX_IMPORT_MAX_FILE_BYTES`） |
| 单技能总量 | ≤ 1 GiB（`PAYROLL_SANDBOX_IMPORT_MAX_BYTES`，硬顶 5 GiB） |
| 文件数 | ≤ 5000 |
| 路径 | 相对路径，复用浏览器的 `requireRelativePath` 护栏；禁 `..`、绝对路径、反斜杠、控制字符、符号链接；首段禁 `_imports` |
| 类型 | 不限制业务类型，但拒绝可执行/脚本扩展名（`.sh/.bash/.bat/.cmd/.exe/.dll/.so/.dylib/.com/.scr/.msi/.ps1/.jar`） |
| 覆盖 | 同名默认 409，需 `overwrite=true` 二次确认 |
| 解压 | 不做在线解压；压缩包按普通二进制保存 |

**UI（最终实现）**

- 数据页在 `data/` 浏览器下方并列一个 `imports/` 浏览器（两个独立的 `SkillDataBrowser`，各自空态与刷新），而非切换标签；
- 导入区动作：单文件上传（带目标目录）、预览、删除；重名时前端弹出覆盖确认后带 `overwrite=true` 重传；
- 上传成功/失败在浏览器内给出结果，不整页刷新；导入区块只在技能声明了 setup 时渲染，非 db 源或沙箱网关关闭时浏览/上传会显示后端返回的不可用原因。

**审计**

- `skill.data.import`、`skill.data.import.delete`；
- 字段：`skillId`、`path`、`sizeBytes`、`sha256`、`overwrite`、操作人；**不记录正文**；
- 与既有 `skill.data.read` 同一资源类型 `skill`。

## 6. 不变的护栏

1. `data/` 仍然只读、只由 setup 生成 —— C 不推翻数据浏览器 D2。
2. 运行容器仍 `--network none`、`--read-only`、非 root、cap-drop ALL。
3. 新数据不自动进模型上下文；是否注入由技能脚本决定，与「不把 data 注入对话」的既有非目标一致。
4. 技能数据仍只落在沙箱宿主机；Web 不缓存正文，多实例约束沿用 D10（P0 单沙箱）。
5. 敏感数据：导入即意味着人工把数据交到沙箱宿主机磁盘；需在 UI 明确提示归属与保留策略。

## 7. 细节决策

| 编号 | 问题 | 选项 | 推荐 | 理由 |
|---|---|---|---|---|
| D1 | 数据模型 | A 仅引导；B 直写 `data/`；C 独立 `imports/` | **C（目标态）**，A 作为 P0 | 保住可复现性，同时给人工数据持久位置 |
| D2 | 运行期路径 | A `/data/_imports`；B `/imports` | **A** | 脚本遍历 `/data` 时自动可见，迁移成本最低；文档写清保留前缀 |
| D3 | 权限 | A 复用 `skill:manage`；B 新增 `skill:data:import` | **A（P1）** | 入口在技能管理页；导出/下载再单独立项 |
| D4 | 上传上限 | A 200 MiB/文件、1 GiB/技能；B 不限 | **A** | 单请求流式即可实现；更大数据集走「setup 拉取」而非上传 |
| D5 | 重建 `data/` 时 imports | A 保留；B 一并清空 | **A（必须）** | 否则人工数据每次重建都丢，等于 B 方案的坑 |
| D6 | 同名覆盖 | A 默认拒绝，显式覆盖；B 直接覆盖 | **A** | 上传重名多为误操作 |
| D7 | 无 setup 技能 | A P1 不支持（先要求有 setup）；B 允许仅 imports | **A**，B 记 P2 | 需要新状态「仅导入数据」，会牵动状态机与运行期判定 |
| D8 | 状态与浏览 | A 不改 `skill_data_state`，浏览页并列两个根；B imports 计入数据大小 | **A** | 避免污染既有容量语义；`imports` 单独显示占用 |
| D9 | 审计粒度 | A 每次上传/删除一条；B 仅批量汇总 | **A** | 人工写入属于数据出口，逐条留痕 |

## 8. 验收标准

| 编号 | 场景 | 期望 |
|---|---|---|
| AC-1 | READY 技能上传 `sample.csv` 到 `imports/` | 200；`imports/sample.csv` 存在；运行容器内 `/data/_imports/sample.csv` 可读；`data/` 内不出现该文件 |
| AC-2 | 「重建数据」后再看 imports | 文件仍在；`data/` 已按 setup 重建；运行容器两个路径都可见 |
| AC-3 | 同名上传不带 `overwrite` | 409，前端提示「已存在，确认覆盖」；带 `overwrite=true` 成功且审计记 overwrite |
| AC-4 | 路径 `../x`、`/etc/passwd`、`a\b`、控制字符 | 400，错误文案只含相对路径 |
| AC-5 | 在 imports 内建符号链接 | 运行期不可见（realpath 拒绝），上传接口不跟随 |
| AC-6 | 超出单文件/总量/文件数上限 | 413 或 400，明确提示具体限额；已写入的部分被回滚 |
| AC-7 | `.sh` 上传 | 400，错误文案列出禁止扩展名 |
| AC-8 | 无 `skill:manage` 用户 | 403，审计无成功记录 |
| AC-9 | 上传成功/删除 | 审计出现 `skill.data.import` / `.delete`，含 path/size/hash，不含正文 |
| AC-10 | 数据页 | `data/` 与 `imports/` 两个分区标签、各自空态、上传进度与结果；`npm run build` 通过；Playwright mock 覆盖上传、409、超限 |
| AC-11 | 真机 | 本机 db 源 + Docker 沙箱，poetry 技能完成「上传一个 JSON → 浏览 → 对话中脚本读取」端到端，截图或录屏留证 |

### 8.1 验收结果（2026-11-27）

| 编号 | 结果 | 证据 |
|---|---|---|
| AC-1 | 通过（真机） | 上传 CSV 返回 200；宿主持久文件存在；运行容器内脚本在 `/data/_imports/sample.csv` 读到相同内容；`data/` 根目录树 18 项且不含 `_imports` |
| AC-2 | 通过（单测 + 代码路径） | 未在本机执行完整重建（既有 `data/` 为 300 MB 样例数据，重建耗时且无必要）；删除/重建只作用于 `dataDir`，`imports/` 为独立目录，单测 `deletePreservesImports` 覆盖 |
| AC-3 | 通过（真机） | 同名上传不带 `overwrite` → 409；带 `overwrite=true` → 200，审计记 `overwritten=true` |
| AC-4 | 通过（单测） | `../x`、`/etc/passwd`、`a\b`、控制字符、首段 `_imports` 均 400 |
| AC-5 | 通过（单测） | `resolveExisting` 遇到符号链接直接拒绝，不做 realpath 跟随 |
| AC-6 | 通过（单测） | 超单文件、总量、文件数分别抛 `ImportLimitException`，映射 413；临时文件回滚 |
| AC-7 | 通过（单测） | `.sh` 等禁止扩展名 400，错误文案列出禁止列表 |
| AC-8 | 通过（接口层） | 无 `skill:manage` 被 `@RequirePermission` 拦截，审计无成功记录 |
| AC-9 | 通过（单测） | 上传/删除审计含 `path/sizeBytes/sha256`，正文不入 before/after |
| AC-10 | 通过 | Vitest 44/44、`npm run build` 通过、Playwright 25/25（含导入上传与 409 mock） |
| AC-11 | 通过（真机） | 本机 db 源 + Docker 沙箱 poetry 技能：上传 → 浏览 → 运行期脚本读取 `/data/_imports` 端到端通过 |

**实现偏差（与草案的差异）**

| 项 | 草案 | 最终实现 | 原因 |
|---|---|---|---|
| UI 形态 | 两个分区标签切换 | 上下并列两个独立 `SkillDataBrowser` | 实现更简单，两个根可同时可见；空态与刷新互不干扰 |
| 环境变量名 | `PAYROLL_SKILL_IMPORT_MAX_BYTES` | `PAYROLL_SANDBOX_IMPORT_MAX_FILE_BYTES`、`PAYROLL_SANDBOX_IMPORT_MAX_BYTES` | 与 `PAYROLL_SANDBOX_INIT_*` 命名一致，并区分单文件与总量 |
| 上传签名 | 未说明 | 正文不参与 HMAC，`size` 与 `sha256` 入签名，沙箱侧重新计算校验 | 避免大文件进入签名计算，同时保证完整性 |
| 可用门控 | 有 setup 即可 | 额外要求 db 技能源且沙箱网关启用 | 与数据初始化入口保持同一前置条件，避免半可用状态 |
| 多选上传 | 支持 | 单文件上传（可反复添加） | 契约更小，409/413 逐文件反馈更清晰 |
| 初始化期挂载 | 可选 | `/skill/imports` 只读挂载，目录不存在则跳过 | setup 可选择合并人工数据，不影响既有技能 |

## 9. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 用户把 imports 当「唯一数据源」，setup 被绕过 | 可复现性下降 | 文案明确 `imports` 是补充；概览显示两者来源 |
| 上传中断留下半文件 | 运行期读到残缺文件 | 先写临时文件再原子 rename；中断清理超 24h 的 `.part` |
| 磁盘配额被导入撑满 | 沙箱宿主机不可用 | 单技能 1 GiB 上限 + 宿主机剩余空间预检；与 `data/` 配额分开计 |
| 敏感薪酬数据落到沙箱磁盘 | 合规风险 | `skill:manage` 门禁 + 逐条审计 + UI 提示；导出能力不在本期 |
| 技能脚本未适配 `/data/_imports` | 用户上传了但智能体读不到 | P1 同步更新技能模板与 `docs/skill-sandbox-data-flow.md`；数据页显示「当前技能脚本是否读取 imports」提示（P2） |
| 多实例沙箱 | imports 只在一台宿主机 | 沿用数据浏览器 D10：P0 单沙箱；多实例需粘性路由或共享卷 |
| 大文件拖垮 Web | OOM | 流式转发 + 固定长度校验，禁止整包缓冲 |

## 10. 拍板结果

| 编号 | 问题 | 结论 |
|---|---|---|
| P1 | 是否接受「独立 `imports/`」作为人工数据归属 | **接受（方案 C）**，已实现 |
| P2 | 运行期路径用 `/data/_imports` 还是 `/imports` | **`/data/_imports`**，已实现 |
| P3 | 上传上限 200 MiB/文件、1 GiB/技能是否够用 | **够用且可配**；上限由 `PAYROLL_SANDBOX_IMPORT_MAX_*` 调整，硬顶 5 GiB |

## 11. 实施记录

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 数据页「数据来源」块、编辑 setup 跳转、模板抽屉、空态分流 | 未单独实施（入口已由数据浏览器与 setup 编辑覆盖） |
| P1-1 | 沙箱：imports 目录、写接口、配额、原子写入、单测 | 完成 |
| P1-2 | 公共层 + Web：HMAC 写通道、代理、审计、错误映射 | 完成 |
| P1-3 | 前端：双分区浏览、上传、409/超限处理 | 完成 |
| P1-4 | 运行时挂载、技能模板与文档、真机端到端 | 完成（本文档与 `skill-sandbox-data-flow.md` 已同步） |
