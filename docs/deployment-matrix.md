# 部署矩阵（D3 / M3、C4-D7）

> 生产唯一形态：`prod` profile + MySQL + Redis + Nacos + 外部 ES/嵌入 + `PAYROLL_SECRET_KEY`。
> 启动期由 `TopologyGuard` 校验；默认 `payroll.guard.strict=warn` 只告警，切 `fail` 后缺项即停止启动。
> 本文是允许组合与验收命令的唯一出处。

## 1. 三档部署

| 档位 | 激活方式 | 数据库 | Redis | ES / 嵌入 | Nacos | 凭证密钥 | 适用 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 单机演示 | 默认（无 profile 或 `mysql`） | H2 文件库或本机 MySQL | 可无 | 可无（HASH 降级） | 可无 | 可无 | 本机开发、评审演示 |
| 单机演练 | 默认 + `payroll.guard.strict=fail` | 按所填配置校验 | 按所填配置校验 | 按所填配置校验 | 按所填配置校验 | 按所填配置校验 | 上线前演练 fail 档 |
| 集群生产 | `prod` + `PAYROLL_NODE_ROLE=web/runtime` | MySQL | 必须 | 必须为 http(s) 服务 | 必须 | 必须 | 多 Web / 多 runtime |

`prod` 通过 profile group 自动拉起 `mysql,redis`，不要手工再写 `--spring.profiles.active=prod,mysql,redis`。
`prod` 固定 `payroll.deployment.mode=cluster`，且禁止 `PAYROLL_NODE_ROLE=all`。

单机（`deployment.mode=single`）不校验生产组合（DB/Redis/Nacos/ES/凭证密钥），只保留管理员口令、
内部调用密钥与解析密钥三项检查；`node-role=all` 在单机只记 WARN，在 cluster 直接报错。

## 2. 守卫行为

`TopologyGuard` 启动时一次收集全部问题，再按 `payroll.guard.strict` 处理：

- `warn`（默认，过渡一个版本）：输出一条 `启动拓扑校验发现 N 项问题` 的 WARN，进程继续启动；
- `fail`：同一份清单以 ERROR 输出并抛异常，进程不进入服务态。

错误只说明「未配置 / 为默认值 / 不满足要求」，不回显任何取值。

以下组合会被判为问题：

- 生产拓扑（cluster 或 prod 时校验）：
  - H2（`jdbc:h2:`）或数据源为空；
  - 未激活 Redis（`spring.data.redis.host` 为空）；
  - Nacos 关闭或地址为空；
  - `payroll.kb.es-enabled=false`，或 ES / 嵌入地址不是 `http(s)://`；
  - 未设置 `PAYROLL_SECRET_KEY`（或不是 Base64 的 32 字节）；
- 全档位：
  - `payroll.auth.admin-password` 为空或仍为默认 `admin123`；
  - web/all 节点 `payroll.internal.token`（`PAYROLL_AGENT_RUNTIME_TOKEN`）为空；
  - `payroll.kb.parse.mode=remote` 时 web/all 节点 `payroll.kb.parse.token`（`PAYROLL_PARSE_TOKEN`）为空；
- 拓扑：
  - `prod` + `payroll.deployment.mode=single`；
  - cluster + `payroll.node-role=all`；
  - single + `payroll.node-role=all`：仅 WARN。

## 3. 环境变量清单（prod）

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 是 | 固定 `prod` |
| `PAYROLL_DB_URL` | 是 | MySQL JDBC URL，禁止 `jdbc:h2:` |
| `PAYROLL_DB_USER` / `PAYROLL_DB_PASSWORD` | 是 | 由部署环境注入，不写入仓库 |
| `PAYROLL_REDIS_HOST` / `PAYROLL_REDIS_PORT` | 是 | Redis 地址 |
| `PAYROLL_NACOS_ENABLED` | 是 | `true` |
| `PAYROLL_NACOS_SERVER` | 是 | Nacos 地址，可带 `/nacos` 上下文 |
| `PAYROLL_KB_ES_URL` / `PAYROLL_MEMORY_ES_URL` | 是 | 外部 ES 地址 |
| `PAYROLL_KB_ES_ENABLED` | 是 | `true` |
| `OLLAMA_BASE_URL` | 是 | 嵌入服务地址（http(s)） |
| `PAYROLL_SECRET_KEY` | 是 | Base64 的 32 字节 AES 主密钥 |
| `PAYROLL_ADMIN_PASSWORD` | 是 | 不得为默认 `admin123` |
| `PAYROLL_AGENT_RUNTIME_TOKEN` | 是 | Web 与 runtime 共享的 HMAC 密钥（web/all 节点必填） |
| `PAYROLL_PARSE_TOKEN` | remote 必填 | Web 调用 parser 的内部密钥 |
| `PAYROLL_DEPLOYMENT_MODE` | 是 | `cluster` |
| `PAYROLL_NODE_ROLE` | 是 | `web` 或 `runtime`（禁止 `all`） |
| `PAYROLL_GUARD_STRICT` | 否 | `warn`（默认）或 `fail` |
| `PAYROLL_SERVER_ADDRESS` | 否 | prod 默认 `0.0.0.0` |

## 4. 验收

```bash
SPRING_PROFILES_ACTIVE=prod \
PAYROLL_DEPLOYMENT_MODE=cluster PAYROLL_NODE_ROLE=web \
PAYROLL_ADMIN_PASSWORD=<non-default> \
PAYROLL_SECRET_KEY=<base64-32B> \
PAYROLL_NACOS_ENABLED=true PAYROLL_NACOS_SERVER=http://127.0.0.1:8848 \
java -jar payroll-backend/target/payroll-backend-exec.jar
curl -s http://127.0.0.1:8080/actuator/health
```

- `PAYROLL_GUARD_STRICT=warn`：缺项只输出 WARN 并启动成功，核对清单后补齐；
- `PAYROLL_GUARD_STRICT=fail`：缺少任一必填项即启动失败，日志为一条带编号清单；
- 全配置：启动成功，`/actuator/health` 为 `UP`，readiness 只包含 DB + Redis（ES/Nacos 不进摘流）。

集群示例（覆盖演示 compose，不改变默认档）：

```bash
PAYROLL_SECRET_KEY=<base64-32B> docker compose \
  -f docker/docker-compose.yml -f docker/docker-compose.prod.yml up -d --build
```

## 5. 回滚

- 守卫阻断启动：`PAYROLL_GUARD_STRICT=warn` 后重启。
- 单机生产回滚到演示档：去掉 `SPRING_PROFILES_ACTIVE=prod`，恢复默认 profile；H2 数据目录 `data/` 保留，不在回滚中删除。
- 集群回滚：先 `docker compose ... down`，再以原演示 compose 启动；MySQL/Redis 卷保留。
- `TopologyGuard` 只读环境，不写库，回滚无需数据修复。
