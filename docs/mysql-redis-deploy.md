# MySQL 与 Redis 部署（部门级）

本地启动默认启用 `mysql` profile（未设置 `SPRING_PROFILES_ACTIVE` 时连接本机 MySQL）；部门多人共用时再加 Redis：

- **MySQL**：业务数据持久化，Flyway 版本化建表，HikariCP 连接池。
- **Redis**：Spring Session 会话共享（重启/多实例不掉登录）、登录失败锁定、单用户对话并发配额、
  同一会话跨实例互斥锁。

H2 保留给测试（内存库）和显式回退（`SPRING_PROFILES_ACTIVE=` 置空），两种库共用同一套仓储代码。

## 1. 准备中间件

MySQL 8.0 与 Redis 7.x 需提前启动（本机 WSL Docker 或独立服务器均可）。创建库与账号：

```sql
CREATE DATABASE payroll DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'payroll'@'%' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON payroll.* TO 'payroll'@'%';
FLUSH PRIVILEGES;
```

表结构由应用启动时的 Flyway 自动创建，**不要手工建表**。脚本位于：

- `src/main/resources/db/migration/mysql/V1__payroll_core.sql`
- `src/main/resources/db/migration/mysql/V2__knowledge_base.sql`
- `src/main/resources/db/migration/mysql/V3__chat_routing.sql`：新增
  `chat_messages.routing_json`（TEXT）与 `chat_action_proposals` 表（写操作提案，
  含状态、有效期、快照哈希、执行结果与幂等键唯一索引）。`chat_action_proposals`
  与消息同事务写入，删除会话时一并清理；过期只改状态，不做独立 TTL 删除。

Flyway 未开启 `baselineOnMigrate`：目标库必须是空库，或已有 `flyway_schema_history` 且版本可继续。
指向“非空但没有 Flyway 历史表”的库（例如手工建过表的旧库）会直接启动失败并提示
`目标库必须为空库或已由 Flyway 管理`，以免跳过建表脚本、在缺列的表上写入。

## 2. 启动应用

`dept` 是 `mysql,redis` 的组合 profile：

```bash
# Linux / macOS / Git Bash
SPRING_PROFILES_ACTIVE=dept \
PAYROLL_DB_URL='jdbc:mysql://127.0.0.1:3306/payroll?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true' \
PAYROLL_DB_USER=payroll \
PAYROLL_DB_PASSWORD='<数据库密码>' \
PAYROLL_REDIS_HOST=127.0.0.1 \
PAYROLL_REDIS_PORT=6379 \
PAYROLL_WEB_PORT=8099 \
java -Dfile.encoding=UTF-8 -jar payroll-backend/target/payroll-backend-exec.jar
```

Windows CMD/PowerShell 用 `set` / `$env:` 设置同名环境变量；也可在 IDE 的 Run Configuration 中配置。

只切 MySQL、不要 Redis：

```bash
SPRING_PROFILES_ACTIVE=mysql ...
```

只加 Redis、仍用 H2（单机验证会话共享）：

```bash
SPRING_PROFILES_ACTIVE=redis ...
```

可用环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PAYROLL_DB_URL` | MySQL 本机 `payroll` 库 | JDBC URL，必须以 `jdbc:mysql:` 开头才会启用 Flyway 与连接池 |
| `PAYROLL_DB_USER` / `PAYROLL_DB_PASSWORD` | `payroll` / `payroll` | 数据库账号，生产必须覆盖密码 |
| `PAYROLL_DB_POOL_SIZE` | `16` | HikariCP 最大连接数 |
| `PAYROLL_REDIS_HOST` / `PAYROLL_REDIS_PORT` | `127.0.0.1` / `6379` | Redis 地址 |
| `PAYROLL_REDIS_PASSWORD` | 空 | Redis 密码 |
| `PAYROLL_REDIS_DB` | `0` | Redis 库号 |
| `PAYROLL_SESSION_TIMEOUT` | `8h` | 会话空闲超时 |
| `PAYROLL_SESSION_NAMESPACE` | `payroll:session` | 会话键前缀 |
| `PAYROLL_LOGIN_MAX_FAILURES` | `5` | 连续登录失败次数上限 |
| `PAYROLL_LOGIN_LOCK_MINUTES` | `5` | 触发上限后的锁定时长 |
| `PAYROLL_CHAT_MAX_CONCURRENT` | `2` | 单用户同时进行的对话数，超出返回 429 |
| `PAYROLL_CHAT_SESSION_LOCK_MINUTES` | `15` | 会话互斥锁 TTL；正常请求结束即释放，仅实例崩溃时依赖过期 |
| `PAYROLL_CHAT_CACHE_MAX_SESSIONS` | `200` | 进程内 `PayrollChat` 缓存上限，超出按最久未使用淘汰 |
| `PAYROLL_CHAT_CACHE_IDLE_MINUTES` | `60` | 会话缓存空闲淘汰时间；不超过 SSE 最长时长的条目不会被误关 |
| `PAYROLL_AGENT_CACHE_MAX` | `64` | 智能体运行期缓存上限 |
| `PAYROLL_AGENT_CACHE_IDLE_MINUTES` | `30` | 智能体缓存空闲淘汰时间；超上限时允许提前淘汰空闲 15 分钟以上的条目 |
| `PAYROLL_SERVER_ADDRESS` | `127.0.0.1` | 监听地址；跨机器访问需设为 `0.0.0.0` 并配置反向代理/防火墙 |

Redis 不可用时，登录限流、并发配额与会话锁自动退化为单进程内存实现，应用仍可启动；
此时多实例之间不共享状态，同一会话需由网关粘性转发或只部署单实例。

### 会话缓存与智能体缓存为什么不放 Redis

`PayrollChat` 与 `HarnessAgent` 都是带流式回调、TraceScope 和运行期状态的 Java 对象，无法序列化；
放进 Redis 也只能存配置而非实例。因此缓存保持在进程内并设上限/空闲淘汰，Redis 只保存
`payroll:chat:lock:<sessionId>` 这类协调键。缓存未命中时按数据库消息与 Nacos 定义重建，
多实例各自持有副本不会产生一致性问题。

## 3. 迁移存量 H2 数据

迁移是一次性工具，执行前**必须停止所有 Web 实例**（H2 单写者锁）。

```bash
# 先备份
cp data/payroll.mv.db data/payroll.mv.db.bak

