# payroll-protocol

## 职责

跨进程契约层：内部接口签名 + 沙箱协议。被 `payroll-common`、`payroll-backend`、`payroll-agent`、`payroll-sandbox`、`payroll-parser` 同时依赖，位于依赖图最底层。

**本模块只有 4 个文件，不新增业务类型。** 任何「顺手放个 DTO」的改动都会把契约层变成第二个 `payroll-common`。

| 文件 | 内容 |
| --- | --- |
| `service/InternalAuth.java` | HMAC-SHA256 签名与校验（Web ↔ runtime、Web ↔ parser） |
| `sandbox/SandboxProtocol.java` | 脚本执行请求/响应、脚本路径白名单与类型校验 |
| `sandbox/SkillDataProtocol.java` | 技能数据工作区读写协议 |
| `sandbox/SkillInitProtocol.java` | 技能初始化（setup）协议 |

## 契约（改动即破坏性变更）

- 签名串：`timestamp\nMETHOD\npath\nbody`，HMAC-SHA256 以 token 为密钥，十六进制输出。
- 请求头：`X-Payroll-Timestamp`（epoch 秒）、`X-Payroll-Internal`（签名）。
- 时间窗：`max(30s, 配置值)`，默认 120s；比较用 `MessageDigest.isEqual`（常量时间）。
- 沙箱响应体固定 snake_case：`exit_code` / `duration_ms` / `script_sha256`，两端都按这个字段名解析。

## 改这里的规则

1. 改签名算法、头名、时间窗语义 → 同一个 PR 内改完 Web、runtime、sandbox、parser 四端，并在 PR 描述里写「不兼容窗口」。
2. 新增协议字段 → 只加可选字段，两端都要能忽略未知字段。
3. 禁止引入 Spring；本模块要能独立打进 sandbox 与 parser。
4. 业务 DTO（员工、批次、审批……）一律放 `payroll-common`。

## 坑

- **只有时间窗，没有 nonce**（`InternalAuth.java:41-58`）：抓到一次请求可在窗口内重放。轮次侧靠 `turn_id` 唯一键兜底，沙箱与技能数据接口没有幂等键。
- body 参与签名，调用方必须**先序列化再签名**，重试时不能重新序列化（字段顺序或空白变化会导致签名不符）。
- `verify` 会对签名 `toLowerCase()`，大写签名也放行；但不对长度做校验，超长输入只靠比较耗时兜底。
