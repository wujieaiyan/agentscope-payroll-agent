# 部署矩阵（D3 / M3）

> 生产唯一形态：`prod` profile + MySQL + Redis + Nacos + 外部 ES/嵌入 + `PAYROLL_SECRET_KEY`。
> 启动期由 `TopologyGuard` 校验，缺项直接失败；本文是允许组合与验收命令的唯一出处。

## 1. 三档部署

| 档位 | 激活方式 | 数据库 | Redis | ES / 嵌入 | Nacos | 凭证密钥 | 适用 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 单机演示 | 默认（无 profile 或 `mysql`） | H2 文件库或本机 MySQL | 可无 | 可无（HASH 降级） | 可无 | 可无 | 本机开发、评审演示 |
| 单机生产 | `prod` | MySQL | 必须 | 必须为 http(s) 服务 | 必须 | 必须 | 单节点部门部署 |
| 集群生产 | `prod` + `PAYROLL_NODE_ROLE=web/runtime` | MySQL | 必须 | 必须 | 必须 | 必须 | 多 Web / 多 runtime |

`prod` 通过 profile group 自动拉起 `mysql,redis`，不要手工再写 `--spring.profiles.active=prod,mysql,redis`。

## 2. 禁止组合

以下组合会被 `TopologyGuard` 拒绝启动：

- `prod` + H2（`jdbc:h2:`）；
- `prod` + 未激活 `redis`（`spring.data.redis.host` 为空）；
- `prod` + Nacos 关闭或地址为空；
- `prod` + `payroll.kb.es-enabled=false`，或 ES / 嵌入地址不是 `http(s)://`；
- `prod` + 未设置 `PAYROLL_SECRET_KEY`（或不是 Base64 的 32 字节）；
- `prod` + `payroll.deployment.mode=single`（prod 固定 cluster）。

错误信息按项编号输出，并指向本文；进程以非 0 退出，不进入服务态。

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
| `PAYROLL_AGENT_RUNTIME_TOKEN` | 集群必填 | Web 与 runtime 共享的 HMAC 密钥 |
| `PAYROLL_SERVER_ADDRESS` | 否 | prod 默认 `0.0.0.0` |

## 4. 验收

单机生产：

```bash
SPRING_PROFILES_ACTIVE=prod \
PAYROLL_SECRET_KEY=<base64-32B> \
PAYROLL_NACOS_ENABLED=true PAYROLL_NACOS_SERVER=http://127.0.0.1:8848 \
java -jar payroll-backend/target/payroll-backend-exec.jar
curl -s http://127.0.0.1:8080/actuator/health
```

- 缺少任一必填项：启动失败，日志仅一条 `prod 拓扑校验失败（N 项）`；
- 全配置：启动成功，`/actuator/health` 为 `UP`，readiness 只包含 DB + Redis（ES/Nacos 不进摘流）。

集群示例（覆盖演示 compose，不改变默认档）：

```bash
PAYROLL_SECRET_KEY=<base64-32B> docker compose \
  -f docker/docker-compose.yml -f docker/docker-compose.prod.yml up -d --build
```

## 5. 回滚

- 单机生产回滚到演示档：去掉 `SPRING_PROFILES_ACTIVE=prod`，恢复默认 profile；H2 数据目录 `data/` 保留，不在回滚中删除。
- 集群回滚：先 `docker compose ... down`，再以原演示 compose 启动；MySQL/Redis 卷保留。
- `TopologyGuard` 只读环境，不写库，回滚无需数据修复。
