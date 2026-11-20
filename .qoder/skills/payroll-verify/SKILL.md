---
name: payroll-verify
description: 在本仓库执行「编译 → 单测 → 定向测试 → 前端构建与测试 → 本地服务健康检查 → 内部接口探测 → 真实对话端到端」的验证流水线。当需要验证改动能否通过构建、排查构建/测试失败、确认 8080/8100/8200 服务状态、排查「智能体运行期不可达」或验证一次功能改动是否真正可用时使用。
---

# Payroll 验证流水线

## 概述

按由快到慢、由虚到实的顺序逐层验证，任一层失败立即停止并定位，不要跳层。

## 1. 编译

```bash
mvn -o -q -DskipTests package
```

- 必须离线 `-o`；本机已缓存全部依赖，联网反而会因代理失败。
- JDK 17（`D:\tools\jdk\jdk-17.0.12`），Maven 3.6.3（`D:\tools\apache-maven-3.6.3`），仓库无 `mvnw`。

## 2. 定向测试

```bash
mvn -o -pl payroll-tests -am test -Dtest=LlmUsageStoreTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

- `-am` 会让没有匹配测试的模块也跑 surefire，缺少 `failIfNoSpecifiedTests=false` 会报 "No tests matching pattern"。
- 新增测试文件在 `payroll-tests/src/test/java/io/agentscope/examples/payroll/`。

## 3. 全量测试

改动了共享逻辑（common/protocol、中间件、权限、存储层）时执行：

```bash
mvn -o test
```

基线：约 985 个测试，0 失败，3 跳过。数字变化要在汇报中说明原因。

## 4. 前端

```bash
cd frontend
npm run build
npm run test
```

E2E（需要浏览器）：

```bash
PLAYWRIGHT_BROWSERS_PATH=D:/playwright-browsers npm run test:e2e
```

E2E webServer 固定 `127.0.0.1:4173`；端口被占先停掉占用进程，不要改 baseURL 迁就。

## 5. 服务健康

| 端口 | 检查 |
| --- | --- |
| 8080 Web | `curl -s http://127.0.0.1:8080/actuator/health` |
| 8100 runtime | `curl -s http://127.0.0.1:8100/actuator/health` |
| 8200 sandbox | 容器/进程存在且端口监听 |
| 8880 nginx | `curl -sI http://127.0.0.1:8880/` 返回 200，页面来自 `frontend/dist` |

`/actuator/health` 为 UP **不代表**内部轮次可用，见下一步。

## 6. 内部接口探测

Web 调 runtime 走签名内部接口，失败不要先怀疑代码。用受保护的方式构造签名探测（不要把 token 打到终端或日志）：

```bash
# 时间戳 + HMAC-SHA256(token, "ts\nPOST\n/internal/turns\nbody")
# 期望：空 body {} → 400「缺少 turn_id 或 message」（说明鉴权/路由已通）
# 401 → token 不一致或时钟偏差 > 120s
# 超时 → runtime 可能以 Debug 模式启动并挂起
```

## 7. 真实对话端到端

1. 登录（管理员 `admin`，默认密码见 `PayrollCoreConfiguration` 的 `payroll.auth.admin-password`）拿 token。
2. 建会话、发消息。中文标题在 Git Bash 下易被编码破坏，调试时用 ASCII 标题并以 `--data-binary @file` 传 JSON。
3. 成功标志：接口返回 `SUCCEEDED` 且 `llm_usage` 表新增行（或看板 `/usage` 数据非零）。
4. 失败标志「智能体运行期不可达，请稍后再试」+ Web 日志 `[运行期] 提交失败 ... request timed out`：**先确认 runtime 是用 Run 而不是 Debug 启动**，这是已复现的环境问题，不是代码缺陷。

## 汇报格式

只写实测结果：命令、结论、未验证项及原因。不要写「应该没问题」。
