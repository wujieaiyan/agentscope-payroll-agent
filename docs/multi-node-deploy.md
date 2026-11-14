# 多节点部署（Web / agent-runtime 拆分 + Redis 共享记忆）

本文对应 `docker/docker-compose.yml` 的本地多节点演练拓扑，说明角色拆分、轮次隔离与续流、
语义缓存开关、容器化 Ollama 以及验证与回退步骤。设计细节见
[multi-node-agent-spec.md](multi-node-agent-spec.md)。

## 1. 拓扑

```text
浏览器 ──► frontend(Nginx) :18088 ──静态文件
                     └──/api──轮询──► web-1 :8080
                                  └──► web-2 :8080
web-1/web-2 ──HMAC 内部接口──► runtime-1 :8100
                           └──► runtime-2 :8100
web/runtime ──► MySQL :3306（会话、消息、chat_turns、审计）
web/runtime ──► Redis :6379（Session、会话锁、轮次租约、事件流、语义缓存精确键）
web/runtime ──► Ollama :11434（compose 内 ollama 服务）
```

| 服务 | 容器端口 | 宿主机端口 | 说明 |
| --- | --- | --- | --- |
| frontend | 80 | 18088 | Nginx 托管 Vue3 产物（SPA 回退），`/api` 轮询转发到 web-1/web-2，无粘性 |
| web-1 / web-2 | 8080 | 不暴露 | 鉴权、会话 API、SSE 转发与续流；不执行智能体 |
| runtime-1 / runtime-2 | 8100 | 不暴露 | 只在容器网络内提供 HMAC 内部接口 |
| mysql | 3306 | 13306 | Flyway 建表，库名 `payroll` |
| redis | 6379 | 16379 | AOF 持久化 |
| ollama | 11434 | 不暴露 | 复用宿主机模型目录（只读挂载） |

三个镜像分别对应三个 Dockerfile：`docker/frontend.Dockerfile`（Node 构建 + Nginx）、
`docker/backend.Dockerfile`（复制 `payroll-backend-exec.jar`）、
`docker/runtime.Dockerfile`（复制 `payroll-agent-runtime.jar`）。
Web 与 runtime 共享 `kb-data`（知识库上传）与 `output-data`（Excel 导出）两个卷，
否则多实例会各自写入容器本地文件。

Web 无状态：登录态在 Redis Session，消息与轮次在 MySQL，事件流在 Redis Stream；
任意 Web 节点都能接管同一会话，但同一时刻只允许一轮回答（Redis 会话锁 + 轮次租约）。

## 2. 角色与环境变量

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `PAYROLL_NODE_ROLE` | `all` | `all` 单进程；`web` 只转发/续流并拒绝 `/internal/*`（返回 404）；`runtime` 只执行内部轮次 |
| `PAYROLL_NODE_ID` | `local` | 节点标识，写入 `chat_turns.owner_node` 与日志 |
| `PAYROLL_AGENT_RUNTIME_URLS` | 空 | Web 调用的 runtime 地址，逗号分隔；为空时对话接口直接返回“智能体运行期未配置”，不做本机回退 |
| `PAYROLL_AGENT_RUNTIME_TOKEN` | 空 | 内部接口 HMAC 密钥；为空时内部提交不可用。生产环境必须替换默认值 |
| `PAYROLL_WEB_NODE_URLS` | `http://127.0.0.1:8080` | runtime 调用 Web 知识库内部接口的地址，逗号分隔；知识库工具注册在 runtime，执行与鉴权仍由 Web 完成 |
| `PAYROLL_KB_SKILL_ENABLED` | `true` | 是否向协同智能体注册 `kb_*` 工具；关闭后对话不提供知识库配置能力，页面功能不受影响 |
| `PAYROLL_INTERNAL_MAX_SKEW_SECONDS` | `120` | 内部请求时间戳允许偏差 |
| `PAYROLL_SERVER_ADDRESS` | `127.0.0.1` | 容器内必须设为 `0.0.0.0`，否则只监听回环、Nginx/Web 无法跨容器访问 |
| `MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED` | `true` | 未部署 ES 时设为 `false`，否则 `/actuator/health` 因 ES 探测失败返回 503 |
| `PAYROLL_TURN_LEASE_TTL_SECONDS` | `30` | 轮次租约 TTL；节点崩溃后最多 30 秒可被接管 |
| `PAYROLL_TURN_HEARTBEAT_SECONDS` | `10` | 租约续约间隔 |
| `PAYROLL_TURN_EVENT_TTL_HOURS` | `24` | 事件流保留时长（续流窗口） |
| `PAYROLL_TURN_EVENT_MAXLEN` | `10000` | 单个轮次事件上限（近似裁剪） |
| `PAYROLL_TURN_RETENTION_DAYS` | `90` | `chat_turns` 保留天数 |
| `PAYROLL_SEMANTIC_CACHE_ENABLED` | `false` | 语义缓存开关，仅 AGENT 路径无工具调用的稳定问答 |
| `PAYROLL_SEMANTIC_CACHE_THRESHOLD` | `0.95` | ES 向量召回余弦阈值 |
| `PAYROLL_SEMANTIC_CACHE_TTL_MINUTES` | `30` | 缓存有效期 |
| `PAYROLL_SEMANTIC_CACHE_MAX_ENTRIES` | `10000` | 内存 LRU 上限 |
| `PAYROLL_MEMORY_ENABLED` | `true` | 向量长记忆；容器内默认关闭（未部署 ES） |

