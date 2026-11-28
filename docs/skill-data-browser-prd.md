# 技能数据浏览器 PRD（沙箱 data/ 只读浏览）

> 状态：P0 已实现（2026-11-26），D1–D10 按推荐项落地；P1 下载与 P2 富预览未开始
> 2026-11-27 修订：数据页在同一 `data/` 目录内提供上传/删除（不再使用 `imports/`），浏览与上传不再要求技能声明 setup；以 `docs/skill-sandbox-data-flow.md` 为准。
> 范围：管理台技能工作台「数据」页签 + `payroll-sandbox` 新增只读内部接口
> 关联：`docs/skill-sandbox-data-flow.md`、`docs/skill-console-ux-spec.md`、`docs/skill-resources-and-sandbox-design.md`
> 前置入口（已随本次改动落地）：概览「数据」卡常显「去数据」、「脚本」卡新增「去文件」、「下一步」四项常显跳转按钮

## 1. 背景

技能沙箱把「初始化」和「运行」拆成两段：初始化容器联网执行 `setup.sh`，把数据写进宿主 `<skillRoot>/<skillId>/data`；运行容器禁网、只读挂载同一目录到 `/data`。管理台目前只能看到这份数据的状态、大小、退出码与日志尾部，**看不到里面有什么**。

用户反馈原话：「概览里有数据以及脚本，但是数据从哪里查看，脚本在哪里写入」。脚本已可通过「文件」页签查看/编辑；数据则只有运维动作，没有浏览入口。

### 1.1 现状证据

