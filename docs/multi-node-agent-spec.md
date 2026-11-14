# 多节点智能体部署与语义缓存方案（Spec）

> 状态：待评审。所有原待确认项已按推荐值给出（D1–D12），可直接据此进入实施。
> 默认不改变现有单机行为：`PAYROLL_NODE_ROLE=all` 时与当前部署完全一致。

## 1. 背景

当前部署把「登录会话、对话编排、智能体运行、SSE 推送」放在同一个进程里。部门环境已支持
MySQL + Redis（`dept` profile），登录态共享、会话互斥、并发配额都能跨实例，但仍有三个问题：

1. **中途换节点会丢回答**：SSE 输出只存在于持有连接的进程内，节点宕机后浏览器收到的只是断线；
2. **故障恢复慢**：跨实例锁 `payroll:chat:lock:<sessionId>` 的 TTL 是 15 分钟
   （`SessionLockService.java:24`），进程崩溃后同一会话最长 15 分钟无法继续；
3. **智能体与 Web 无法独立扩容**：模型调用是长耗时、吃内存的部分，和静态资源/管理台接口绑在一起，
   扩模型吞吐只能整机扩容。

用户目标：把智能体拆分出来独立部署多个节点，节点间可互相替代，会话连续（A 节点发起、B 节点接着聊）；
用 Redis 做共享记忆；并评估语义缓存以降低 token 消耗、提升响应速度。

## 2. 目标

- 任意 Web 节点可接待任意会话，不依赖网关粘性，下一轮对话在 B 节点可无缝继续。
- 单轮回答期间节点宕机：浏览器重连到其他节点可续看已产生的输出，最终只落一条助手消息。
- 智能体执行可独立横向扩容，Web 层与执行层各自按负载扩缩容。
- 语义缓存：对稳定、只读的问题命中缓存，降低模型调用次数；默认关闭，可灰度。
- 保持现有单机/离线演示能力，Redis 不可用时退化为单机语义而不是直接不可用。

## 3. 非目标

- 不做多租户隔离改造（沿用现有部门/权限模型）。
- 不对已确认的写操作做跨节点自动重放；写操作仍走提案 + 人工确认。
- 不把完整对话历史搬进 Redis 作为唯一存储（Redis 是共享热态，不是持久真源）。
- 不在此方案内引入消息队列中间件（Kafka/RabbitMQ）；Redis Stream 足够覆盖当前量级。

## 4. 现状盘点

| 状态 | 当前位置 | 换节点是否可用 | 结论 |
| --- | --- | --- | --- |
| 登录会话 | Spring Session Redis（`application-redis.yml`，namespace `payroll:session`） | 可用 | 无需改动 |
| 消息、摘要、角色绑定、提案、追溯 | MySQL / H2（`chat_messages`、`chat_sessions`、`chat_action_proposals` 等） | 可用 | 已在库中，B 节点可按会话重建 |
| 会话上下文（摘要 + 最近轮） | `PayrollChatService.context`，构造时从库加载（`PayrollChatService.java:272`、`1489`） | 可用（延迟一轮） | 内存副本可随时丢弃 |
| 会话运行时对象 | `ChatSessionManager.chats` 进程内缓存（`ChatSessionManager.java:52`、`499`） | 不可序列化，但可按库重建 | 保留为性能缓存 |
| 智能体实例（HarnessAgent） | 进程级 `PayrollAgentFactory.sharedTeam` + `AgentCache` | 可重建 | 保留为性能缓存 |
| 同一会话互斥 | Redis `payroll:chat:lock:<sid>` TTL 15 分钟 + 进程内 `busy` | 会互斥但恢复慢 | 改为短租约 + 心跳 |
| 进行中的 SSE 输出 | 只在持有连接的进程/内存中（`ChatSse.Channel`） | 丢失 | 需要事件流 + 续流 |
| 对话并发配额 | Redis `payroll:chat:active:<uid>` | 可用 | 无需改动 |
| 长记忆（历史问题/摘要向量） | Elasticsearch + Ollama 嵌入 | 可用 | 与语义缓存共用基础设施 |