`SPRING_PROFILES_ACTIVE=mysql,redis` 由 compose 统一注入；单体部署仍可用 `dept` 别名。

## 3. 轮次生命周期与跨节点语义

一轮对话的持久化坐标是 `chat_turns`（`turn_id`、`session_id`、`status`、`owner_node`、
`fencing_token`、`last_seq`），状态为 `RUNNING / SUCCEEDED / FAILED / CANCELED / EXPIRED`。

1. Web 节点 `beginTurn`：抢 Redis 会话锁 → 取租约（fencing token）→ 落用户消息与 `chat_turns` 行。
2. Web 把 `turn_id` 提交给 runtime（HMAC 签名）；runtime 校验归属与 fencing token 后接管租约，
   以 `owner_node` 记录来源节点，执行事件写入 Redis Stream（序号由 `INCR` 生成，单调递增）。
3. Web 通过 `XREAD BLOCK` 尾随事件并转发 SSE。阻塞读取单次上限 1 秒（小于 Redis 命令超时 2 秒），
   由外层循环累计等待，避免把“读超时”误判为 Redis 故障而永久退化为进程内事件。
4. 客户端断线后可用 `GET /api/chat/sessions/{sessionId}/turns/{turnId}/events?after_seq=N`
   从任意 Web 节点续流：先重放历史事件，再尾随新事件；重试由前端带上最后收到的 `seq`。
5. 节点崩溃：租约 30 秒过期；若长时间无事件（>60 秒）且轮次仍为 `RUNNING`，续流端把轮次标记为
   `EXPIRED` 并返回明确错误，避免前端无限等待。执行中的模型调用不会被迁移（CPU/模型实例无法序列化），
   迁移的是“会话与已产生的事件”，不是“正在推理的进程”。
6. 取消：`POST .../turns/{turnId}/cancel` 只写 Redis 取消标记；runtime 在阶段输出时停止追加事件，
   轮次正常返回后**不落库回答**并标记为 `CANCELED`。取消不能中断正在进行的模型推理，
   因此在 CPU 推理下最长要等到当前阶段（含后续阶段）结束才变为终态。

Web 节点在续流结束（done/终态/客户端断开）时会释放本机会话占用（`busy` 与本地锁记录），
因此同一节点可以立刻发起下一轮；远程执行时回答由 runtime 落库，Web 不重复保存。

## 4. 语义缓存

默认关闭（`PAYROLL_SEMANTIC_CACHE_ENABLED=false`）。开启后只缓存满足以下条件的轮次：

- 路由为纯 `AGENT` 对话，且本轮没有工具调用、没有写操作提案；
- 作用域包含 persona、模型 id、用户部门与权限指纹，避免跨部门/跨权限复用答案。

两级查找：Redis 精确键命中（问题文本完全相同）→ 内存 LRU；语义命中依赖 ES `dense_vector`
索引（`payroll-semantic-cache-v1`，768 维，`nomic-embed-text`），未部署 ES 时只启用精确命中。
命中时 SSE `done.cache` 返回标记，前端可展示“缓存命中”。

## 5. 容器化 Ollama

容器内运行 `ollama/ollama`，只读挂载宿主机模型目录（默认 `/mnt/d/ollma`）：

```yaml
volumes:
  - ${PAYROLL_OLLAMA_MODELS_DIR:-/mnt/d/ollma}:/models:ro
environment:
  OLLAMA_MODELS: /models
  OLLAMA_HOST: 0.0.0.0:11434
```

> Windows + WSL2 下，容器访问宿主机 `host.docker.internal:11434` 通常会被 Windows 防火墙拦截
> （组策略模式 `LocalFirewallRules N/A (GPO-store only)` 时本地放行规则不生效），
> 因此在 compose 网络内单独跑 Ollama，容器之间用服务名 `ollama:11434` 访问，不依赖宿主端口。

## 6. 构建与启动