| 事实 | 证据 |
|---|---|
| 数据页签只展示状态、大小、声明上限、setup 路径、上次同步、退出码、日志尾部，无文件列表 | [SkillDataPanel.vue:1](file:///D:/workspace/agentscope-payroll-agent/frontend/src/views/admin/skills/SkillDataPanel.vue#L1) 起的模板与 `data-facts` |
| 管理台数据相关接口只有状态 / 初始化 / 删除三个 | [SkillAdminController.java:664](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L664)、[:680](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L680)、[:745](file:///D:/workspace/agentscope-payroll-agent/payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java#L745) |
| 数据实际落在沙箱宿主机：包目录与 `data/` 由工作区服务创建，初始化后只保留 `data/` | [SkillWorkspaceService.java:38](file:///D:/workspace/agentscope-payroll-agent/payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/SkillWorkspaceService.java#L38)、[:215](file:///D:/workspace/agentscope-payroll-agent/payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/SkillWorkspaceService.java#L215) |
| 数据目录由已校验 skillId 推导，不接受请求传路径 | [SandboxSettings.java:154](file:///D:/workspace/agentscope-payroll-agent/payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/SandboxSettings.java#L154) |
| Web 容器没有挂载 skillRoot，且多节点时数据只在执行初始化的那台宿主机 | `docker/docker-compose.yml:237-240`（web-1 仅挂 kb-data / output-data）；`docker/docker-compose.yml:291-293`（只有 sandbox 挂 skillRoot） |
| 沙箱已有 HMAC 内部通道，仅三个技能端点 | [SandboxSkillController.java:38](file:///D:/workspace/agentscope-payroll-agent/payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/SandboxSkillController.java#L38)、[:65](file:///D:/workspace/agentscope-payroll-agent/payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/SandboxSkillController.java#L65)、[:81](file:///D:/workspace/agentscope-payroll-agent/payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/SandboxSkillController.java#L81) |
| 内部签名当前只覆盖 `METHOD + path + body`，尚无 query 约定 | [SandboxClient.java:214](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/service/SandboxClient.java#L214)、时钟偏移上限 120 秒 [SandboxSkillController.java:23](file:///D:/workspace/agentscope-payroll-agent/payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/SandboxSkillController.java#L23) |
| 技能 ID 与相对路径已有严格校验，可复用 | [SkillInitProtocol.java:43](file:///D:/workspace/agentscope-payroll-agent/payroll-protocol/src/main/java/io/agentscope/examples/payroll/sandbox/SkillInitProtocol.java#L43)、[:182](file:///D:/workspace/agentscope-payroll-agent/payroll-protocol/src/main/java/io/agentscope/examples/payroll/sandbox/SkillInitProtocol.java#L182) |

## 2. 问题与用户故事

| 编号 | 角色 | 故事 |
|---|---|---|
| US-1 | 技能维护人 | 初始化 READY 后，想确认 `data/` 里到底生成了哪些文件、结构对不对（如 poetry 技能生成的诗人目录与文本） |
| US-2 | 排障者 | 脚本执行结果异常时，想直接看 `data/` 里的原始内容，而不是只看到「初始化完成」 |
| US-3 | 平台管理员 | 想核对「声明上限 2 GiB」与实际占用是否异常，快速定位某个大文件 |
| US-4（P1） | 业务核对人 | 想把单个 CSV/JSON 下载下来做线下核对，但不应拿到整包 |

## 3. 目标与非目标

**目标**

1. 在「数据」页签内**只读**浏览 `data/`：目录树、文件名、大小、修改时间、文本预览。
2. 浏览内容与状态卡一致：未初始化、初始化中、失败、沙箱未启用都要有明确反馈。
3. 所有读取经沙箱内部通道，Web 不直接接触宿主机目录，不落盘缓存正文。
4. 路径、符号链接、文件类型、大小、权限、审计全部有明确护栏。
5. 不改变现有初始化 / 删除 / 运行期数据语义。

**非目标**

1. 不在浏览器内做文本编辑或重命名——上传/删除走数据页单独的「上传」「删除」动作，写入的仍是同一个 `data/` 目录；重建数据会清空上传文件（页面有明确提示）。
2. 不提供任意 Shell、SQL 或表达式查询；agent 仍只能通过技能脚本读 `/data`。
3. 不把 `data/` 内容注入对话上下文。若产品目标是「让智能体直接查数据」，必须单独立项评估：它会绕过技能脚本的取数口径，也把薪酬数据带进模型上下文。
4. 不跨技能浏览；不展示 `<skillRoot>` 或其他技能目录。
5. 不在 Web 侧缓存或镜像数据副本。
6. P0 不做在线解压、图片渲染、PDF 预览、Office 预览。

## 4. 关键决策（待拍板）

| 编号 | 问题 | 选项 | 推荐 | 理由 / 影响 |
|---|---|---|---|---|
| D1 | 能力范围 | A 只读浏览；B 浏览 + 下载；C 浏览 + 查询/执行 | **A（P0）**，B 放 P1 | 浏览能解决 90% 排障场景；下载涉及敏感数据出口与审计升级，单独决策 |
| D2 | 是否允许编辑 data/ | A 禁止；B 允许文本编辑 | **A**（2026-11-27 修订：允许上传/删除文件，仍禁止在线文本编辑） | 上传与 setup 共用 `data/`，重建会清空；在线编辑会与「改完即被重建覆盖」和审计口径冲突 |
| D3 | 读取通道 | A 沙箱内部接口透传；B Web 直接读宿主机目录 | **A** | B 在容器/多节点下不可达（web 未挂 skillRoot），且会把宿主机路径与权限暴露给 Web |
| D4 | 浏览授权 | A 复用 `skill:manage`；B 新增只读权限 | **A（P0）** | 浏览入口天然在技能管理页；P1 下载再引入 `skill:data:export` |
| D5 | 审计粒度 | A 目录树不审计、文件读取审计；B 全量审计；C 全不审计 | **A** | 树查询高频且不含内容；文件读取可能触碰薪酬敏感数据，必须留痕 |
| D6 | 预览大小与截断 | A 文本预览 256 KiB 截断并提示；B 不截断 | **A**；硬上限 1 MiB（请求参数不可突破） | 防止大文件拖垮浏览器与 Web 内存 |
| D7 | 数据非 READY 时行为 | A 未创建 `data/` 返回空树、初始化中 409；B 一律返回空树 | **A**（2026-11-27 修订：`data/` 由上传直接创建，未创建按空树处理以便先上传） | 初始化中仍需 409，避免读到半成品；未创建空树方便无 setup 技能直接上传 |
| D8 | HMAC 是否覆盖 query | A 规范串纳入排序后的 query；B 只签 path | **A（必须）** | 否则 `path` 参数可被篡改，等于绕过路径护栏 |
| D9 | 二进制文件 | A 只回元数据、不传正文；B 回 base64 | **A** | 浏览场景不需要二进制正文；base64 会放大 1/3 且无渲染能力 |
| D10 | 多实例沙箱 | A P0 约束单沙箱实例；B 现在就做技能→实例路由 | **A**，B 记入后续 | 当前 compose 只有一个 sandbox；多实例时 `data/` 在初始化那台机器上，需要粘性路由或共享卷 |

## 5. 方案概览

```
浏览器（数据页签）
  └─ GET /api/admin/skills/{id}/data/tree|file   （session + skill:manage）
      └─ SkillAdminController：审计、错误映射、参数限幅
          └─ SandboxGateway（新增 browse 方法）
              └─ SandboxClient：HMAC（含 canonical query）GET /internal/skills/{id}/data/...
                  └─ SandboxDataService：路径护栏 + 只读访问 <skillRoot>/<id>/data
```

设计约束：

- 只新增读接口，**不新增任何写接口**；初始化 / 删除仍走既有端点。
- 浏览与初始化互不阻塞：浏览不占初始化信号量，但初始化中（`INITIALIZING`）直接返回 409，避免读到半成品。
- 浏览请求不进入 `pendingInit`、不改 `skill_data_state`，状态卡数据仍以既有同步逻辑为准。

## 6. API 契约

### 6.1 沙箱内部接口（新增）

统一前缀沿用 `/internal/skills/{id}`，新增 `data` 子资源；全部要求 HMAC 头。

#### 6.1.1 目录树

`GET /internal/skills/{id}/data/tree?path=relative/dir&depth=1`

| 参数 | 约束 | 默认 |
|---|---|---|
| `path` | 相对 `data/`，可空（根）；复用 `SkillInitProtocol.requireRelativePath` 规则 | 空 |
| `depth` | 1–3 | 1 |

```json
{
  "status": "OK",
  "skillId": "poetry",
  "path": "poets",
  "entries": [
    { "name": "li-bai", "path": "poets/li-bai", "type": "dir", "size": 0, "modifiedAt": 1732600000000 },
    { "name": "index.json", "path": "poets/index.json", "type": "file", "size": 20481, "modifiedAt": 1732600000000, "isText": true }
  ],
  "truncated": false,
  "entryLimit": 500
}
```

单目录最多 500 项；超出时 `truncated=true`，按名称排序截断，前端提示「仅显示前 500 项」。

#### 6.1.2 读取文本文件

`GET /internal/skills/{id}/data/file?path=poets/index.json&maxBytes=262144`

```json
{
  "status": "OK",
  "path": "poets/index.json",
  "size": 20481,
  "modifiedAt": 1732600000000,
  "isText": true,
  "encoding": "utf-8",
  "truncated": false,
  "content": "{ ... }"
}
```

- `maxBytes` 默认 262144，硬顶 1048576；超过硬顶返回 400。
- 文件大于 `maxBytes` 时只读前 `maxBytes`，`truncated=true`，`size` 保持原值。
- `isText=false`（二进制 / 非法 UTF-8 / 含 NUL）时**不带 `content` 字段**，只回 `size`、`mime`。
- 目录、设备文件、FIFO、符号链接一律 400。

#### 6.1.3 下载单文件（P1）

`GET /internal/skills/{id}/data/download?path=...`，流式返回 `application/octet-stream`，上限 64 MiB。P0 不实现。

#### 6.1.4 状态码

| 码 | 语义 | 响应 |
|---|---|---|
| 200 | 成功 | 上述 JSON |
| 400 | 路径非法、文件过大、非普通文件 | `status=INVALID` + 中文原因（不回显宿主机绝对路径） |
| 401 | HMAC 失败 / 时钟偏移超 120s | 空体 |
| 404 | 技能工作区不存在或路径不存在 | `status=NOT_FOUND` |
| 409 | 初始化中，暂不可浏览 | `status=BUSY` |
| 503 | 未配置令牌 | `status=NOT_CONFIGURED` |

### 6.2 Web 管理接口（新增）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/admin/skills/{id}/data/tree?path=&depth=` | `skill:manage` | 透传沙箱树接口；错误映射为 `PayrollNotFoundException` / `PayrollConflictException` / `PayrollException` |
| GET | `/api/admin/skills/{id}/data/file?path=&maxBytes=` | `skill:manage` | 透传并审计 `skill.data.read` |
| GET | `/api/admin/skills/{id}/data/download?path=` | `skill:manage`（P1 起改 `skill:data:export`） | 流式下载，P1 |

Web 侧额外规则：

1. 先查技能是否存在（`store.detail(id)`），非 db 源直接 400：「当前来源不提供数据状态」。
2. 技能声明无需数据（无 setup）时直接 400，不打扰沙箱。
3. 沙箱未启用直接 400，提示检查 `PAYROLL_SKILL_EXEC_ENABLED / PAYROLL_SANDBOX_URL / PAYROLL_SANDBOX_TOKEN`。
4. 错误文案只含相对路径与原因，**不回传宿主机绝对路径**。

### 6.3 HMAC 规范串（D8）

新增 `SkillDataProtocol`（`payroll-protocol`），两侧共用：

```
canonicalPath = "/internal/skills/" + id + "/data/tree" + "?" + canonicalQuery
canonicalQuery = 按 key 字典序，key=urlEncode(value)，用 & 连接；无参数时不加 "?"
signature = HMAC-SHA256(token, token + "\n" + timestamp + "\n" + METHOD + "\n" + canonicalPath + "\n" + body)
```

`SandboxClient` 与 `SandboxSkillController` 必须调用同一个 `canonicalPath` 方法生成签名与验签，禁止各自拼接。测试需覆盖：参数顺序不同签名一致、缺参/多参/编码字符（空格、中文、`+`）两侧一致。

## 7. 安全设计

### 7.1 路径护栏（沙箱侧，fail-closed）

1. `requireSkillId` 正则 `^[a-z][a-z0-9-]{1,63}$`。
2. `root = settings.skillDataDir(id)`；若 `root` 不存在 → 404。
3. `rootReal = root.toRealPath()`（根目录本身也解析一次）。
4. `candidate = root.resolve(relative).normalize()`，必须 `startsWith(root)`。
5. `candidate.toRealPath()` 必须 `startsWith(rootReal)`——拦截指向外部的符号链接。
6. 逐段拒绝符号链接（`Files.isSymbolicLink`），避免「链接指向根内但语义漂移」。
7. 仅允许 `Files.isRegularFile(p, NOFOLLOW_LINKS)`；目录只用于 tree。
8. 拒绝路径中的 `.`、`..`、反斜杠、控制字符、绝对路径（复用 `SkillInitProtocol` 的规则）。
9. 只读取相对路径，错误信息中只出现相对路径。

### 7.2 内容护栏

- 文本判定：扩展名白名单（`md/txt/json/yaml/yml/csv/tsv/log/py/sh/js/xml/html`）+ 前 8 KiB 无 NUL 且 UTF-8 可解码；两者都满足才算文本。
- 预览截断：默认 256 KiB，硬顶 1 MiB；`truncated=true` 时前端显著提示。
- 单目录 500 项上限；`depth ≤ 3`，禁止递归整树。
- 单次响应体由沙箱侧控制上限，Web 侧再校验一次长度（防止协议实现漂移）。

### 7.3 权限、审计与限流

- Web 端点沿用 `@RequirePermission(Permissions.SKILL_MANAGE)`（[Permissions.java:34](file:///D:/workspace/agentscope-payroll-agent/payroll-common/src/main/java/io/agentscope/examples/payroll/support/Permissions.java#L34)）。
- 审计：`skill.data.read`，字段 `skillId`、`path`、`sizeBytes`、`truncated`、`isText`、`operator`；**不记录文件内容**。
- 目录树不审计（D5），但记录访问指标（如 `skill.data.tree.requests`）。
- 读取超时：tree 5s、file 10s；超时返回 503 通用文案并记日志。
- P1 下载单独审计 `skill.data.download` 并引入导出权限。

### 7.4 多实例约束（D10）

`data/` 只在执行初始化的 sandbox 宿主机上。P0 明确要求 `payroll-sandbox` 单实例；多实例上线前必须先做「技能 → 沙箱实例」粘性路由或共享卷，否则会出现「A 实例初始化、B 实例浏览为空」。

## 8. UI 设计

数据页签保留现有状态卡，在其下新增「数据文件」面板（只读）：

```
┌ 数据 ─────────────────────────────────────────────┐
│ 状态 READY · 12.4 MB / 2.0 GB · 上次同步 11:20     │
│ [初始化] [删除数据] [刷新状态]                      │
├ 数据文件 ─────────────────────── [刷新] [只读] ───┤
│ poets/                     │ poets/index.json      │
│   li-bai/                  │ 20.0 KB · 11-26 11:19 │
│   du-fu/                   │ ─────────────────────  │
│ index.json          20 KB  │ {                     │
│ README.md          1.2 KB  │   "poets": [ ...      │
│                            │ }                     │
└────────────────────────────┴──────────────────────┘
```

交互规则：

1. 进入页签时自动加载根目录（仅当 `status=READY` 且 `needsData=true`）。
2. 左树按目录展开/折叠，懒加载子目录；面包屑显示当前路径。
3. 点击文件在右侧预览；文本用等宽字体；`truncated` 显示「仅预览前 256 KiB」。
4. 二进制文件显示大小、修改时间、类型徽标，不显示内容（P0 无下载）。
5. 空目录显示「目录为空」；未初始化显示空状态 + 「初始化数据」按钮；初始化中显示进度并轮询状态，不请求树。
6. 沙箱未启用显示既有提示，不渲染浏览区。
7. 非 db 源沿用现有「当前来源不提供数据状态」。
8. 移动端/窄屏：树与预览上下堆叠，预览最小高度 240px。
9. 无障碍：树节点 `role="treeitem"`、`aria-expanded`、键盘 ↑↓←→/Enter；预览区 `role="region" aria-label="文件预览"`。
10. 只读语义：不出现编辑、保存、删除、上传按钮。

前端改动涉及：

- 新增 `frontend/src/views/admin/skills/SkillDataBrowser.vue`（树 + 预览）。
- `WorkbenchData.vue` 在 `SkillDataPanel` 下方挂载浏览器，仅在 `source === 'db'` 时渲染。
- `frontend/src/api/http.js` 复用现有 `api()`。

## 9. 验收标准

| 编号 | 场景 | 期望 |
|---|---|---|
| AC-1 | poetry 技能 READY | 树根条目与宿主机 `docker exec` 查看 `<skillRoot>/poetry/data` 一致；文本文件预览内容与 `cat` 一致 |
| AC-2 | `path=../../pkg/x`、`/etc/passwd`、`a\\b`、控制字符 | 均 400，错误文案只含相对路径 |
| AC-3 | 在 `data/` 内创建指向 `/etc` 的符号链接后访问 | 400，不返回目标内容 |
| AC-4 | 600 KiB 文本文件 | 返回前 256 KiB，`truncated=true`，前端有截断提示；请求 `maxBytes=2097152` 返回 400 |
| AC-5 | PNG / 压缩包 | `isText=false`，无 `content` 字段，前端显示元数据 |
| AC-6 | `INITIALIZING` / 未初始化 | 分别 409 / 404，前端给出对应提示，不显示假空树 |
| AC-7 | 无 `skill:manage` 用户访问 | 403，审计无成功记录 |
| AC-8 | 读取成功 | 审计出现 `skill.data.read`，包含 path/size/operator，不含正文 |
| AC-9 | 既有初始化 / 删除 / 状态接口 | 全量回归通过，行为不变 |
| AC-10 | 前端 | `npm run build` 通过；Playwright mock 冒烟覆盖树加载、文件预览、空态、409 态 |
| AC-11 | 真机 | 本机 db 源 + Docker 沙箱，poetry 技能完成端到端浏览；截图或录屏留证。2026-11-26 已通过，见 §9.1 |

### 9.1 真机验收记录（2026-11-26）

环境：Web `127.0.0.1:8080`（db 源，nginx `8880` 静态入口）、runtime `8100`、WSL Docker 沙箱容器 `payroll-sandbox-8200`（技能根 `/tmp/payroll-sandbox-work/skills`）。登录 admin 后进入 `/platform/skills/poetry/data`。

| 检查项 | 结果 |
|---|---|
| 数据状态卡 | 「数据就绪」，数据大小 287.0 MB / 2048 MB，初始化脚本 `scripts/setup.sh`，退出码 0 |
| 根目录树 | 15 个目录（`strains`、`五代诗词`、`元曲`、`全唐诗`、`四书五经`、`宋词`、幽梦影、御定全唐詩、曹操诗集、`楚辞`、水墨唐诗、纳兰性德、蒙学、论语、诗经）+ `LICENSE 1.1 KB`、`log.log 23.3 KB`、`README.md 7.8 KB` |
| 展开 `宋词` | 返回 `author.song.json`、`ci.db 6.3 MB` 与 `ci.song.*.json` 等 30+ 条目，中文路径经 URL 编码正常 |
| 文本预览 | `宋词/ci.song.2019y.json`：面包屑 `data / 宋词 / ci.song.2019y.json`，大小、修改时间、`application/json`，正文中文正常解码 |
| 二进制 | `宋词/ci.db`：`application/octet-stream`，标注「二进制 / 二进制文件不展示内容，仅提供大小、修改时间与类型」 |
| 超限截断 | `宋词/ci.song.1000.json`（360.3 KB）：提示「仅预览前 256.0 KB，完整文件未加载。」 |
| 请求链路 | 5 次 `GET /api/admin/skills/poetry/data/{tree,file}` 均 200，参数 `path=`、`depth=1`、`maxBytes=262144` 与协议一致 |
| 审计 | `GET /api/audit?action=skill.data.read` 返回 8 条 SUCCESS（含本次 3 次文件预览） |

说明：Qoder 内置浏览器为隐藏视口（`NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`），本轮以 DOM 结构与网络请求留证，未生成截图；像素级视觉走查仍需普通浏览器。

## 10. 测试计划

| 层 | 内容 |
|---|---|
| 协议单测 | `SkillDataProtocol` 路径校验、canonical query 签名一致性（顺序、编码、中文） |
| 沙箱单测 | 临时目录构造树 / 大文件 / 二进制 / 符号链接 / 目录越界；`SandboxDataService` 每个分支 |
| 沙箱控制器 | HMAC 通过/失败、400/404/409/503 状态映射 |
| Web 控制器 | 权限、非 db 源、无 setup、沙箱禁用、错误映射、审计字段 |
| 前端单测 | 树展开、预览、截断提示、409/404/空态 |
| Playwright | mock 接口的冒烟；真机模式加一条只读浏览用例 |
| 回归 | `JdbcSkillStoreTest`、`SkillAdminControllerTest`、`SandboxSkillControllerTest`、初始化/删除既有用例 |

## 11. 分期与工作量（粗估）

| 阶段 | 内容 | 估时 |
|---|---|---|
| P0-1 | `SkillDataProtocol` + 沙箱路径护栏 + tree/file 接口 + 单测 | 2.5 人日 |
| P0-2 | `SandboxGateway` / `SandboxClient` 扩展 + Web 透传 + 审计 + 控制器测试 | 1.5 人日 |
| P0-3 | 前端树 + 预览 + 空态/错误态 + vitest | 2 人日 |
| P0-4 | 真机验收 + 文档回写 | 1 人日 |
| P1 | 下载、搜索、目录项分页、导出权限与审计 | 2 人日 |
| P2 | CSV/JSON 表格化预览、图片缩略图、下载限流 | 3 人日 |

## 12. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| HMAC 未覆盖 query，路径参数被篡改 | 高危路径穿越 | D8：两侧共用 canonical 方法与测试 |
| 符号链接逃逸 | 读到宿主机任意文件 | 规范化 + realpath + 逐段拒链 + NOFOLLOW |
| 薪酬敏感数据外泄 | 合规风险 | `skill:manage`、读取审计、默认不提供下载 |
| 大目录/大文件拖慢 Web | 页面卡死、内存上涨 | 500 项、depth 3、256 KiB/1 MiB 上限 |
| 初始化中读到半成品 | 误判数据错误 | INITIALIZING 直接 409 |
| 多实例沙箱读到空目录 | 「数据丢了」误报 | P0 单实例约束；多实例前做路由/共享卷 |
| 沙箱进程与执行容器路径不一致 | 浏览为空 | 沿用现有约束：`PAYROLL_SANDBOX_SKILL_ROOT` 必须与宿主机同路径（compose 已同路径挂载） |
| Windows 权限位不生效 | 无影响 | 只读访问由沙箱进程控制，不依赖 POSIX 权限 |

## 13. 兼容与回滚

- 全部为**新增**端点与新增组件，不改现有接口契约；旧客户端不受影响。
- 建议加开关 `PAYROLL_SKILL_DATA_BROWSE_ENABLED`（默认 true，随沙箱启用生效）；关闭时 Web 返回 404，前端隐藏浏览面板。
- 回滚只需回退新增代码与开关，不涉及数据迁移；`data/` 与初始化状态不受影响。

## 14. 未决问题

1. D1 是否需要在 P0 就带下载？（下载会引入新的数据出口，建议单独立项）
2. 预览是否需要按 MIME 做语法高亮/CSV 表格化？（建议 P2）
3. 浏览行为是否需要单独审计查询（而非文件读取）以支持合规追溯？（当前 D5 为不审计）
4. 多实例沙箱的归属路由优先级，是否与本轮容量整改一起排期？