关键判断：**「A 节点聊一半，B 节点接着聊下一轮」在现有代码上已经基本成立**——会话消息与摘要都在库中，
B 节点首次访问会用 `chatFactory.create()` 从 `context_summary` + 最近消息重建上下文
（`ChatSessionManager.java:499-510`、`PayrollChatService.java:1489-1502`）。
真正缺的是：轮次所有权与故障接管、SSE 断线续流、物理拆分，以及语义缓存。

## 5. 目标架构

```
                    ┌───────────────┐
   浏览器 ──HTTPS──▶ │ 负载均衡/Nginx │  无粘性，轮询或最少连接
                    └───────┬───────┘
             ┌──────────────┼──────────────┐
             ▼              ▼              ▼
      ┌────────────┐ ┌────────────┐ ┌────────────┐
      │ web-node-1 │ │ web-node-2 │ │ web-node-N │  BFF/管理台/SSE 转发，无状态
      └─────┬──────┘ └─────┬──────┘ └─────┬──────┘
            │ 内部 HMAC     │              │
            └───────────────┼──────────────┘
             ┌──────────────┼──────────────┐
             ▼              ▼              ▼
      ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
      │ runtime-1    │ │ runtime-2    │ │ runtime-N    │  HarnessAgent/工具/流程/团队
      └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
             └────────────────┼────────────────┘
                              ▼
        ┌──────────┬──────────┬──────────┬──────────┐
        │ MySQL    │ Redis    │ ES       │ Ollama   │
        └──────────┴──────────┴──────────┴──────────┘
```

- **web 节点**：认证、权限、会话/消息查询、提案确认、SSE 转发、管理台静态资源。不直接跑模型。
- **runtime 节点**：加载 Agent 定义与技能、调用模型与工具、写消息/追溯/提案、写事件流。
- **共享层**：MySQL 真源；Redis 协调（租约、配额、事件流、精确缓存）与登录态；
  ES 长记忆 + 语义缓存；Ollama/DashScope 模型服务（每个 runtime 都能访问）。

## 6. 核心设计

### 6.1 进程角色拆分（D1）

同一仓库内先做「一份构建产物、两种角色」，再按需拆 Maven 模块：

| 角色 | 启动方式 | 说明 |
| --- | --- | --- |
| `all`（默认） | `--web` | 与现状一致，单进程完成全部工作，用于开发和单机部署 |
| `web` | `--role web` | 只提供 HTTP/SSE 与管理台，提交轮次给 runtime |
| `runtime` | `--role runtime --internal-port 8100` | 只提供内部轮次接口，不暴露公网 |

推荐理由：物理拆分立即获得独立扩容能力，同时避免第一步就做多模块重构带来的构建/依赖风险。
当 runtime 稳定后（P3）再把 `service`、`agent`、`tools`、`team`、`flow` 抽成 `payroll-runtime` 模块，
Web 只依赖 `payroll-core`。

### 6.2 状态分层（D2）

| 层 | 内容 | 生命周期 | 丢失影响 |
| --- | --- | --- | --- |
| MySQL | 会话、消息、摘要、角色、提案、追溯、`chat_turns` | 持久 | 不可接受，唯一真源 |
| Redis | 登录态、轮次租约、事件流、配额、语义缓存、版本号 | 分钟~小时 | 可重建/可降级 |
| 进程内 | `PayrollChatService`、HarnessAgent 实例、模型客户端 | 空闲淘汰 | 下一轮从库重建 |

结论：Redis 承载的是「共享工作记忆」，不是「对话记忆本身」。这样 Redis 淘汰或主从切换不会让
历史对话消失；同时 A→B 的连续性只依赖 MySQL，不依赖 A 的缓存是否还在。

### 6.3 轮次生命周期与所有权（D3/D4）

每轮对话对应一条 `chat_turns` 记录，`turn_id` 由 Web 节点生成（UUID）。