```bash
# 宿主机（Windows Git Bash）：先产出可执行 jar
mvn -DskipTests package

# WSL2 内执行（Docker 引擎在 WSL 中）：三个镜像分别构建
cd /mnt/d/workspace/agentscope-payroll-agent
docker compose -f docker/docker-compose.yml up -d --build --remove-orphans

# 查看状态与日志
docker compose -f docker/docker-compose.yml ps
docker compose -f docker/docker-compose.yml logs -f web-1 runtime-1 frontend
```

前端镜像在构建阶段执行 `npm ci && npm run build`，产物由 Nginx 托管；
backend/runtime 镜像直接复制 `payroll-backend-exec.jar` 与 `payroll-agent-runtime.jar`，
因此必须先完成 `mvn package`。

首次启动 Ollama 容器后，若宿主机模型目录已有模型，`docker exec payroll-multinode-ollama-1 ollama list`
应能看到 `qwen2.5:3b-instruct` 与 `nomic-embed-text`。

访问 `http://127.0.0.1:18088`，默认账号 `admin / admin123`（首次建库时创建）。

停止与清理：

```bash
docker compose -f docker/docker-compose.yml stop
# 彻底清理（会删除 MySQL/Redis 数据卷，谨慎）
docker compose -f docker/docker-compose.yml down -v
```

## 7. 验证清单

```bash
# 健康检查（通过容器网络直连两个 Web，均为 UP）
docker compose -f docker/docker-compose.yml exec frontend \
  sh -c 'wget -qO- http://web-1:8080/actuator/health; echo; wget -qO- http://web-2:8080/actuator/health'

# 前端静态页与 API 代理
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:18088/
curl -s http://127.0.0.1:18088/api/agents/route-preview -X POST \
  -H "Content-Type: application/json" -d '{"message":"算一下张伟的工资"}'

# 角色隔离：/internal 不经 Nginx 代理（405 = 静态处理器拒绝 POST），Web 节点自身返回 404
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:18088/internal/turns
docker compose -f docker/docker-compose.yml exec -T web-1 \
  wget -S --post-data='{}' -O /dev/null http://127.0.0.1:8080/internal/turns 2>&1 | grep "HTTP/"

# 登录并创建会话
curl -s -c /tmp/cj -X POST http://127.0.0.1:18088/api/auth/login \
  -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'
curl -s -b /tmp/cj -X POST http://127.0.0.1:18088/api/chat/sessions \
  -H "Content-Type: application/json" -d '{}'

# 发起一轮（SSE，经前端负载均衡到任一 Web）
curl -s -N -b /tmp/cj -X POST http://127.0.0.1:18088/api/chat/sessions/1/stream \
  -H "Content-Type: application/json" -d '{"message":"帮我核算员工 E1001 本月工资"}'

# 断开后续流（turn_id 取自首个 turn 事件，可命中另一 Web 节点）
curl -s -N -b /tmp/cj \
  "http://127.0.0.1:18088/api/chat/sessions/1/turns/<turnId>/events?after_seq=0"

# 取消
curl -s -b /tmp/cj -X POST \
  http://127.0.0.1:18088/api/chat/sessions/1/turns/<turnId>/cancel
```

本地实测结论（2026-11-13，三镜像拆分后）：

- 经 `18088` 发起一轮，`turn` 事件 `owner_node=web-1`，runtime 执行并写入事件流，47 个事件完整到 `done`；
- `done.routing` 带路由决策（本轮问题命中 `AGENT`，置信度 0.90，`persona=payroll-accountant`），
  `/api/agents/route-preview` 对核算类问题返回 `target=pipeline`；
- 用 `after_seq=0` 通过同一入口重放该轮，收到 46 条历史事件并再次以 `done` 结束（可落在任一 Web 节点）；
- 取消：第二轮发出后取消，收到 `error`（`cancelled=true`，“本轮回答已取消”），无 `done`、不写入回答消息；
- 角色隔离：Web 节点自身 `/internal/turns` 返回 404；Nginx 不代理该路径（静态处理器对 POST 返回 405）；
- 底座停掉 runtime 后，Web 返回“智能体运行期不可达”，不再回退本机执行（拆分后 Web 无本机模型）。

## 8. 已知限制

- 容器内 Ollama 为 `ollama/ollama:0.12.11` 时，轻量模型调用会告警 `invalid option provided (maxTokens)`
  并回退规则兜底（动态团队分类降级，不影响金额与主链路）；升级 Ollama 镜像后消失。
- CPU 推理下单轮（三段流水线）约 1–3 分钟，`PAYROLL_CHAT_SESSION_LOCK_MINUTES`（默认 15）需大于
  最慢一轮耗时；会话锁随轮次结束释放，实例崩溃后按 TTL 过期。
- 跨节点迁移的是会话与事件流，不是正在推理的进程；runtime 崩溃后该轮会以 `EXPIRED` 收尾，
  用户重新提问即可由其它 runtime 接手。
- 语义缓存的向量召回需要 Elasticsearch；未部署时仅精确命中有意义。
