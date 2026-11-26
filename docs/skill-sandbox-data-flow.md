# 技能沙箱技术流程与数据流转

> 适用范围：`payroll-sandbox`（端口 8200）及调用它的 Web / agent-runtime 链路。
> 基线：`feature/dev-team` 分支，HEAD `bd42e39`。
> 相关文档：`docs/architecture.md`、`docs/multi-node-deploy.md`、`docs/nacos-skills`。

## 1. 结论先行

沙箱采用**两段式一次性容器**模型：

- **初始化阶段**：起一个**允许联网**、`data/` 可写的一次性容器，执行技能包里的 `setup.sh`，由脚本自己把数据下载到 `/skill/data`（宿主机 `<skillRoot>/<skillId>/data`）。初始化结束后只保留 `data/`。
- **执行阶段**：起一个**禁网**、只读、非 root 的一次性容器，把同一份 `data/` **只读**挂到 `/data`，脚本通过 `/data` 读取，脚本本体挂到 `/src`。

沙箱服务自身**不下载任何数据，也不访问 Nacos / Web 接口**；它只做校验、限流、调度 `docker run`。所有输入（脚本正文、初始化包）都随 HTTP 请求体传入，因此可复现、可审计。

## 2. 拓扑与职责边界

| 组件 | 端口 | 职责 |
|---|---|---|
| Web（payroll-backend） | 8080 | 技能管理、确认卡片、`skill_run` 提案执行、数据页签 API |
| agent-runtime | 8100 | 模型与工具；只产生执行提案，不执行脚本 |
| payroll-sandbox | 8200 | 校验 + 并发限流 + 调用宿主机 `docker` CLI |
| 宿主机 Docker daemon | — | sandbox 容器通过 `/var/run/docker.sock` 驱动，拉起兄弟容器 |

调用方向：`agent-runtime → Web（确认）→ sandbox → docker`。runtime 与 sandbox 之间没有直接信任关系，所有内部调用都走 HMAC。

## 3. 执行链路（skill_run）

```
用户提问
  └─ 模型调用 skill_run
      └─ SkillRunTools：校验路径/参数 → 生成执行提案（PENDING_CONFIRM），不执行
          └─ 前端确认卡片 → 用户确认
              └─ SkillRunActionContributor：按 skill_id 重新读取技能包与 scripts/**
                  └─ SandboxClient：HMAC 签名 POST /internal/sandbox/execute
                      └─ SandboxService：二次校验 + 全局/用户并发限流 + 数据就绪检查
                          └─ DockerSandboxRunner：docker run --network none ...
                              └─ 返回 exit_code / stdout / stderr / truncated
```

### 3.1 工具侧只提案

`SkillRunTools.run()` 仅做协议校验与技能解析，然后调用 `ActionGuard.intercept(...)` 返回 PENDING_CONFIRM；上下文缺少 `ActionGuard` 时直接拒绝执行（`payroll-common/.../agent/SkillRunTools.java:165`）。这是“写操作强制确认”约束的一部分。

### 3.2 确认后由 Web 重建脚本包

`SkillRunActionContributor.execute()` 用提案中的 `skill_id` 重新查技能、重新读取全部 `scripts/**` 内容再调用沙箱（`payroll-backend/.../SkillRunActionContributor.java:74-99`）。提案中不携带脚本正文，审计只落脚本路径、参数与 `script_sha256`，不落整段脚本和完整输出。

### 3.3 内部 HMAC 通道

`SandboxClient.send()` 对 `token + "\n" + timestamp + "\n" + METHOD + "\n" + path + "\n" + body` 签名，携带 `X-Payroll-Timestamp`、`X-Payroll-Internal`；两侧时钟偏移上限均为 120 秒（`payroll-common/.../service/SandboxClient.java:214-240`，`payroll-sandbox/.../SandboxController.java:44-52`）。沙箱未配置令牌时直接 503，不做匿名放行。

### 3.4 沙箱侧二次校验与限流

`SandboxService.execute()`（`payroll-sandbox/.../SandboxService.java:27-64`）：

1. 重新执行完整校验：技能 ID、脚本路径（只允许 `scripts/**.{py,sh,js}`）、参数（数组、无 NUL/换行、单参数 ≤200 字符）、脚本数量/总大小、目标脚本必须在包内；
2. `require_data=true` 时先检查工作区状态，非 READY 返回 409 `DATA_NOT_READY`；
3. **全局 4 并发 + 同一用户 1 并发**，超限返回 429 BUSY；
4. 通过后进入 `DockerSandboxRunner`。