状态机：

```
RUNNING ──完成──▶ SUCCEEDED
   │
   ├──用户取消/断线超时──▶ CANCELED
   ├──执行异常─────────▶ FAILED
   └──节点崩溃租约过期───▶ EXPIRED（可按 D7 重跑）
```

租约：`SET payroll:chat:turn:<sessionId> <turnId>:<fencing> NX PX 30000`，
持有者每 10 秒 `PEXPIRE` 续约（Lua 校验 token）。所有写 `chat_turns` 的 UPDATE 都带
`WHERE status='RUNNING' AND fencing_token=?`，保证旧持有者即使恢复也无法覆盖新持有者的结果。

与现有 15 分钟锁的关系：`SessionLockService` 保留为兼容回退，但 P0 起主链路改用 30 秒租约；
`PAYROLL_TURN_LEASE_TTL_SECONDS` 可调。进程正常退出时 `finally` 主动释放；崩溃时最多 30 秒自动释放。

### 6.4 会话连续性（A → B）

1. 用户在 A 发问 → A 校验归属 → 获取租约 → 写入用户消息与 `chat_turns(RUNNING)` → 提交 runtime。
2. runtime 从 MySQL 重建会话上下文（摘要 + 最近轮）→ 执行 → 逐条写事件流 → 完成后在一个事务里
   写助手消息/追溯/提案并更新 `chat_turns(SUCCEEDED)` → 释放租约。
3. 用户在 B 继续发问 → B 获取租约（此时已空闲）→ runtime（可以是任意 runtime 节点）从库重建上下文 →
   正常回答。上下文包含 A 轮的消息与摘要，指代（「那他的社保呢」）可正确解析。
4. 若 A 轮仍在执行，B 收到请求直接返回 429「该会话正在生成回答」，不会并发生成两条回答。

### 6.5 事件流与 SSE 续流（D5/D6）

- runtime 把每个 SSE 事件写入 Redis Stream `payroll:chat:events:<turnId>`，
  字段：`seq`（单调递增）、`event`（start/text/tool_start/done/error…）、`data`（JSON）。
  `XADD` 后 `EXPIRE 24h`，并用 `MAXLEN ~ 10000` 控制单轮上限。
- Web 节点用 `XREAD BLOCK` 从 `after_seq` 开始尾随，转成浏览器 SSE 下发；客户端断开只停止尾随，
  runtime 继续执行并在库中落最终结果。
- 新增续流接口：`GET /api/chat/sessions/{sid}/turns/{turnId}/events?after_seq=N`。
  `stream` 接口的第一个事件改为 `turn`：`{turn_id, seq:0}`，前端记录 `turnId` 与 `lastSeq`
  （内存 + localStorage），`fetch` 中断后用 `after_seq` 重连；重连仍失败则回退到
  `GET .../messages` 拉取最终结果并提示「回答已完成」。
- 不做 `Last-Event-ID`：当前前端是 `fetch + ReadableStream`（`src/main/resources/static/app.js:3418`），
  显式 `after_seq` 更简单，也便于用 curl 排查。

### 6.6 执行组件拆分与内部接口

