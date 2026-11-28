# 技能项目（Skill Project）支持规格

- 状态：已实现并验收（P0–P2 全部落地，2026-11-16）
- 日期：2026-11-16
- 关联：[skill-resources-and-sandbox-design.md](skill-resources-and-sandbox-design.md)、[NacosSkillRegistry.java](../payroll-common/src/main/java/io/agentscope/examples/payroll/skill/NacosSkillRegistry.java)、[SkillFilePackage.java](../payroll-common/src/main/java/io/agentscope/examples/payroll/skill/SkillFilePackage.java)、[DockerSandboxRunner.java](../payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/DockerSandboxRunner.java)
- 参考样例：[Wscats/poetry-skills](https://github.com/Wscats/poetry-skills)（目录式技能：SKILL.md + PROMPT.md + setup.sh + data/ 大数据集）

## 一、结论

把"技能"从「单篇正文 + 小文件包」升级为**技能项目**：

1. 支持目录式技能包导入（Git URL / ZIP），解析 `SKILL.md` 全量 frontmatter（`name/version/license/type/description/activation/setup`）与根文件、子目录；
2. 大数据（如 poetry 的 `data/` 约 291MB）**不进 Nacos**，由技能自带的初始化脚本在沙箱工作区内下载；
3. 沙箱新增「技能初始化」链路：一次性容器、允许联网、工作区可写、限时限量；
4. 运行期保留 `skill_run` 单一通道：`data/` 只读挂载，模型只能通过技能自带 `scripts/**` 检索数据，沙箱仍禁网。

### 已确认决策

| 编号 | 决策 | 结论 |
| --- | --- | --- |
| D1 | 首期范围 | 完整技能项目：目录导入 + data 初始化 + 运行期数据挂载 |
| D2 | 导入方式 | Git URL 与 ZIP 上传都要 |
| D3 | 初始化边界 | 沙箱联网执行技能自带 setup 脚本（独立 init 容器） |
| D4 | 数据访问 | 技能自带脚本检索，data 只读挂载，不新增平台数据查询工具 |

## 二、现状与差距

| 能力 | poetry-skills | 当前实现 | 差距 |
| --- | --- | --- | --- |
| 包结构 | 目录：`SKILL.md`、`PROMPT.md`、`setup.sh`、任意子目录 | 仅 Nacos 正文 + `references/**`、`scripts/**`（[SkillFilePackage.java:190](../payroll-common/src/main/java/io/agentscope/examples/payroll/skill/SkillFilePackage.java#L190)） | 根文件与任意子目录不可存 |
| frontmatter | `name/version/license/description(多行)/type/activation.triggers` | 只取 `description` 与 `execution.enabled`（[NacosSkillRegistry.java:459](../payroll-common/src/main/java/io/agentscope/examples/payroll/skill/NacosSkillRegistry.java#L459)） | 其余字段被丢弃，保存时被覆盖 |
| 大小 | 包本身约 15KB，data 291MB 分离 | 单文件 64KB、总量 256KB、最多 20 个 | 包容量不足；大数据无处安放 |
| 初始化 | `setup.sh` 一次性 git clone | 无初始化概念 | 需要 init 容器与状态机 |
| 运行 | 脚本读本地 `data/` | 一次性容器、tmpfs 16MB、`--network none`、只挂 `scripts/**`（[DockerSandboxRunner.java:149](../payroll-sandbox/src/main/java/io/agentscope/payroll/sandbox/DockerSandboxRunner.java#L149)） | 无数据挂载、无持久工作区 |
| 导入 | 直接 clone 仓库 | 管理台逐文件手写 | 需要 Git/ZIP 导入 |

注意：poetry-skills 本身是 prompt-only 项目，**仓库内没有查询脚本**；要让它可回答问题，还需技能作者补 `scripts/*.py`。本规格交付的是承载能力，验收用自带脚本的 fixture + 真实仓库导入/初始化两步验证。

## 三、目标格式

以 poetry-skills 为事实标准：

```
poetry/
├── SKILL.md          # frontmatter + 正文（必需）
├── PROMPT.md         # 核心指令（可选，作为 references 之外的根文件）
├── README.md         # 可选
├── LICENSE           # 可选
├── setup.sh          # 可选，初始化脚本
├── references/**     # 短参考
├── scripts/**        # 可执行脚本（.py/.sh/.js）
└── data/             # 不随包发布，初始化后在沙箱工作区生成
```

frontmatter 兼容示例：

```yaml
---
name: poetry
version: 1.0.0
license: MIT
description: |
  提供诗词生成与查询能力……
type: prompt
activation:
  - trigger: "诗词生成"
    description: 写诗、作词时激活
  - trigger: "查询诗词"
    description: 找诗、搜诗时激活
setup: "sh setup.sh"        # 可选；缺省时存在 setup.sh 即执行
data:
  max-bytes: 536870912      # 可选，默认 2GB，硬上限 5GB
---
```

- `activation` 首期**只展示不参与路由**（技能与智能体仍是显式绑定关系）。
- `setup` 只能指向包内 `setup.sh` 或 `scripts/**` 下的脚本，禁止 shell 字符串拼接之外的路径。
- `name` 归一化为技能 ID：小写字母、数字、短横线，2–64 位；非法时回退仓库/ZIP 名。

## 四、存储模型

### 4.1 Nacos（小包）

| 配置 | dataId | 内容 |
| --- | --- | --- |
| 正文 | `<id>.md` | 完整 frontmatter（保留未知字段）+ 正文 |
| 文件包 v2 | `<id>.files.json` | `{"version":2,"mdHash":"...","meta":{...},"files":[{"path","content"}]}` |

- v2 兼容读取 v1；写入统一升到 v2。
- `meta` 保存原始 frontmatter 映射，管理台保存正文时只重写 `name/description/execution`，`version/license/type/activation/setup/data` 原样保留。
- 包内路径白名单扩展为：
  - 根文件：`SKILL.md`、`PROMPT.md`、`README.md`、`LICENSE`、`setup.sh`（固定名）
  - 目录：`references/**`、`scripts/**`、`assets/**`
  - 禁止：`data/**`、`.git/**`、`node_modules/**`、绝对路径与 `..`
- 限额：单文件 512KB、总量 4MB、最多 200 个（`PAYROLL_SKILL_FILE_*` 可调）；仍只接受 UTF-8 文本，二进制暂不支持。
- 4MB 是 Nacos 单配置的保守值；超出时导入直接失败并提示"请把大数据放到 data/ 或知识库"。

### 4.2 沙箱工作区（大数据）

- 宿主目录：`PAYROLL_SANDBOX_SKILL_ROOT/<skill-id>/`，其中 `data/` 为技能数据。
- 状态文件：`PAYROLL_SANDBOX_SKILL_ROOT/<skill-id>/.init.json`（snake_case，与运行期契约一致）
  `{"status":"READY|FAILED|INITIALIZING","skill_id","setup_path","exit_code","started_at","finished_at","size_bytes","log_tail","message"}`
  （时间戳为 epoch 毫秒；`log_tail` 保留末尾 8KB）
- 工作区只由沙箱服务读写；Web/runtime 不直接访问该目录。
- 初始化不清理旧数据；「重建」需管理员在管理台显式确认后删除。

## 五、导入链路

### 5.1 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/admin/skills/import/git` | `{url, ref?, id?, overwrite?, dryRun?}` |
| POST | `/api/admin/skills/import/zip` | multipart：`file`、`id?`、`overwrite?`、`dryRun?` |
| GET | `/api/admin/skills/{id}` | 详情增加 `metadata`、`setup`、`data` 状态 |

- `dryRun=true` 只解析并返回：解析出的 id、元信息、文件清单、检测到的 setup、冲突项，不写 Nacos。
- 冲突：同 id 已存在且 `overwrite=false` 返回 409；覆盖只替换包与正文，**不删除**已初始化数据。
- 审计：`skill.import`（来源 URL/SHA 或 ZIP 文件名、文件数、总大小、是否覆盖）。

### 5.2 Git 导入

- 仅 `https://`，主机白名单 `PAYROLL_SKILL_IMPORT_HOSTS`（默认 `github.com,gitlab.com,gitee.com`）。
- 使用 JGit 浅克隆（`depth=1`，指定 `ref` 时按 ref 检出），不依赖宿主机 git。
- 克隆到临时目录，排除 `.git/**`、`data/**`、`node_modules/**`；包大小超 4MB 或文件数超 200 直接失败。
- 私有仓库与鉴权首期不支持；需要时后续加 `PAYROLL_SKILL_IMPORT_TOKEN`。
- 记录解析到的 commit SHA 到审计与详情，便于追溯导入版本。

### 5.3 ZIP 导入

- 单包 ≤ 20MB（排除 data 后仍需 ≤ 4MB 才能入库）。
- 解压前逐条校验：无绝对路径、无 `..`、无符号链接、无加密项；只允许一层顶层目录并自动剥离。
- 解压到临时目录后与 Git 导入共用同一解析器。

### 5.4 解析器（新增 `SkillProjectParser`）

输入目录 → 输出：

```java
record ParsedProject(
    String id,                 // 归一化
    Map<String,Object> metadata,
    String description,
    String body,               // SKILL.md 正文
    List<SkillFile> files,     // 白名单内文件
    String setupCommand,       // 可空
    long dataMaxBytes,         // 可空
    List<String> warnings)     // 如 activation 暂不生效、文件被忽略
```

规则：

1. `SKILL.md` 必需且正文 ≥ 200 字、含 Markdown 标题（沿用现有质量门槛）；
2. 根文件固定名之外的文件（如任意 `.md`）丢弃并告警；
3. 二进制或非 UTF-8 文件丢弃并告警；
4. `data/`、`.git/`、`node_modules/` 一律忽略；
5. 文本统一按 UTF-8 读取，入库前把 CRLF 归一化为 LF：Windows 检出的仓库常把脚本转成 CRLF，
   Linux 沙箱执行 `/bin/sh` 时会报 `bad interpreter`，归一化发生在解析阶段，Nacos 中保存的始终是 LF；
6. setup 缺省：存在 `setup.sh` → `sh setup.sh`（首行为 `#!/bin/bash` 时改用 `/bin/bash`，见 §6.1）；`setup` 显式声明时校验目标文件在包内。

## 六、初始化链路（沙箱）

### 6.1 容器

```
web --(HMAC)--> sandbox --(docker run, 一次性)--> init 容器
```

- 镜像：`payroll-sandbox-init:local`（python3 + node + git + curl + ca-certificates），新增 `docker/sandbox-init.Dockerfile`。
- 挂载：
  - `/skill`：包内根文件与 `scripts/**`（可写副本），`setup.sh` 在此执行；
  - `/skill/data`：工作区（rw），即 `PAYROLL_SANDBOX_SKILL_ROOT/<id>/data`；
  - `/work`：tmpfs 16MB（临时文件）。
- 解释器：按 setup 脚本首行 shebang 选择。`.py` → `python3 -B`，`.js` → `node`，
  `.sh` 声明 `bash`（`#!/bin/bash`、`#!/usr/bin/env bash`、`env -S bash -e`）→ `/bin/bash`，其余 → `/bin/sh`。
  第三方脚本常用 `BASH_SOURCE` 等 bash 语法，用 sh 执行会报 `bad substitution`。
- 网络：`--network bridge`（允许下载）；不注入任何令牌、数据库连接或宿主环境变量。
- 资源：CPU 1、内存 1GB、PID 128、超时 600s（硬上限 900s）、全局并发 2、同技能同时只允许 1 个初始化。
- 配额：每 5s 统计 `data/` 大小，超过 `data.max-bytes`（默认 2GB，硬上限 5GB）立即 kill 并标记 FAILED。
- 结束后：写 `.init.json`（状态、退出码、耗时、`du` 结果、日志尾 8KB），删除临时包副本，容器 `--rm`。

### 6.2 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/internal/skills/init` | `{skill_id, setup_path, setup_content, files:[{path,content}]}`，异步启动，返回状态 |
| GET | `/internal/skills/{id}` | 状态、大小、日志尾、退出码 |
| DELETE | `/internal/skills/{id}` | 删除工作区（管理台二次确认后调用） |

- 请求携带包内 `scripts/**` 与根文件（沿用"脚本内容随请求传输、沙箱不访问 Nacos"的既有边界）。
- 状态映射：`BUSY→429`、`NOT_CONFIGURED→503`、`INVALID→400`，`OK/FAILED` 返回 200。
- Web 侧 `SandboxGateway` 增加 `initSkill(...)`、`skillData(...)`、`deleteSkillData(...)`；`SandboxClient` 同步实现。

### 6.3 管理台

- 技能编辑弹窗新增「数据」页签：状态、大小、最后初始化时间、日志尾、「初始化 / 重建」按钮。
- 首次初始化与重建均需二次确认，文案明确"将执行第三方仓库脚本并联网下载数据"。
- 审计：`skill.init.start`、`skill.init.finish`（含状态、退出码、耗时、大小）、`skill.data.delete`。

## 七、运行链路

- `skill_run` 接口与确认流程不变：仍是脚本路径 + 参数、会话内强制确认（`ActionGuard`）。
- 沙箱执行容器新增挂载：`PAYROLL_SANDBOX_SKILL_ROOT/<id>/data` → `/data`（`ro`）；`scripts/**` 仍挂 `/src`（`ro`）；`/work` 保持 16MB tmpfs。
- 运行容器仍 `--network none`，不能联网。
- 技能脚本约定从 `/data` 读取；`PayrollAgentFactory` 注入指引补充：「技能数据目录为 `/data`（只读），由初始化任务生成」。
- 工作区不存在或状态非 `READY` 时，`skill_run` 返回明确错误：「技能数据未初始化，请先在技能管理台执行初始化」，不静默降级。
- 现有 `skill_read` 不变（仅读 `references/**`、`scripts/**`，8KB × 5 次/轮）；`assets/**` 不注入、不默认可读，避免上下文膨胀。

## 八、安全基线

| 面 | 规则 |
| --- | --- |
| 导入 | 仅管理员（`skill:manage`）；https + 主机白名单；ZIP 解压防护；审计来源与 commit |
| 初始化 | 独立容器、非 root、cap-drop、no-new-privileges；只允许写 `/skill` 与 `/work`；不注入秘密；限额限时；日志尾入库 |
| 运行 | 保持禁网、只读 rootfs、只读脚本与数据、tmpfs 工作区 |
| 数据 | 不进入 Nacos/数据库/模型上下文；只由沙箱访问 |
| 回滚 | 删除工作区即回到「未初始化」；包与数据分离，互不阻塞 |

## 九、配置项

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PAYROLL_SKILL_FILES_MAX_FILES` | `200` | 包内文件数上限 |
| `PAYROLL_SKILL_FILE_MAX_BYTES` | `524288` | 单文件上限（v2 调大） |
| `PAYROLL_SKILL_FILES_MAX_BYTES` | `4194304` | 包总量上限（v2 调大） |
| `PAYROLL_SKILL_IMPORT_HOSTS` | `github.com,gitlab.com,gitee.com` | Git 导入主机白名单 |
| `PAYROLL_SANDBOX_SKILL_ROOT` | `PAYROLL_SANDBOX_WORK_DIR/skills` | 技能工作区根目录（宿主与容器同路径） |
| `PAYROLL_SANDBOX_INIT_IMAGE` | `payroll-sandbox-init:local` | 初始化镜像 |
| `PAYROLL_SANDBOX_INIT_TIMEOUT_MS` | `600000` | 初始化超时，硬上限 900000 |
| `PAYROLL_SANDBOX_INIT_MAX_BYTES` | `2147483648` | 数据目录默认上限，硬上限 5GB |
| `PAYROLL_SANDBOX_INIT_CONCURRENCY` | `2` | 初始化全局并发 |
| `PAYROLL_SANDBOX_UPLOAD_MAX_FILE_BYTES` | `209715200` | 数据页上传单文件上限（200 MiB） |
| `PAYROLL_SANDBOX_UPLOAD_MAX_BYTES` | `1073741824` | 单个技能 `data/` 上传总量上限（1 GiB，硬上限 5 GiB） |

## 十、分期任务与验收

| 批次 | 任务 | 交付 | 验收 |
| --- | --- | --- | --- |
| P0-1 | 包格式 v2 与 frontmatter 保留 | `SkillFilePackage` v2、根文件白名单、`meta` 透传、限额调整 | 旧 v1 技能照常加载；保存正文后 `version/license/activation` 不丢失 |
| P0-2 | 技能项目解析器 | `SkillProjectParser`（目录 → 包）、setup/data 解析、告警 | 用 poetry-skills 仓库离线目录解析出 id=poetry、文件清单与 setup |
| P0-3 | Git / ZIP 导入 | 两个导入接口、dryRun、覆盖语义、审计 | GitHub 公共仓库导入成功；重名无 overwrite 返回 409；ZIP 穿越被拒 |
| P1-1 | 沙箱工作区与 init | `sandbox-init` 镜像、init 接口、状态机、限额、审计 | fixture 的 setup.sh 生成数据后状态 READY、大小正确；超限被 kill |
| P1-2 | 运行期数据挂载 | `/data` 只读挂载、未初始化错误、提示词指引 | 脚本读取 `/data` 返回结果；未初始化时明确报错；运行容器仍无法联网 |
| P1-3 | 管理台 | 导入入口、元信息展示、数据页签（初始化/重建/日志） | 网页完成导入 → 初始化 → 对话查询全链路；重建需二次确认 |
| P2-1 | 回归与端到端 | 单测、fixture 技能（setup + 检索脚本）、真实 poetry 仓库实跑 | `mvn` 全量通过；真实仓库可导入与初始化；带脚本 fixture 可对话查询 |
| P2-2 | 文档与部署 | README、交接文档、compose 增加 init 镜像与工作区卷 | 一键启动；配置项说明完整 |

约束：P0 不动沙箱；P1 只加 init 与挂载，不改变 `skill_run` 的确认与审计语义。

### 10.1 实跑记录（2026-11-16）

| 项 | 结果 |
| --- | --- |
| 全量回归 | `mvn -pl payroll-tests -am test`：668 tests，0 failure / 0 error |
| 真实仓库导入 | `https://github.com/Wscats/poetry-skills.git`（commit `5bcb37e3fe86c9acacbbf914adce3feb921848f8`）→ id `poetry`，根文件与 `scripts/**` 入库，`setup: sh setup.sh`、`data.max-bytes: 2GB` |
| 真实仓库初始化 | init 容器执行 `setup.sh`（`git clone --depth 1`）→ READY，data 约 291MB、1909 个 JSON 文件；包体积校验（4MB）与 data 无关 |
| 带脚本 fixture | ZIP 导入 `poetry-fixture` → init READY（13 字节）→ 以签名内部请求执行 `query.py 明月`，返回 2 条诗句，exit_code 0、337ms，验证 `/data` 只读挂载 |
| 实跑修复 | ① Windows 检出导致的 CRLF 脚本在沙箱报 `bad interpreter` → 解析阶段归一化 LF；② `#!/bin/bash` 脚本被 sh 执行报 `bad substitution` → 按 shebang 选解释器 |

部署侧同步：`docker/docker-compose.yml` 的 sandbox 服务新增 `PAYROLL_SANDBOX_SKILL_ROOT`、
`PAYROLL_SANDBOX_INIT_IMAGE` 与技能工作区卷，启动前需构建 `payroll-sandbox-init:local`（见文件头注释）。

## 十一、风险与回滚

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 第三方 setup 脚本任意执行 | 供应链风险 | 仅管理员触发、独立容器、只写工作区、不注入秘密、审计来源 commit；后续可加脚本白名单/哈希固定 |
| 大仓库耗尽磁盘 | 宿主机不可用 | 默认 2GB、硬 5GB、周期统计超限 kill、管理台可删 |
| Nacos 配置过大 | 发布失败 | 包上限 4MB；大数据强制走 data/ |
| 多沙箱节点数据不一致 | 节点间查询失败 | 首期按单沙箱部署；多节点时每节点独立初始化（文档注明） |
| 初始化网络不可用 | 初始化失败 | 状态 FAILED + 日志尾与退出码直接展示，可重试 |
| 回滚 | — | 删除工作区、删除导入包或覆盖回旧正文；运行期仅依赖脚本，不依赖数据即可降级 |

## 十二、不做的事

- 不把 data/ 或大文件写入 Nacos、数据库或提示词；
- 不允许运行期联网、访问宿主目录或其他技能的数据；
- 不新增平台级数据查询工具（D4：由技能脚本负责检索）；
- 首期不支持私有仓库鉴权、二进制包、`activation` 自动路由；
- 不在 agent 进程内启用 shell。