沙箱侧显式声明“不信任运行期传入的任何路径或参数”，两侧各自校验（`payroll-protocol/.../sandbox/SandboxProtocol.java:13-16`）。

### 3.5 执行容器参数

`DockerSandboxRunner.command()`（`payroll-sandbox/.../DockerSandboxRunner.java:140-185`）：

| 维度 | 取值 | 目的 |
|---|---|---|
| 网络 | `--network none` | 运行期完全禁网，不能装依赖、不能回连内网 |
| 根文件系统 | `--read-only` + `/work` 16m tmpfs | 防止写坏宿主 / 持久化恶意文件 |
| 用户 | `65534:65534`，`--cap-drop ALL`，`no-new-privileges` | 非 root，无提权路径 |
| 资源 | `--memory 256m`、`--cpus 1`、`--pids-limit 64` | 防资源耗尽 |
| 脚本 | 宿主机临时目录 → `/src`，只读 | 脚本包每次重新写入，不落持久盘 |
| 数据 | `<skillRoot>/<id>/data` → `/data`，只读，目录存在才挂 | 只读消费初始化数据 |
| 超时 | 上限 10 秒；超时按容器名 `docker kill` | CLI 被杀也不残留容器 |
| 输出 | stdout/stderr 各限 8KB，超限置 `truncated=true` | 防上下文与日志被刷爆 |

数据目录路径**只由已校验的 skillId 推导**，不接受请求传入（`DockerSandboxRunner.java:188`），用于防目录穿越。

## 4. 数据如何“下载”（初始化链路）

### 4.1 触发入口