# 目标库为空时可直接迁移
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-cli.jar --migrate-to-mysql

# 目标库已有数据时需显式覆盖
java -Dfile.encoding=UTF-8 -jar payroll-agent/target/payroll-agent-cli.jar --migrate-to-mysql --force
```

可选环境变量：

| 变量 | 默认值 |
| --- | --- |
| `PAYROLL_MIGRATE_SOURCE_URL` | `jdbc:h2:file:./data/payroll;DB_CLOSE_ON_EXIT=FALSE` |
| `PAYROLL_MIGRATE_SOURCE_USER` / `_PASSWORD` | `sa` / 空 |
| `PAYROLL_MIGRATE_TARGET_URL` | 本机 MySQL `payroll` 库 |
| `PAYROLL_MIGRATE_TARGET_USER` / `_PASSWORD` | `payroll` / `payroll` |

工具行为：

1. 先在目标库执行 Flyway，确保表结构是最新版本；
2. 按表读取 H2、`REPLACE INTO` 写入 MySQL，保留主键与自增序列；
3. 每张表写入后核对行数，不一致立即失败；
4. 目标表非空且未加 `--force` 时拒绝覆盖。

迁移完成后用 MySQL 启动应用，原有用户、会话、工资批次、知识库数据均可继续使用。

## 4. 验收清单

```bash
# 健康检查：database 字段应显示 jdbc:mysql://...
curl -s http://127.0.0.1:8099/api/health

# 确认 V3 已应用（交互式输入数据库密码）：
#   flyway_schema_history 应包含 3 - chat routing 且 success=1
#   chat_messages 应存在 routing_json 列，chat_action_proposals 表应存在
docker exec -it mysql mysql -upayroll -p payroll -e \
  "select version,description,success from flyway_schema_history order by installed_rank;"
docker exec -it mysql mysql -upayroll -p payroll -e \
  "show columns from chat_messages like 'routing_json'; show tables like 'chat_action_proposals';"

# 登录后端口令：应得到 200，且 Redis 出现 payroll:session:sessions:<id>
curl -c /tmp/c.txt -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' \
  http://127.0.0.1:8099/api/auth/login

# 指标：可抓取 Prometheus 指标
curl -s http://127.0.0.1:8099/actuator/prometheus | head

# 重启应用后复用同一个 cookie 访问，仍应为 200（会话已迁移到 Redis）
curl -b /tmp/c.txt http://127.0.0.1:8099/api/chat/sessions

# 发起一次对话，期间应出现会话锁，结束后自动消失：
docker exec redis redis-cli --scan --pattern 'payroll:chat:lock:*'
# 人为占用锁模拟其它实例，再发请求应收到“该会话正在其他实例上生成回答”
docker exec redis redis-cli SET payroll:chat:lock:<sessionId> other EX 60
```

## 5. 回退

将 `SPRING_PROFILES_ACTIVE` 置空（如 `SPRING_PROFILES_ACTIVE=`）并用原 H2 文件启动即可回退；迁移工具不会修改源 H2 文件。生产环境切换前请保留 H2 备份与 MySQL 逻辑备份（`mysqldump`）。