runtime 内部接口（仅内网，HMAC 校验头 `X-Payroll-Internal`，密钥 `PAYROLL_AGENT_RUNTIME_TOKEN`）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/internal/turns` | 提交轮次；body 含 `turn_id/session_id/user_id/message/fencing`；幂等：同 `turn_id` 重复提交直接返回 202 |
| POST | `/internal/turns/{turnId}/cancel` | 写取消键并在下一个阶段边界中止 |
| GET | `/internal/health` | 就绪探针，返回模型/MCP/Nacos 可用性 |

事件流只走 Redis，不让 runtime 与 web 之间再维护第二条 SSE 通道，避免「双链路状态不一致」。

取消：Web 写 `payroll:chat:cancel:<turnId>`（TTL 1 小时）；runtime 在每个阶段开始前与每次工具调用前
检查该键，命中后终止并写 `CANCELED`。不用 pub/sub，轮询一次 O(1) 且不会丢消息。

### 6.7 故障与接管策略（D7）

| 场景 | 行为 |
| --- | --- |
| Web 节点崩溃 | 浏览器重连任意 Web 节点，按 `turnId + after_seq` 续流；runtime 不受影响 |
| Runtime 节点崩溃（只读轮次） | 租约 ≤30s 过期后，同一 Web 请求或重试可重新提交一次；结果只保留第一次成功的助手消息 |
| Runtime 节点崩溃（含写提案） | 不自动重放；`chat_turns` 置 `EXPIRED`，前端提示重试。写操作只生成提案，不会自动执行，重跑不会造成重复写入 |
| 用户已点确认的执行 | 走 `ActionProposalService` 条件 UPDATE 幂等（`chat_action_proposals`），与轮次重跑解耦 |
| Redis 不可用 | 租约/配额/事件流退化为进程内实现，仅单实例语义；健康检查返回 degraded 并告警，禁止在此时横向扩容 |

### 6.8 Redis 键空间

| 键 | 类型 | TTL | 用途 |
| --- | --- | --- | --- |
| `payroll:session:*` | hash | 8h | Spring Session 登录态（已存在） |
| `payroll:chat:turn:<sid>` | string | 30s（心跳续） | 会话轮次租约，值为 `turnId:fencing` |
| `payroll:chat:events:<turnId>` | stream | 24h | 轮次事件日志，`seq` 递增 |
| `payroll:chat:cancel:<turnId>` | string | 1h | 取消标志 |
| `payroll:chat:active:<uid>` | string | 15m | 并发配额（已存在） |
| `payroll:chat:lock:<sid>` | string | 15m | 旧会话锁，P1 后仅作回退 |
| `payroll:cache:ver:<scope>` | string | 无 | 数据/配置版本号，变更时 INCR |
| `payroll:semcache:exact:<sha256>` | string | 30m | 语义缓存精确命中 |

### 6.9 数据模型：`chat_turns`

```sql
CREATE TABLE chat_turns (
  turn_id               VARCHAR(36) PRIMARY KEY,
  session_id            BIGINT NOT NULL,
  user_id               BIGINT NOT NULL,
  user_message_id       BIGINT,
  assistant_message_id  BIGINT,
  status                VARCHAR(16) NOT NULL,   -- RUNNING/SUCCEEDED/FAILED/CANCELED/EXPIRED
  owner_node            VARCHAR(64),
  fencing_token         BIGINT NOT NULL DEFAULT 0,
  last_seq              BIGINT NOT NULL DEFAULT 0,
  error_message         VARCHAR(500),
  started_at            TIMESTAMP NOT NULL,
  updated_at            TIMESTAMP NOT NULL,
  finished_at           TIMESTAMP NULL,
  INDEX idx_turn_session (session_id, started_at),
  INDEX idx_turn_status (status, updated_at)
);
```

同步加到 H2 `PayrollDatabase.SCHEMA` 与 MySQL `V4__chat_turns.sql`；保留 90 天后由启动清理任务删除。
事件本体不落 MySQL，Stream 过期后只保留最终助手消息与 `last_seq`，续流窗口由
`PAYROLL_TURN_EVENT_TTL_HOURS` 控制。

### 6.10 配置项

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PAYROLL_NODE_ROLE` | `all` | `all`/`web`/`runtime`，默认兼容现状 |
| `PAYROLL_AGENT_RUNTIME_URL` | `http://127.0.0.1:8100` | web 调用的 runtime 地址（可配多个，轮询） |
| `PAYROLL_AGENT_RUNTIME_TOKEN` | 空 | 内部接口 HMAC 密钥；角色拆分时必填 |
| `PAYROLL_INTERNAL_PORT` | `8100` | runtime 内部端口 |
| `PAYROLL_TURN_LEASE_TTL_SECONDS` | `30` | 轮次租约 TTL |
| `PAYROLL_TURN_HEARTBEAT_SECONDS` | `10` | 续约间隔 |
| `PAYROLL_TURN_EVENT_TTL_HOURS` | `24` | 事件流保留时长 |
| `PAYROLL_TURN_EVENT_MAXLEN` | `10000` | 单轮事件上限 |
| `PAYROLL_SSE_REPLAY_MAX_EVENTS` | `2000` | 单次续流最多重放条数 |
| `PAYROLL_TURN_RETENTION_DAYS` | `90` | `chat_turns` 保留天数 |
| `PAYROLL_SEMANTIC_CACHE_ENABLED` | `false` | 语义缓存总开关 |

