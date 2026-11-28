# payroll-sandbox

## 职责

技能脚本隔离执行服务（默认 8200）。**不依赖 `payroll-common`**，只依赖 `payroll-protocol`，通过 HTTP 内部接口被 Web 调用。

两类容器：
- **执行容器**：禁网、只读根盘，跑 `scripts/*.py|sh|js`；
- **初始化容器**：允许联网，只在技能 `setup` 时使用，产出 `data/` 工作区。

## 目录地图

| 类 | 职责 |
| --- | --- |
| `SandboxApplication` | 启动入口（8200） |
| `SandboxController` | 脚本执行入口，HMAC 校验 |
| `SandboxSkillController` | 技能维度的执行入口 |
| `SandboxDataController` | 数据工作区上传/删除 |
| `SandboxService` | 全局并发 4、单用户 1 的信号量限流 |
| `SandboxRunner` / `DockerSandboxRunner` | `docker run` 参数组装、超时 `docker kill` |
| `SkillInitRunner` / `DockerSkillInitRunner` | 初始化容器（bridge 网络） |
| `SkillWorkspaceService` | `data/` 工作区建立与大小统计 |
| `SandboxDataWriteService` | 上传落盘：临时文件 + 声明大小 + sha256 双校验 + 原子替换 |
| `SandboxSettings` | 环境变量解析（`PAYROLL_SANDBOX_*`） |

## 容器基线（改之前先读）

执行容器：`--network none` / `--read-only` / `/work` tmpfs 可写 / `--user 65534` / `--cap-drop ALL` / `--memory 256m` / `--pids-limit 64` / 超时默认 5s、硬上限 10s，超时按容器名 `docker kill`。

初始化容器：把 `--network none` 换成 bridge，其余隔离一致；`/skill` 是包副本，`/skill/data` 可写；超时 600s（硬上限 900s），数据目录 2GB（硬上限 5GB），每 5s 统计一次大小。

## 改这里的规则

1. 命令必须 `List<String>` + `ProcessBuilder`，**禁止拼 shell 字符串**（当前实现已经这样，不要退回去）。
2. 任何来自请求的路径都要过 `SandboxProtocol` 白名单：只允许 `scripts/**.{py,sh,js}`、`references/**`、`assets/**`；拒绝 `..`、反斜杠、控制字符。
3. 记住根盘只读：新增产物只能写 `/work` 与 `/data`，写别处会在运行期失败而不是编译期。
4. 新增执行入口必须加 HMAC 校验，未配置 token 时 fail-closed。
5. `PAYROLL_SANDBOX_WORK_DIR` 必须宿主与容器同路径（compose 已约束），否则挂载对不上。

## 坑

- 依赖宿主 docker socket（等同宿主 root 权限面），不要把沙箱暴露到不受信网络。
- **没有执行审计落库**：`SandboxResult` 已经产出 `script_sha256`，但没有任何表记录「谁在何时跑了哪个脚本」。这是已知 P2。
- 错误码需要与 Web 侧 `SkillRunActionContributor` 的映射一致，否则前端只会显示一句「执行失败」。
- 初始化失败的状态写在 `PAYROLL_SANDBOX_SKILL_ROOT/<id>/.init.json`（snake_case），改字段名会让运行期的「数据未初始化」判断失效。