管理台技能「数据」页签调用：

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/admin/skills/{id}/data` | GET | 查询状态、数据体积、`log_tail` |
| `/api/admin/skills/{id}/data/init` | POST | 提交初始化；已 READY 时需 `overwrite=true` |
| `/api/admin/skills/{id}/data` | DELETE | 删除工作区 |

实现见 `payroll-backend/.../web/SkillAdminController.java:381`（查询）、`:392`（初始化）、`:456`（删除）。setup 路径优先取 frontmatter `setup`，否则回退根目录 `setup.sh`（`:396-400`）。初始化期间前端每 2.5 秒轮询，直到状态不再是 `INITIALIZING`（`frontend/src/views/admin/SkillsView.vue:206-226`）。

### 4.2 包落盘：pkg + data

沙箱把请求中的文件写入工作区（`SkillWorkspaceService`）：

```
<skillRoot>/<skillId>/pkg     临时包：SKILL.md / PROMPT.md / setup.sh / scripts/**
<skillRoot>/<skillId>/data    持久数据，初始化后唯一保留的目录
<skillRoot>/<skillId>/state   状态文件（INITIALIZING / READY / FAILED + 日志尾部）
```

- `execute()` 在 `finally` 中删除 `pkg`，只留 `data/`（`SkillWorkspaceService.java:207-241`）；
- 落盘前做路径越界检查，并限制单文件 512KB、总量 4MB、最多 200 个文件（`SkillInitProtocol.java:24-26`）；
- 初始化包只接受根目录固定文件（`SKILL.md`、`PROMPT.md`、`README.md`、`LICENSE`、`setup.sh`）与 `scripts/**`；`data/`、`references/`、`assets/` 不参与初始化（`SkillInitProtocol.java:36-37`）。

### 4.3 初始化容器：唯一允许联网的容器

`DockerSkillInitRunner.command()`（`payroll-sandbox/.../DockerSkillInitRunner.java:160-207`）：

| 维度 | 初始化容器 | 执行容器 |
|---|---|---|
| 网络 | **`--network bridge`（允许下载）** | `none` |
| 根文件系统 | `--read-only` + `/work`、`/tmp` tmpfs | `--read-only` + `/work` tmpfs |
| 技能目录 | `packageDir` → `/skill`（可写） | 脚本 → `/src`（只读） |
| 数据目录 | `dataDir` → **`/skill/data`（可写）** | `dataDir` → `/data`（只读） |
| 工作目录 | `/skill` | `/work` |
| 资源 | 1g 内存、1 CPU、128 进程 | 256m、1 CPU、64 进程 |
| 超时 | 默认 600s，硬顶 900s | 硬顶 10s |
| 数据配额 | 默认 2GiB，硬顶 5GiB | — |

镜像分工印证了该设计：

- init 镜像安装 `git`、`curl`、`nodejs`，用于拉取数据（`docker/sandbox-init.Dockerfile:11-13`）；
- runtime 镜像只装 `nodejs`，不装 pip/npm 包，运行期禁网（`docker/sandbox-runtime.Dockerfile:11-13`）。

典型 setup 脚本：

```bash
#!/bin/bash
set -euo pipefail
# 下载 / 克隆 / 解压到 /skill/data（即宿主机 <skillRoot>/<id>/data）
git clone --depth 1 https://example.com/dataset.git /skill/data/repo
curl -fsSL https://example.com/pack.json -o /skill/data/pack.json
```

解释器选择：`.py` → `python3 -B`；`.sh` 按首行 shebang 选择 `/bin/bash`（声明 bash 时）或 `/bin/sh`；`.js` → `node`（`SkillInitProtocol.java:103-111`）。

### 4.4 下载过程的约束

- **体积看门狗**：独立线程每 5 秒统计 `data/` 总字节数，超过 `max_bytes` 立即 `docker kill`，状态 FAILED，消息“数据目录超过上限…已终止”（`DockerSkillInitRunner.java:129-158`）。防止第三方仓库写满磁盘。
- **超时双保险**：CLI 超时后再按容器名 kill，避免 JVM 被杀而容器残留（容器名固定前缀 `payroll-skill-init-` + UUID）。
- **日志尾部 8KB** 回传管理台展示。
- **并发**：初始化线程池 2 个槽位；同一技能同时只允许一个初始化任务（`running` 集合），避免两个 setup 同时写同一 `data/`。
- **状态先落盘再启动容器**：请求返回后立刻查询也能看到 `INITIALIZING`（`SkillWorkspaceService.java:68-121`）。

### 4.5 完成后的状态

1. 删除 `pkg`，仅保留 `data/`；
2. 状态写 `READY`，记录 `setupPath / exit_code / size_bytes / log_tail / started_at / finished_at`；
3. 执行阶段 `require_data=true` 时先读状态文件，非 READY 直接 409 `DATA_NOT_READY`；工具层翻译为“请先在技能管理台执行初始化”（`SandboxService.java:67-80`，`SkillRunTools.java:151-158`）。

## 5. 完整数据流

```
① 技能包（代码）
   Nacos / MySQL skill store
        │  Web 读取技能包
        ▼
② 执行：脚本正文放入 HTTP body ──HMAC──▶ sandbox
                                        │ 写入临时目录 → /src（只读）
                                        ▼
                                容器内 /src/scripts/x.py
                                        │ 读取
                                容器内 /data（只读）
                                        ▲ bind
③ 数据：<skillRoot>/<id>/data ──────────┘

②' 初始化：包文件 ──HMAC──▶ sandbox ──写──▶ <skillRoot>/<id>/pkg
                                        │ bind：/skill（可写）、/skill/data（可写）
                                        ▼
                              init 容器执行 setup.sh（bridge 网络）
                                        │ git / curl 下载
                                        ▼
                              <skillRoot>/<id>/data（宿主持久）
                                        │ pkg 删除
                                        ▼
                                     READY
```

要点：**代码（脚本）每次执行都重新传输，数据（大文件）只下载一次**。这避免了每次执行搬运大量数据，也避免沙箱持有访问内网仓库的凭据。

## 6. 状态机与接口

内部接口（HMAC 保护）：

| 接口 | 方法 | 说明 |
|---|---|---|
| `/internal/skills/init` | POST | 提交初始化，异步返回 `INITIALIZING` |
| `/internal/skills/{id}` | GET | 查询状态 + 实时 data 体积 |
| `/internal/skills/{id}` | DELETE | 删除工作区（初始化中返回 BUSY） |
| `/internal/sandbox/execute` | POST | 执行脚本 |

状态与 HTTP 映射：

| 状态 | HTTP | 含义 |
|---|---|---|
| `INITIALIZING` | 200 | 初始化进行中 |
| `READY` | 200 | 数据就绪 |
| `FAILED` | 200 | 初始化失败，`message` / `log_tail` 给原因 |
| `BUSY` | 429 | 并发或同技能互斥 |
| `INVALID` | 400 | 参数非法 |
| `DATA_NOT_READY` | 409 | 声明了 setup 但数据非 READY |
| `NOT_CONFIGURED` / `UNAVAILABLE` | 503 | 未配置令牌 / 服务不可用 |

**残留自愈**：进程重启导致 `INITIALIZING` 成为孤儿时，超过 `initTimeoutMs + 60s` 的状态查询会将其标记为 FAILED「初始化任务已中断（沙箱重启或进程退出）」（`SkillWorkspaceService.java:165-180`）。

## 7. 部署要点

摘自 `docker/docker-compose.yml:274-296` 与 `SandboxSettings.java:23-35`：

1. sandbox 容器必须挂载 `/var/run/docker.sock`；它自身不执行用户脚本，只调用 `docker` CLI。
2. `PAYROLL_SANDBOX_WORK_DIR` 与 `PAYROLL_SANDBOX_SKILL_ROOT` 在宿主机与 sandbox 容器内必须是**同一绝对路径**，否则宿主机 Docker 看不到 bind 源目录（`SandboxSettings.java:114-117`）。
3. 需先构建两个镜像：`payroll-sandbox-runtime:local`、`payroll-sandbox-init:local`。
4. `PAYROLL_SANDBOX_TOKEN` 在 Web / runtime 与 sandbox 三处必须一致；不配置时整体 fail-closed。
5. 关键环境变量：`PAYROLL_SANDBOX_URL`、`PAYROLL_SKILL_EXEC_ENABLED`、`PAYROLL_SANDBOX_MAX_CONCURRENCY`、`PAYROLL_SANDBOX_INIT_TIMEOUT_MS`、`PAYROLL_SANDBOX_INIT_MAX_BYTES`、`PAYROLL_SANDBOX_INIT_CONCURRENCY`。
6. 本机直接运行（非容器）时，Windows 文件系统不支持 POSIX 权限设置，相关调用会被忽略；隔离仍由 `--user 65534` 与只读挂载保证。

## 8. 排障速查

| 现象 | 含义 | 处理 |
|---|---|---|
| `DATA_NOT_READY` / “技能数据未初始化” | 声明了 setup 但未成功初始化 | 数据页签执行初始化，查看 `log_tail` |
| setup 退出码 `125` | `docker run` 失败（镜像缺失/参数错误） | 检查镜像与 docker 权限 |
| 退出码 `137` | 容器被 kill（超时 / OOM / 超配额） | 查看状态消息；调整 `max_bytes` 或优化 setup |
| `BUSY` 429 | 全局 4 并发或同用户 1 并发占满 | 等待；必要时调整 `PAYROLL_SANDBOX_MAX_CONCURRENCY` |
| “沙箱内部签名校验失败” | 令牌不一致或时钟偏移超 120 秒 | 对齐令牌与 NTP |
| 状态长期 `INITIALIZING` | 多为进程重启留下的孤儿 | 等待超时窗口自动转 FAILED 后重建 |
| “初始化容器启动失败” | 退出码 125，stderr 首行给出 docker 原因 | 检查镜像名与 docker.sock |

## 9. 代码索引

| 主题 | 文件与方法 |
|---|---|
| 执行协议与限额 | `payroll-protocol/.../sandbox/SandboxProtocol.java:17-135` |
| 初始化协议与限额 | `payroll-protocol/.../sandbox/SkillInitProtocol.java:15-207` |
| 工具与确认 | `payroll-common/.../agent/SkillRunTools.java:95-178` |
| 提案执行器 | `payroll-backend/.../backend/SkillRunActionContributor.java:64-114` |
| HMAC 客户端 | `payroll-common/.../service/SandboxClient.java:63-240` |
| 执行接口 | `payroll-sandbox/.../SandboxController.java:35-62` |
| 校验与限流 | `payroll-sandbox/.../SandboxService.java:27-126` |
| 执行容器 | `payroll-sandbox/.../DockerSandboxRunner.java:42-192` |
| 初始化容器 | `payroll-sandbox/.../DockerSkillInitRunner.java:46-207` |
| 工作区生命周期 | `payroll-sandbox/.../SkillWorkspaceService.java:68-241, 349-370` |
| 初始化接口 | `payroll-sandbox/.../SandboxSkillController.java:38-93` |
| 管理台数据 API | `payroll-backend/.../web/SkillAdminController.java:381-483` |
| 数据页签前端 | `frontend/src/views/admin/SkillsView.vue:206-271` |
| 部署编排 | `docker/docker-compose.yml:274-296` |
| 运行/初始化镜像 | `docker/sandbox-runtime.Dockerfile`、`docker/sandbox-init.Dockerfile` |