### 6.11 可观测

日志统一前缀，便于按 `sessionId/turnId` 串起来：

- `[轮次] turn=… session=… owner=… 状态=RUNNING/SUCCEEDED/… 耗时=…ms`
- `[续流] turn=… afterSeq=… 重放=… 条`
- `[接管] session=… 旧租约过期，turn=… 由 node-… 重新提交`
- 指标（沿用 Actuator/Prometheus 规划）：轮次成功/失败数、租约过期数、续流次数、重放事件数、
  语义缓存命中率、平均模型耗时。

## 7. 语义缓存（D8–D11）

### 7.1 结论

**做，但限定范围，默认关闭。** 薪酬场景答案涉及金额与权限，全量语义缓存风险高；
只在「稳定、只读、与个人数据无关」的问题上启用，核算结果改用精确缓存（带数据版本）。

### 7.2 命中链路

```
用户问题
  → 归一化（去空白/标点、全角转半角、转小写）
  → Redis 精确键（scope + 问题哈希 + 版本）      ── 命中 → 返回
  → Ollama 嵌入（nomic-embed-text, 768 维）
  → ES kNN（scope 过滤 + score ≥ 0.95 + 未过期） ── 命中 → 回填精确键 → 返回
  → 未命中 → 正常执行 → 成功后按策略写缓存
```

### 7.3 作用域与键（D10）

```
scope = 部门/租户 | persona（pipeline/agent:<id>/flow:<id>）| 权限指纹 | 模型 id | 提示词/技能版本
scope_key = sha256(scope 排序拼接)
exact_key = payroll:semcache:exact:<sha256(scope_key | normalized_question)>
```

- 权限指纹取当前用户权限集合排序后的哈希：权限不同的用户不会互相命中，避免越权泄露。
- 模型/提示词/技能版本进 scope：Nacos 修改技能、切换模型后自动失效，不用手工清缓存。
- 不含 `user_id`，同一权限桶内共享缓存；个性化答案（「我的工资」）不进入语义缓存。

### 7.4 可缓存条件（白名单）

| 条件 | 要求 |
| --- | --- |
| 路由 | `AGENT` 政策/FAQ 问答、知识库检索问答；`FLOW`/`TEAM_ACTIVE` 首期不缓存 |
| 工具 | 无工具调用，或仅只读检索工具（`search_knowledge` 等） |
| 提案 | 本轮无 `action_proposal` |
| 文本 | 非空、非错误话术、长度 ≤ 4000 |
| 数据 | 回答不含实时金额；含金额的核算走精确缓存 |

精确缓存（核算结果）：key 含 `employee_id + period + data_version + 模型 id`，
TTL 10 分钟；`data_version` 来自员工/考勤/考核/批次表的版本号，任一导入或批次状态变化即 INCR 失效。
前端在答复上标注「缓存结果」，并且仍然展示当轮的追溯与金额依据（由确定性引擎重算或直接存库）。

### 7.5 存储选择（D11）

- **ES** 建独立索引 `payroll-semantic-cache-v1`：`scope_key`、`question_hash`、`question`、
  `vector`（dense_vector 768 / cosine）、`answer`、`model`、`prompt_version`、`data_version`、
  `created_at`、`expires_at`、`hit_count`。复用现有 `ElasticsearchVectorStore` 的连接与
  `EmbeddingClient`（`payroll.memory.embedding-*`），不引入新中间件。
- **Redis** 只存精确键 → ES 文档引用，TTL 30 分钟，扛住高频重复问题，避免每次都走 kNN。
- 未启用 ES 时语义缓存直接禁用（精确缓存仍可用 Redis 实现）。

### 7.6 失效

- 版本号：员工/考勤/考核/工资批次/知识库/Agent/Flow 写操作后 `INCR payroll:cache:ver:<scope>`；
  读写前比对版本，不一致即视为未命中并异步删除旧文档。
- 用户负反馈（现有 `chat_feedback`）命中缓存答案时，按 `question_hash` 删除缓存条目。
- 定时清理：每小时删除 `expires_at < now` 的文档，单次最多 1000 条，失败不阻塞主流程。
- 容量上限：`PAYROLL_SEMANTIC_CACHE_MAX_ENTRIES=10000`，按 `hit_count` 与 `created_at` 淘汰。

### 7.7 风险控制

- 阈值 0.95 起，命中时在 SSE `done` 中带 `cache: {type: "semantic", score: 0.97}`，前端显示
  「命中缓存」小标签，便于人工抽查。
- 缓存命中仍要经过权限与会话归属校验，且不计入「提案确认」流程（提案永不缓存）。
- 灰度：先只对知识库问答启用，观察一周命中率与负反馈率，再考虑扩展到政策问答。

## 8. 决策点汇总（全部按推荐执行）

| 编号 | 问题 | 推荐决策 |
| --- | --- | --- |
| D1 | 拆分形态 | 同仓双角色进程先行（`web`/`runtime`/`all`），稳定后再拆 Maven 模块 |
| D2 | 状态真源 | MySQL 为唯一真源；Redis 只做协调、事件流与热缓存 |
| D3 | 网关粘性 | 不做会话粘性，靠轮次租约保证同会话串行 |
| D4 | 轮次所有权 | 30 秒租约 + 10 秒心跳 + fencing token，条件 UPDATE 防覆盖 |
| D5 | 执行模型 | 异步轮次 + Redis Stream；保留 `sync` 兼容模式 |
| D6 | SSE 续流 | `turnId + after_seq` 重放，不使用 Last-Event-ID |
| D7 | 故障接管 | 只读轮次最多自动重跑一次；写操作不自动重放，仅重新生成提案 |
| D8 | 上下文共享 | 继续按 MySQL 重建（摘要 + 最近轮），Redis 不存全量对话 |
| D9 | 是否做语义缓存 | 做，默认关闭，白名单路由 |
| D10 | 缓存作用域 | 部门 + persona + 权限指纹 + 模型/提示词版本；不含 user_id |
| D11 | 缓存存储 | ES 向量索引 + Redis 精确键；阈值 0.95、TTL 30 分钟 |
| D12 | 降级策略 | runtime 不可达→本机执行（同角色，不算路由回退）；Redis/ES 故障逐项降级并告警 |

## 9. 实施阶段

### P0 无拆分多实例（4–6 人日）

- `chat_turns` 表（H2 + MySQL V4）、`TurnLeaseService`（租约/心跳/fencing）。
- 轮次提交改走租约；事件写 Redis Stream；续流端点；前端记录 `turnId/lastSeq` 并自动重连。
- 网关配置：无粘性、SSE 不缓冲（`proxy_buffering off`、`X-Accel-Buffering: no`）。
- 验收：2 个 Web 节点 + 共享 MySQL/Redis，杀节点后能续流。

### P1 拆分 agent-runtime（6–10 人日）

- 新增 runtime 启动入口与内部接口（HMAC）；web 只做鉴权/转发/查询。
- runtime 负责重建上下文、执行、落库、写事件流；web 从 Stream 尾随。
- 部署 2 web + 2 runtime，验证任意组合的 A→B 连续性与取消。

### P2 语义缓存（5–8 人日）

- 缓存策略、作用域、精确键、ES 向量索引、版本失效、指标与前端标签。
- 先知识库问答灰度，再扩政策问答；核算结果仅精确缓存。

### P3 混沌与文档（2–3 人日）

- `kill -9` 节点、Redis 主从切换、ES 不可用、runtime 全挂等场景演练。
- 部署文档（Nginx、探针、扩容步骤）、交接文档、压测报告。

总计约 3–4 周（单人估算），可按 P0→P1 先上线多实例，P2 独立灰度。

## 10. 验收标准

1. 2 个 Web 节点 + 2 个 runtime 节点，网关轮询无粘性；同一账号在两个节点交替提问，上下文连贯。
2. A 节点发起长回答，中途 `kill -9`；浏览器 5 秒内重连到 B，已输出内容重放，最终只有一条助手消息。
3. runtime 崩溃后租约 ≤40 秒释放，同一会话可继续提问；`chat_turns` 状态与实际一致。
4. 同一会话在两个节点同时提交，第二个立即收到 429，不产生并发回答。
5. 写提案轮次崩溃重跑不会执行写操作；确认接口保持幂等（沿用现有 351 个测试）。
6. 语义缓存：知识库问题集预热后命中率 ≥30%，P95 延迟明显下降；不同权限用户不串缓存；
   `action_proposal` 永不命中缓存；模型/技能变更后旧缓存自动失效。
7. Redis 停用后单节点功能完整，多节点场景明确返回降级告警，不出现静默并发。
8. 现有 351 个测试全部通过，新增测试覆盖租约、续流、缓存作用域与降级。

## 11. 测试计划

- 单元：`TurnLeaseService`（获取/续约/过期/旧持有者写失败）、`CacheScope`（权限指纹、版本拼接）、
  `CachePolicy`（白名单与排除项）、事件序列化（seq 单调、重放边界）。
- 集成：两个 Spring 上下文共享同一 MySQL/Redis 模拟 A/B 节点；`after_seq` 续流；
  租约过期后重新提交；取消键生效。
- 端到端：Nginx 2+2 部署脚本；`kill -9` 演练；Redis 重启演练；语义缓存命中率与负反馈抽样。
- 回归：现有 351 个测试 + 知识库/团队/审批链路的双节点冒烟。

## 12. 风险与回滚

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Redis 成为单点 | 租约/配额/续流失效 | 生产用 Sentinel/Cluster；降级到单实例并告警 |
| 重跑带来重复模型成本 | 费用上升 | 只读轮次最多自动重跑一次，其余等用户重试 |
| 语义缓存答错 | 答案与当前数据不符 | 阈值 0.95、作用域隔离、默认关闭、命中标签、负反馈删除 |
| 网关缓冲 SSE | 表现为「不流式」 | Nginx `proxy_buffering off` + 响应头 `X-Accel-Buffering: no` |
| `chat_turns` 膨胀 | 库增长 | 90 天清理任务；只存状态不存事件 |
| 内部接口暴露 | 越权执行 | 仅内网监听 + HMAC + token 轮换；不加入公网路由 |
| 首期拆分引入回归 | 对话不可用 | `PAYROLL_NODE_ROLE=all` 一键回退到现状；runtime 故障时本机执行兜底 |

回滚方式：`PAYROLL_NODE_ROLE=all` 恢复单进程部署；关闭 `PAYROLL_SEMANTIC_CACHE_ENABLED`
立即停用缓存；`chat_turns` 与事件流为旁路数据，停止使用不影响对话主链路。

## 13. 已知限制

- 事件流保留 24 小时，超时后无法续流，只能看最终消息。
- 跨节点「正在执行」的轮次不做实时迁移，崩溃后按 D7 重跑，不保证从断点阶段继续。
- 语义缓存首期只覆盖知识库/政策问答；流程与动态团队的编排结果不缓存。
- 精确缓存的金额结论由确定性引擎产生，但自然语言表述可能因模型温度有细微差异。
- 内部接口的 HMAC 只保证调用来源可信，不替代用户权限校验；runtime 仍以 `user_id` 做归属与权限判断。
