# 断点会话设计与实现指南

> 本文既是本仓库（`payroll-agent`）对话续传的完整实现说明，也是下次新项目落地「断点会话」时的复用手册。
> 核心结论：**用 SSE 传输、Redis Stream 做可重放事件日志、关系库存轮次状态与最终消息、租约 + 围栏令牌保证单写者，客户端只记住 `turnId + lastSeq`。**

---

## 0. 一句话原理

一次回答被建模为一个有生命周期的「轮次（turn）」：

1. 服务端为每轮生成 `turnId`，在数据库落 `chat_turns` 行，并向 Redis Stream 追加带单调序号的事件；
2. 每个 SSE 事件都带 `id: <seq>`，客户端持续记录已收到的最大序号；
3. 连接断开后，客户端带着 `after_seq` 重连，服务端从 Stream 中 `seq > after_seq` 的位置重放，再阻塞尾随新事件；
4. 轮次结束（`done` 或终态）后，最终答复以 `chat_messages` 为准，事件流只是可丢弃的传输缓存。

**恢复的是「输出」而不是「计算」**：模型/工具已经执行过的部分不会重跑，节点真正崩溃时只能把轮次标记为 `EXPIRED`，由用户重新提问。这是下面所有取舍的前提。

---

## 1. 目标与非目标

### 1.1 要解决的问题

| 场景 | 期望行为 |
| --- | --- |
| 网络闪断、代理 60s 超时 | 自动重连，从断点继续输出，文本不重复 |
| 页面刷新 / 切走再切回 | 自动找回进行中的轮次，重建气泡并回放 |
| Web 节点重启 | 换一个 Web 节点仍能继续转发同一轮事件 |
| 运行期节点崩溃 | 租约失效后判定中断，明确提示，不无限等待 |
| 多标签页 / 多实例并发提问 | 同一会话同一时刻只允许一个写者 |
| 用户点击停止 | 立即停止推流，本轮不落助手回答 |

### 1.2 设计目标

- **可重放**：事件有序号，可从任意断点重放。
- **单写者**：同一会话同一时刻只有一个执行者，旧持有者不能覆盖新结果。
- **最终一致**：流式内容允许缺失/重复，最终消息只认数据库。
- **可降级**：Redis 故障时退化为单机续传，不影响回答落库。
- **可运维**：事件有 TTL、有上限、可清理；故障有明确状态和日志。

### 1.3 非目标

- 不做「崩溃后从模型中间状态继续生成」——大模型调用不可重入，重跑会产生副作用和额外成本。
- 不做跨轮次的对话上下文恢复——上下文由 `chat_messages` + 摘要压缩负责，与断点续传正交。
- 不保证事件 exactly-once——序号可能因崩溃出现空洞，最终内容以 `done` 落库消息为准。

---

## 2. 技术选型

| 能力 | 选型 | 原因 |
| --- | --- | --- |
| 服务端到客户端推送 | **SSE**（`SseEmitter` / `fetch` + `ReadableStream`） | 单向流足够；原生支持 `id:` 与 `Last-Event-ID`；HTTP 语义对网关友好；比 WebSocket 简单，无需粘性会话 |
| 事件日志 | **Redis Stream** | 天然 append-only、按 ID 范围读取、支持阻塞读、`XTRIM` 限长、TTL 过期；多节点共享；无需消费者组 |
| 轮次状态 | **关系库 `chat_turns`** | 需要事务、条件更新、按会话查询进行中轮次；是跨节点的事实来源 |
| 最终消息 | **关系库 `chat_messages`** | 断点续传结束后唯一的展示依据；事件被裁剪也不影响最终答复 |
| 会话互斥 | **Redis `SET NX PX` 锁** | 跨实例互斥，TTL 兜底死锁 |
| 执行者租约 | **Redis `SET NX PX` + Lua 续约/释放** | 判断「模型慢」还是「节点死了」；令牌防止误删他人锁 |
| 防旧写者覆盖 | **围栏令牌（fencing token）** | 每会话单调递增，所有状态更新都带 `WHERE fencing_token = ?` |
| 客户端游标 | **`sessionStorage`** | 按标签页隔离，刷新后仍在；关闭标签页即失效，符合「本页续传」语义 |
| 自定义 POST 流 | **`fetch` 手写 SSE 解析** | 原生 `EventSource` 只支持 GET，无法提交消息体 |

### 2.1 为什么不用其它方案

| 方案 | 不采用的原因 |
| --- | --- |
| WebSocket | 双向能力用不上；多节点需要粘性会话或额外广播层；断线重连语义要自己实现 |
| Redis Pub/Sub | 只广播不存储，断线期间的事件永久丢失，无法重放 |
| Kafka | 为「每轮几十秒、万级事件」引入集群和分区管理，过重；Stream 的 TTL/限长更贴合 |
| 数据库轮询事件表 | 每个 token 都写库，写放大严重；轮询延迟高 |
| 服务端全量缓冲后一次性返回 | 失去流式体验，长回答首字延迟不可接受 |
| 崩溃后重跑 Agent | 工具可能已产生副作用（邮件、写操作），且成本翻倍 |

---

## 3. 数据模型与存储布局

### 3.1 关系库：轮次与消息

`chat_turns`（MySQL DDL 见 `payroll-common/src/main/resources/db/migration/mysql/V4__chat_turns.sql:4`，H2 由 `PayrollDatabase` 内联同构创建）：

| 字段 | 类型 | 作用 |
| --- | --- | --- |
| `turn_id` | VARCHAR(36) PK | UUID，贯穿 HTTP、Redis 键、SSE 游标 |
| `session_id` | BIGINT | 所属会话，联合索引 `(session_id, started_at)` |
| `user_id` | BIGINT | 归属校验，防止越权读别人的轮次 |
| `user_message_id` | BIGINT | 本轮提问在 `chat_messages` 中的 id |
| `assistant_message_id` | BIGINT | 成功后写入的助手消息 id；失败/取消为 0 |
| `status` | VARCHAR(16) | `RUNNING / SUCCEEDED / FAILED / CANCELED / EXPIRED` |
| `owner_node` | VARCHAR(64) | 发起轮次的节点标识，便于排障 |
| `fencing_token` | BIGINT | 每会话单调递增，条件更新的依据 |
| `last_seq` | BIGINT | 已写入的最大的**结构事件**序号（粗粒度游标） |
| `error_message` | VARCHAR(500) | 失败/过期原因，直接回显给前端 |
| `started_at` / `updated_at` / `finished_at` | TIMESTAMP | 生命周期与失联判定 |

索引：`(session_id, started_at)` 支持「找会话最近轮次」，`(status, updated_at)` 支持「扫描失联 RUNNING 轮次」。

`chat_messages`（`V1__payroll_core.sql:49`）保存用户消息、助手最终回答与错误消息。轮次结束后，前端一律以这里的消息为准，而不是拼接事件流。

**为什么 `last_seq` 不记录每个 token**：文本增量（`text` / `thinking` / `tool_args`）量级可达数千条，逐条 `UPDATE` 会把数据库打爆。实现只在结构事件（`start`、`phase`、`tool_start`、`done` 等）时更新游标（`ChatSessionManager.java:694`、`ChatSessionManager.java:768`）。因此 `last_seq` 只是「大致进度」，精确续传位置由 Redis 中的真实事件序号和客户端游标决定。

### 3.2 Redis：事件流、计数、租约、取消

| Key | 类型 | TTL | 写入方 | 作用 |
| --- | --- | --- | --- | --- |
| `payroll:chat:events:{turnId}` | Stream | 24h（可配） | 执行端 | 事件日志；字段 `event`、`data`(JSON)；记录 ID 为 `{seq}-0`；`XTRIM MAXLEN ~ 10000` |
| `payroll:chat:turnseq:{turnId}` | String | 24h | 执行端 | `INCR` 分配全局单调序号，作为 Stream 记录 ID |
| `payroll:chat:turn:{sessionId}` | String | 30s（心跳 10s 续期） | 执行端 | 轮次租约，值为 `turnId:fencing`，证明「执行节点还活着」 |
| `payroll:chat:cancel:{turnId}` | String `"1"` | 1h | Web 节点 | 取消标记，执行端每 500ms 轮询一次 |
| `payroll:chat:lock:{sessionId}` | String | 15min | Web 节点 | 会话级互斥锁，比轮次租约更粗，防止并发发起 |

键前缀定义：`RedisTurnEventBus.java:27-29`，`TurnLeaseService.java:28`，`SessionLockService.java:24`。

### 3.3 客户端：会话级游标

`frontend/src/stores/activeTurn.js`：

- 键：`payroll.activeTurn.{sessionId}`，存 `{turnId, lastSeq, startedAt}`；
- 写入 800ms 节流（`ChatView.vue:238`），避免每个 token 都写 `sessionStorage`；
- `lastSeq` 只增不减（`ChatView.vue:264`）；
- 24 小时过期（`activeTurn.js:2`）；
- 收到 `done` / `error`、取消、放弃、切换会话时清理（`ChatView.vue:256`）。

### 3.4 三种游标的职责边界

| 游标 | 存储 | 精度 | 用途 |
| --- | --- | --- | --- |
| Redis 事件序号 | Redis Stream | 每个事件 | 重放的唯一权威顺序 |
| `chat_turns.last_seq` | 数据库 | 仅结构事件 | `/turns/active` 告诉前端「大概到哪了」 |
| 客户端 `lastSeq` | `sessionStorage` | 每个收到的 SSE 事件 | 断线重连的精确起点 |

服务端取 `max(after_seq, Last-Event-ID)`（`ChatSessionController.java:444`），兼容自研 fetch 客户端和原生 `EventSource` 代理重连两种方式。

---

## 4. 端到端原理

### 4.1 首轮：提问到流式输出

```
Client            Web 节点                         Runtime 节点              Redis            MySQL
  | POST /stream      |                                 |                      |                |
  |------------------>| beginTurn:                      |                      |                |
  |                   |  requireOwned / busy.add        |                      |                |
  |                   |  turnId=UUID, fencing=MAX+1     |                      |                |
  |                   |  SETNX payroll:chat:turn:{sid}  |--------------------->|                |
  |                   |  INSERT user message            |                      |--------------->|
  |                   |  INSERT chat_turns RUNNING      |                      |--------------->|
  |                   |                                 |                      |                |
  |                   | POST /internal/turns (HMAC)     |                      |                |
  |                   |-------------------------------->| acceptRemoteTurn:    |                |
  |                   |                                 |  校验 turn/session/  |                |
  |                   |                                 |  fencing/RUNNING     |                |
  |                   |                                 |  takeover 租约       |                |
  |                   |<------------- 202 Accepted -----|  异步执行 chat.stream|                |
  |                   |                                 |                      |                |
  |                   | GET events?after_seq=0          | emit(event,data):    |                |
  |                   |  XREAD BLOCK 1s                 |  INCR seq            |                |
  |                   |<--------------------------------|--------------------->| XADD seq-0     |
  |<-- SSE id=1 ------| replay                          |  XTRIM / EXPIRE      |                |
  |<-- SSE id=2 ------|                                 |                      |                |
  |                   |                                 | onDone:              |                |
  |                   |                                 |  save assistant msg  |--------------->|
  |                   |                                 |  finish SUCCEEDED    |--------------->|
  |                   |                                 |  emit done           |                |
  |<-- SSE done ------|                                 |                      |                |
```

关键点：

- **提问先落库**：`beginTurn` 先插入用户消息与 `chat_turns`（`ChatSessionManager.java:466`），再提交给运行期。这样即使运行期不可达，轮次也有记录可查、可标记失败。
- **提交是幂等的**：运行期按 `turn_id + session_id + fencing + RUNNING` 过滤，并用进程内 `remoteExecuting` 集合去重（`ChatSessionManager.java:524`）。重复提交返回 409（`InternalTurnController.java:100`），不会启动两次模型。
- **事件先入总线再下推**：执行端通过 `ChatSse.busOnly` 只写总线（`ChatSse.java:32`），Web 节点从总线读取后转发。Web 与执行端因此完全解耦。
- **SSE 的 `id` 就是事件序号**：`ChatSse.publish` 写入 `seq` 字段并以 `seq` 作为 SSE id（`ChatSse.java:196`）。

### 4.2 事件写入：序号、记录 ID 与限长

`RedisTurnEventBus.append`（`RedisTurnEventBus.java:53`）：

1. `INCR payroll:chat:turnseq:{turnId}` 得到 `seq`（`:188`）；
2. 用显式 ID `{seq}-0` 写 Stream（`:64`），保证 Stream 顺序与业务序号一致；
3. `XTRIM MAXLEN ~ 10000` 限长、对事件流和计数器设置 24h TTL（`:68-70`）。

**序号分配与写入不是原子的**：进程若在 `INCR` 之后、`XADD` 之前崩溃，会留下一个空洞。续流端检测到 `seq > lastSeq + 1` 只记 WARN（`ChatSessionController.java:470`），不中断——因为最终内容以数据库消息为准。这是有意用「至少一次可用」换「实现简单」。

### 4.3 断线续传：重放 + 尾随

服务端 `resumeEvents`（`ChatSessionController.java:450`）循环执行：

1. `waitTurnEvents(..., block=10s, max=200)` 最终落到 `XREAD BLOCK`（单次最长 1 秒，见 4.4）；
2. 逐条 `ChatSse.replay`，把事件体与 `seq` 一起以 SSE `id:` 下发（`ChatSse.java:217`）；
3. 遇到 `done` 立即结束；
4. 没有新事件时检查数据库轮次状态，终态则补发 `error` 并结束；
5. 空闲 10 秒发一次 `ping`（`ChatSse.java:233`），**不带 id**，避免污染 `Last-Event-ID`；
6. 45 秒无事件时检查租约：租约在 → 认为模型慢，继续等；租约没了 → `EXPIRED`；
7. 单次连接硬上限 30 分钟。

客户端 `resumeLoop`（`ChatView.vue:488`）的退避序列是 `500ms → 1s → 2s → 4s → 8s`（`ChatView.vue:29`），每次重试前先查 `/turns/{turnId}`：

| 查到的状态 | 客户端动作 |
| --- | --- |
| `SUCCEEDED` | 清标记，拉取历史消息，结束（不重复拉流） |
| `FAILED / CANCELED / EXPIRED` | 清标记，展示服务端错误 |
| `RUNNING` | `cursor = max(cursor, turn.last_seq)`，带 `after_seq` 续流 |

这样即使「服务端已经完成但 `done` 事件在断线期间丢失」，客户端也不会重复播放。

### 4.4 为什么阻塞读被限制为 1 秒

`RedisTurnEventBus.readBlocking` 把单次 `XREAD BLOCK` 截断到 1 秒（`RedisTurnEventBus.java:34`、`:109`）。原因：Redis 命令超时默认 2 秒，如果 `BLOCK 10s`，客户端会先收到命令超时异常，进而被误判为 Redis 故障并触发永久降级。调用方用外层循环反复读取，达到更长的等待时间；真正的 `RedisCommandTimeoutException` 被识别为「暂无新事件」，返回空列表而不降级（`:139`）。

这是实现续传时非常容易踩的坑：**阻塞时长必须小于客户端命令超时**。

### 4.5 刷新与冷启动恢复

页面刷新后内存状态全部丢失，恢复流程（`ChatView.vue:542`、`:574`）：

1. `GET /turns/active` 查询会话当前 `RUNNING` 轮次（`ChatSessionController.java:394`）；
2. 有轮次 → 重建回答气泡，**从 `seq=0` 全量重放**（`ChatView.vue:591` 的 `rebuild` 分支）；
3. 没有 → 清理本地标记；服务端不可达时才退回本地 `sessionStorage` 标记；
4. 重放结束后若仍未完成，进入 `resumeLoop` 继续尾随；
5. 最终 `finalizeStream` 等待逐字打印队列吐完，再 `loadMessages(true)`，用数据库消息替换临时气泡（`ChatView.vue:627`）。

**为什么刷新时从 0 重放而不是从 `last_seq`**：DOM 里的文本已经丢失，只重放后半段会得到残缺回答；而 `last_seq` 又只记录结构事件，本来就不能作为精确起点。全量重放 + 前端 `lastSeq` 去重是最稳的组合。

### 4.6 取消

1. 前端 `POST /turns/{turnId}/cancel`（`ChatSessionController.java:419`）；
2. `ChatSessionManager.cancelTurn` 只写 Redis 取消标记（`ChatSessionManager.java:651`），不直接杀线程；
3. 执行端 `ChatSse` 每 500ms 轮询一次取消标记（`ChatSse.java:178`），停止追加 `text/thinking/tool_args`；
4. 模型返回后 `onDone` 发现已取消：助手回答不落库，轮次标记 `CANCELED`，发出 `error{cancelled:true}`（`ChatSse.java:150`）；
5. 前端收到后标记 `cancelled` 并清理本地游标。

选择「标记 + 协作式停止」而不是中断线程，是为了避免模型/工具在不可中断点被强杀，留下半截副作用。

### 4.7 多节点与故障接管

**正常情况下谁在写**：

- Web 节点负责鉴权、创建轮次、转发 SSE；
- Runtime 节点负责执行模型、写事件、落最终消息；
- 租约由 Runtime 持有并每 10 秒续约（`TurnLeaseService.java:196`），令牌为 `turnId:fencing`；
- Web 节点只读事件流，不参与续约。

**Runtime 崩溃**：租约最多 30 秒过期；Web 的续流循环 45 秒无事件且 `leaseAlive=false` 时，把轮次标记 `EXPIRED` 并提示重新提问（`ChatSessionController.java:512`）。

**Web 崩溃**：Runtime 不受影响，继续写 Redis Stream 和数据库。用户刷新后由另一个 Web 节点按 `after_seq` 续流即可，这是把事件放在 Redis 而不是 Web 内存的根本原因。

**Web 提交后立即崩溃**：轮次已处于 `RUNNING` 且 Runtime 已接管；新 Web 节点通过 `/turns/active` 找回并续流。

**启动清理**：每个节点启动时把 `updated_at` 早于 `max(60s, 2 × 租约TTL)` 的 `RUNNING` 轮次标记为 `EXPIRED`（`PayrollCoreConfiguration.java:499`），避免历史失联轮次永久占用会话。

**不会发生的事**：另一个 Runtime 不会自动「接手重跑」一个已开始的轮次。同一 `turn_id` 的重复提交会被 409 拒绝；这是「恢复输出不恢复计算」的直接体现。

### 4.8 围栏令牌：防止旧持有者覆盖

每轮开始时取该会话历史最大 `fencing_token + 1`（`H2ChatTurnStore.java:113`），之后：

- 结束轮次：`UPDATE ... WHERE turn_id=? AND status='RUNNING' AND fencing_token=?`（`H2ChatTurnStore.java:147`）；
- 更新游标：同样带 `status='RUNNING' AND fencing_token=?`（`:128`）；
- 续约/释放租约：Lua 脚本比较 token 后再 `PEXPIRE` / `DEL`（`TurnLeaseService.java:29-38`）。

因此即使旧节点在租约过期后「复活」，它的写回也会因为令牌不匹配而影响 0 行，不会覆盖新节点的结果。

---

## 5. 一致性边界与已知取舍

| 问题 | 当前处理 | 影响 |
| --- | --- | --- |
| `INCR` 成功但 `XADD` 失败 | 序号空洞，续流端 WARN | 可能少一段增量；`done` 后以数据库为准 |
| 事件超过 10000 条或被 TTL 清理 | 重放不完整 | 前端只能拿到部分内容；最终消息仍完整 |
| Redis 故障 | 退化为进程内总线（`InMemoryTurnEventBus`） | 仅同节点可续流；跨节点恢复失效 |
| 保存助手消息失败 | 轮次标记 `FAILED` | 用户看到错误，可重新提问 |
| `last_seq` 更新失败 | 只记 debug 日志 | 只影响 `/turns/active` 的粗游标，不影响重放 |
| 客户端 `sessionStorage` 被禁用 | 失去刷新恢复能力 | 流式回答本身不受影响 |
| 客户端游标落后于实际 | 重放少量重复事件 | 前端按 `lastSeq` 取最大值并去重 |
| SSE 连接 10 分钟超时 | 客户端自动重连续流 | 单次连接 30 分钟硬上限按连接计算，跨连接会重新计时 |

> 若新项目要求「零丢失」，需要把事件日志升级为持久化消息队列 + 消费位点，并接受更高的运维成本；对本类对话产品，数据库最终消息已足够兜底。

---

## 6. 接口契约

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/chat/sessions/{id}/stream` | 首轮流式回答；首个事件为 `turn`（含 `turn_id`） |
| GET | `/api/chat/sessions/{id}/turns/active` | 当前 `RUNNING` 轮次；刷新后先查这里 |
| GET | `/api/chat/sessions/{id}/turns/{turnId}` | 轮次状态，用于重连前判定 |
| GET | `/api/chat/sessions/{id}/turns/{turnId}/events?after_seq=N` | 重放并尾随，直到 `done` 或终态 |
| POST | `/api/chat/sessions/{id}/turns/{turnId}/cancel` | 取消本轮 |
| POST | `/internal/turns` | Web → Runtime 提交轮次（HMAC，202/409） |

### 6.1 SSE 事件

| 事件 | 关键字段 | 说明 |
| --- | --- | --- |
| `turn` | `turn_id`, `user_message_id`, `owner_node` | 首轮建立游标 |
| `start` | `model` | 模型开始 |
| `agent_start` / `agent_text` / `agent_end` | `agent`, `label`, `text` | 多智能体时间线 |
| `thinking` | `text` | 思考增量 |
| `phase` | `label` | 编排阶段 |
| `context` | `compressed`, `kept`, `summarized` | 上下文压缩提示 |
| `tool_start` / `tool_args` / `tool_result` | `tool_call_id`, `name`, `state`, 追溯字段 | 工具调用 |
| `text` | `text` | 最终回答增量 |
| `action_proposal` | `proposal` | 写操作待确认 |
| `done` | `reply`, `message_id`, `tools`, `routing`, `proposals` | 成功终态，落库完成 |
| `error` | `message`, `cancelled` | 失败 / 取消 |
| `ping` | `after_seq`, `ts` | 心跳，**无 SSE id** |

所有真实事件都同时带 SSE `id: <seq>` 和 JSON 体 `seq` 字段；前端解析时若负载缺 `seq`，用 SSE id 兜底（`frontend/src/api/sse.js:50`）。

---

## 7. 前端状态机

```
idle ──发送──> streaming ──断线──> recovering ──成功──> streaming
                  │                    │
                  │                    ├─5次失败/不可恢复──> resumable（保留气泡，可手动重试）
                  ├─done/error/cancel──> idle（以数据库消息收尾）
                  └─刷新/切回────────────> 重建气泡，从 0 重放
```

实现要点：

- **先查状态再续流**：避免对已经 `SUCCEEDED` 的轮次重复拉流；
- **游标只增不减**：`turnState.lastSeq = max(current, data.seq)`；重放 `turn` 事件时若 `turn_id` 相同，不把游标重置为 0（`ChatView.vue:377`）；
- **退避重试**：5 次（0.5s/1s/2s/4s/8s），仍失败则保留气泡并提供「重试续传 / 放弃本轮」；
- **在线事件与可见性**：`online` 与 `visibilitychange` 触发自动重试（`ChatView.vue:887`）；
- **禁止并发**：有未完成回答时前端禁止发送，服务端 `busy` / 会话锁同样会拒绝；
- **收尾以数据库为准**：成功后清空流式气泡，重新加载消息列表，保证只显示一条助手消息。

---

## 8. 下次项目落地清单

按顺序实现，每步都可独立验证：

1. **建表**：`chat_turns` 13 个字段 + 两个索引；消息表保留 `role` 以区分用户/助手/错误。
2. **定义轮次状态机**：`RUNNING → SUCCEEDED | FAILED | CANCELED | EXPIRED`，提供 `terminal(status)` 判断。
3. **选事件存储**：优先 Redis Stream；键按 `events:{turnId}` 隔离；TTL 大于产品承诺的续传窗口；`MAXLEN` 限长。
4. **序号分配**：`INCR` 生成，Stream 记录 ID 用 `{seq}-0`；接受崩溃空洞，重放端做不连续告警。
5. **单写者**：会话级 `SET NX PX` 锁 + 轮次级租约；租约 TTL ≥ 3 × 心跳，心跳 ≤ TTL/2。
6. **围栏令牌**：每会话单调递增；所有状态更新、租约续约/释放都校验令牌。
7. **提交接口幂等**：校验 `turnId + sessionId + fencing + RUNNING`；重复提交返回 409；异步执行返回 202。
8. **SSE 契约**：真实事件 `id=seq`；心跳不带 id；续流接受 `after_seq` 与 `Last-Event-ID` 取最大值；先重放、后阻塞尾随。
9. **续流守护**：不连续告警、终态兜底、空闲心跳、无事件时查租约、单连接硬上限。
10. **客户端**：`sessionStorage` 存 `{turnId,lastSeq,startedAt}`；写节流；先查状态再重连；指数退避；刷新从 0 重放；成功以数据库消息收尾。
11. **清理策略**：启动扫描失联 `RUNNING`；按保留期删除历史轮次；删除会话时级联删除轮次。
12. **可观测与测试**：记录 turnId/sessionId/seq/owner；覆盖并发拒绝、续流、取消、过期、接管、事件裁剪等场景。

### 8.1 参数怎么定

| 参数 | 取值原则 | 本仓库默认 |
| --- | --- | --- |
| 事件 TTL | ≥ 产品承诺的续传窗口；越长 Redis 占用越大 | 24h |
| 事件上限 | 覆盖 p99 事件数，防止单轮打爆内存 | 10000 |
| 租约 TTL | ≥ 3 × 心跳；节点崩溃后的最长接管等待 | 30s |
| 心跳间隔 | TTL 的 1/3 ~ 1/2 | 10s |
| 无事件宽限 | > 租约 TTL，避免把「慢」误判成「死」 | 45s |
| 单连接硬上限 | > p99.9 回答耗时；SSE 容器超时到达后由客户端重连 | 30min（容器 10min） |
| 重放批量 | 单次内存可控，避免一次拉太多 | 200 |
| 阻塞读时长 | **必须小于 Redis 命令超时** | 1s（超时 2s） |

### 8.2 必须补的测试

- 同一会话并发 `beginTurn`，只有一个成功；
- 事件写入后按 `after_seq` 重放，序号与内容一致；
- 断线重连不重复、不丢关键事件；
- 取消后助手消息不落库，状态为 `CANCELED`；
- 旧令牌写回被拒绝（fencing 生效）；
- 失联轮次启动时被标记 `EXPIRED`；
- Runtime 接管后重复提交返回 409；
- Redis 不可用时退化为单机总线且不影响落库。

本仓库对应测试见 `payroll-tests/src/test/java/io/agentscope/examples/payroll/service/ChatTurnLifecycleTest.java`。

---

## 9. 容量估算

单轮 Redis 占用近似：

```
事件条数 × (字段开销 + JSON 长度) ≈ N × 150B ~ 300B
```

- 典型回答 500 ~ 2000 个增量事件，单轮约 0.1 ~ 0.6 MB；
- 1000 个并发进行中的轮次约 100 ~ 600 MB；
- TTL 24h 后自动释放，`MAXLEN` 兜底极端长回答；
- 数据库只写结构事件（每轮约 10 ~ 50 次 `UPDATE`），写放大可控。

如果并发量更大，优先缩短事件 TTL、降低 `MAXLEN`，或把文本增量合并为更大批次再入总线；不要取消 `MAXLEN`。

---

## 10. 本仓库参考实现索引

| 主题 | 文件与位置 |
| --- | --- |
| 轮次模型与终态判断 | `payroll-common/.../domain/ChatTurn.java:10`、`:31` |
| 轮次表 DDL | `payroll-common/src/main/resources/db/migration/mysql/V4__chat_turns.sql:4` |
| 轮次存储与围栏条件更新 | `payroll-common/.../db/H2ChatTurnStore.java:33`、`:113`、`:128`、`:147`、`:177` |
| 开启轮次 / 接管 / 事件 / 结束 | `payroll-common/.../service/ChatSessionManager.java:466`、`:524`、`:694`、`:712`、`:731`、`:755` |
| Redis Stream 事件总线 | `payroll-common/.../service/RedisTurnEventBus.java:53`、`:79`、`:109`、`:188` |
| 进程内降级总线 | `payroll-common/.../service/InMemoryTurnEventBus.java` |
| 轮次租约与 Lua 续约 | `payroll-common/.../service/TurnLeaseService.java:72`、`:100`、`:169`、`:196` |
| 会话级互斥锁 | `payroll-common/.../service/SessionLockService.java:24`、`:51` |
| SSE 事件桥接 / 重放 / 心跳 | `payroll-common/.../web/ChatSse.java:196`、`:217`、`:233` |
| 续流接口与守护循环 | `payroll-backend/.../web/ChatSessionController.java:429`、`:450`、`:488`、`:512`、`:526` |
| `/turns/active` 冷启动查询 | `payroll-backend/.../web/ChatSessionController.java:394` |
| Runtime 内部接口（HMAC/202/409） | `payroll-agent/.../runtime/InternalTurnController.java:73`、`:100`、`:223` |
| 失联轮次启动清理 | `payroll-common/.../config/PayrollCoreConfiguration.java:499` |
| 前端 SSE 解析与续流调用 | `frontend/src/api/sse.js:5`、`:15`、`:50` |
| 前端退避、冷恢复、状态机 | `frontend/src/views/ChatView.vue:29`、`:488`、`:542`、`:574`、`:627` |
| 客户端游标持久化 | `frontend/src/stores/activeTurn.js:8`、`:24`、`:45` |

---

## 11. 运维验证

1. **网络闪断**：长回答输出中断网（DevTools → Offline），恢复后应看到「正在尝试续流…」，文本从断点继续且不重复。
2. **刷新恢复**：输出中途刷新，会话自动重建气泡并完整回放，结束后只剩一条助手消息。
3. **取消**：中途点击停止，回答立即结束，轮次变 `CANCELED`，刷新后无残留气泡。
4. **跨 Web 节点**：`web-1` 发起后停掉 `web-1`，用 `web-2` 的登录态刷新，应从 Redis 事件续流。
5. **执行节点崩溃**：停掉 Runtime，续流端应在约 45 秒内提示「回答中断」，数据库轮次为 `EXPIRED`。
6. **接口层**：
   ```bash
   curl -s -b ck.txt http://localhost:18088/api/chat/sessions/1/turns/active
   curl -N -b ck.txt "http://localhost:18088/api/chat/sessions/1/turns/<turnId>/events?after_seq=0"
   ```
7. **Redis 检查**：
   ```bash
   redis-cli XRANGE payroll:chat:events:<turnId> - +
   redis-cli GET payroll:chat:turn:1
   redis-cli TTL payroll:chat:events:<turnId>
   ```

---

## 12. 改进任务清单

> 排序原则：先修正确性，再降成本，再提体验，最后做扩展性。每项完成后勾选，验收不通过不进入下一优先级。

### P0 正确性（必须先做）

- [x] **P0-1 修正失联轮次误杀**
  - 改动：`chat_turns` 增加 `heartbeat_at`，由执行端每 10s 独立更新（与结构事件解耦）；`expireStale` 只处理心跳超时且租约缺失的轮次；启动清理保留，另加每 60s 周期扫描。
  - 涉及：`H2ChatTurnStore.java:177`、`PayrollCoreConfiguration.java:499`、`ChatSessionManager.java`。
  - 验收：纯文本长回答运行超过 2 分钟期间重启任一 Web 节点，轮次仍为 `RUNNING`；执行端真正宕机后 ≤ 90s 变 `EXPIRED`。
  - 已实现：执行端注册 `turn-maintenance` 心跳，每 10s 写 `heartbeat_at`；扫描取「30s 无心跳」候选后逐个校验租约，租约存活则跳过；MySQL 走 V5，H2 内联 DDL 同步。

- [x] **P0-2 终态消息幂等**
  - 改动：`chat_messages` 增加 `turn_id` 列与唯一索引（MySQL migration + H2 DDL 同步）；`completeTurn` 按 `turn_id` 先查后写或 UPSERT；保存消息与 `finishTurn` 同事务，或都带 fencing 条件更新。
  - 涉及：`ChatSessionManager.java:608`、`H2ChatStore`、`V1__payroll_core.sql`。
  - 验收：同一 `turn_id` 重复提交、接管重放 3 次，助手消息仍只有 1 条。
  - 已实现：`turn_id` 只标记终态助手消息（用户消息经 `user_message_id` 关联，避免唯一索引冲突）；先查后写并用唯一索引兜底重复；`completeTurn` 终态短路，旧令牌直接放弃写回。

- [x] **P0-3 `beginTurn` 事务化与孤儿清理**
  - 改动：用户消息与 `chat_turns` 写入放在同一数据库事务；租约获取失败时补偿；启动清理「RUNNING 但无用户消息」和「用户消息无轮次」的孤儿数据。
  - 涉及：`ChatSessionManager.java:466`。
  - 验收：在两次写入之间注入异常不产生孤儿数据；修复后 `/turns/active` 不返回异常轮次。
  - 已实现：`createWithUserMessage` 在同一事务内写消息与轮次；提交后的副作用失败会把轮次补偿为 `FAILED`；启动与周期扫描标记「用户消息缺失」的 `RUNNING` 轮次。
  - 说明：历史「用户消息无轮次」不做删除——升级前的消息本就没有轮次，删除会损失会话历史；事务化后不会再产生这类半写数据。

- [x] **P0-4 事件写放大治理**
  - 改动：在 `ChatSse` / `ChatSessionManager.emit` 增加 50~100ms 的 `text` / `thinking` 增量合并；`RedisTurnEventBus.append` 用 pipeline 或 Lua 一次提交；TTL 只在首事件或每 N 秒刷新；裁剪改为时间优先（`MINID`），保留 `MAXLEN` 兜底。
  - 涉及：`RedisTurnEventBus.java:53`、`ChatSse.java`、`ChatSessionManager.java:694`。
  - 验收：同样一轮回答的 Redis 命令数下降 ≥ 5 倍；文本仍按 50~100ms 粒度到达，首字延迟无明显退化。
  - 已实现：`ChatSse` 以 80ms 窗口合并 `text` / `thinking` / `agent_text` / `tool_args`，任一非增量事件（工具、阶段、done）前强制冲刷，取消后丢弃未发增量。
  - 已实现：`RedisTurnEventBus.append` 改为单次 Lua（INCR + XADD + 条件 EXPIRE + MINID/MAXLEN 裁剪），由每事件约 6 条命令降为 1 次脚本往返；TTL 首事件或每 64 条刷新，裁剪每 32 条执行，时长维度由键 TTL 负责。

- [x] **P0-5 断点过期契约**
  - 改动：事件读取返回最早可用 `seq`；`resume` 发现 `after_seq` 早于最早事件时，下发 `gap` 事件（带 `earliest_seq`）或返回 410；前端收到后停止拼接，改为展示数据库最终消息并明确提示「部分过程已跳过」。
  - 涉及：`ChatSessionController.java:470`、`frontend/src/api/sse.js`、`ChatView.vue`。
  - 验收：人为 `XTRIM` 掉前半段事件后重连，前端不展示残缺回答，且提示清晰。
  - 已实现：`TurnEventBus.earliestSeq`（内存取队首、Redis `XRANGE ... COUNT 1`）；续流开始与循环内发现序号不连续时下发 `gap`（`after_seq` / `earliest_seq` / `message`），并从最早可用序号继续重放。
  - 已实现：前端收到 `gap` 后清空已拼接文本与思考、忽略后续增量，顶部常驻提示「部分过程已跳过」，`done`/收尾时以落库消息展示完整回答。

### P1 成本、可靠性与扩展性

- [x] **P1-1 轮次快照与冷恢复优化**
  - 改动：Redis 增加 `events:{turnId}:snapshot`，每 N 个事件或每 1~2s UPSERT `{seq,text,tools,agents,phase}`；刷新时先取快照再补增量；读侧合并连续 `text` 后再下发。
  - 验收：1 万事件轮次刷新后首屏 < 1s，SSE 帧数下降 ≥ 50%。
  - 已实现：`RedisTurnEventBus` 维护 `payroll:chat:snapshot:{turnId}`（`{seq,text,tools,agents,phase}`，文本保留最近 20k、工具/智能体去重封顶 50），每 16 个事件或 `done`/`error` 时持久化；`InMemoryTurnEventBus` 按同样契约重放事件生成快照。
  - 已实现：续流先下发 `snapshot` 再补增量（`ChatSessionController`），后端把连续 `text` 合并为一帧下发；前端收到 `snapshot` 直接替换文本/工具/智能体/阶段状态，不再逐字重放历史。

- [x] **P1-2 绝对截止时间与进度看门狗**
  - 改动：`chat_turns` 增加 `deadline_at`，创建时固定；续流按绝对截止时间判断，不再每次连接重置；执行端每 30s 检查 `last_seq` 是否前进，长时间不前进则主动失败。
  - 验收：执行端 hang 但租约仍在续期时，轮次在配置上限内结束，不会因客户端重连无限延长。
  - 已实现：`ChatTurn.deadlineAt` 创建时按 `payroll.chat.turn-max-minutes`（默认 30）固定；H2 内联 DDL 与 MySQL `V6__turn_deadline.sql` 加列并为历史运行中轮次回填，扫描节点 `expireOverdueRunning` 兜底置 `EXPIRED`；续流按绝对截止时间判断，不再以连接开始时间重置。
  - 已实现：执行端心跳每轮比较 `last_seq`，超过 `payroll.chat.turn-progress-timeout-seconds`（默认 300）无进展或到达截止时间即主动 `FAILED`、归还租约并取消运行。

- [x] **P1-3 围栏令牌原子分配**
  - 改动：改为 Redis 每会话 `INCR payroll:chat:fencing:{sessionId}`，或数据库增加唯一索引 `(session_id, fencing_token)` 并在冲突时重试；所有写路径继续校验令牌。
  - 验收：并发与 Redis 降级场景下无重复 fencing；旧令牌写回影响 0 行。
  - 已实现：新增 `chat_fencing` 计数器表（H2 内联 DDL、MySQL `V7__chat_fencing.sql`），`H2ChatTurnStore.nextFencing` 在单事务内以行锁自增并按已有轮次最大令牌校准，不再依赖 `SELECT MAX + 1` 的读改写，Redis 是否可用不影响分配正确性；全部写回仍带 `fencing_token` 条件，旧令牌影响 0 行。
  - 已实现：`ChatTurnLifecycleTest.concurrentFencingAllocation` 以 8 线程并发分配 160 次，令牌互不重复且连续递增。

- [x] **P1-4 事件总线降级可恢复**
  - 改动：用断路器替换永久 `degraded`：失败阈值、半开探测、恢复后自动切回；降级范围限制在当前轮次。
  - 涉及：`RedisTurnEventBus.java:237`。
  - 验收：断开 Redis 30s 后恢复，无需重启即可重新跨节点续流。
  - 已实现：`RedisTurnEventBus` 内置断路器（连续失败 3 次打开，10s→20s→40s→60s 退避封顶，半开仅放行单次探测），Redis 调用期间降级到进程内总线；探测成功自动恢复并记日志；`readBlocking` 命令超时视为 Redis 可达，不计入失败。

- [x] **P1-5 单节点事件分发器**
  - 改动：每节点每轮只保留一个读取循环，本地 fan-out 给多个 SSE 订阅者；或采用 Pub/Sub 通知 + Stream 补放。
  - 验收：1000 并发连接时 Redis QPS 与连接数解耦，稳定在每轮约 1 次/秒量级。
  - 已实现：新增 `TurnEventFanout`，每节点每轮一个读取线程（`turn-events-*`），本地环形缓冲 2048 条供所有订阅者共享；订阅者只在自己的游标上取数，缓冲裁剪后落后订阅者按既有 `gap` 契约收尾；无订阅 60s 后自动退出，`ChatSessionManager.close()` 统一释放。
  - 已实现：`TurnEventFanoutTest` 用 3 个并发订阅者验证读取次数不随连接数放大（等待期只由一个循环轮询）。

- [x] **P1-6 事件 schema 版本化**
  - 改动：事件统一为 `{v,type,seq,ts,payload}`；客户端忽略未知事件；发布前做兼容性检查。
  - 验收：新旧前端混跑期间，旧前端不因新增事件报错。
  - 已实现：`TurnEventBus.VERSION = 1`，`TurnEvent` 增加 `version` / `timestamp` 元数据；Redis Stream 每条事件写入 `v` / `ts` 字段，读取时高于当前版本的事件跳过并告警，缺失版本按 1 兼容；`ChatSse` 在所有 SSE 负载上补齐 `v` / `ts`（重放保留原始 `ts`），前端 `handleEvent` 的 `default` 分支天然忽略未知事件。
  - 已实现：事件类型登记表 `TurnEventBus.KNOWN_EVENTS`，`ChatSessionManager.emit` 发布未登记类型时记录告警；负载仍保持平坦字段而非嵌套 `payload`，避免破坏既有前端读取契约。

### P2 可运维性与安全

- [x] **P2-1 多标签页协调**：`BroadcastChannel` 选主，或服务端限制每会话单活跃订阅者。
  - 已实现：新增 `frontend/src/stores/tabSync.js`，同一浏览器内以 `BroadcastChannel` 协调：打开会话时先 `probe` 探测其它标签页是否已在拉同一条流，已有主人则拉取 `state` 快照并镜像渲染后续 `event`；发送新问题前同样探测，若其它标签页正在生成则提示并转为镜像，避免重复写入。流主人关闭页面时广播 `end{handoff}`，镜像页自动接管续流。
  - 兼容：不支持 `BroadcastChannel` 或探测超时（300ms）时退化为各标签页独立拉流，行为与改造前一致。
- [x] **P2-2 清理会话锁死路径**：把 `SessionLockService` 接入 `beginTurn`，或删除该路径，避免「看起来有锁、实际未生效」。
  - 已实现：删除 `SessionLockService` 及其测试与 Spring Bean；遗留 `begin()` 改走 `TurnLeaseService`（`acquire` + `track`，释放时 `release`），与 `beginTurn` 共用同一套短租约，多实例互斥不再依赖 15 分钟长锁。
- [x] **P2-3 事件大小上限与 Redis 隔离**：单事件 ≤ 64KB；事件流使用独立实例或独立库；禁止 `allkeys-lru` 误淘汰。
  - 已实现（上限）：`ChatSessionManager.emit` 统一走 `limitEventSize`，序列化超过 64KB 时先按 `text`/`reply`/`args`/`message` 字段迭代截断并标记 `truncated=true`，仍超限则只保留标量与短字符串，附 `dropped_bytes` 告警；`EventSizeLimitTest` 覆盖小负载、超长文本、超大集合三种情况。
  - 已实现（淘汰策略）：`RedisTurnEventBus` 构造时执行 `INFO memory` 自检，`maxmemory-policy` 以 `allkeys` 开头即记录 WARNING；`docker/docker-compose.yml` 的 redis 启动参数显式声明 `--maxmemory-policy noeviction`。
  - 取舍：未做代码级「独立连接工厂 + 独立库」。额外 `RedisConnectionFactory` Bean 会让 Spring Boot Redis 自动配置因 `@ConditionalOnMissingBean` 回退，反而影响会话、限流等全部 Redis 用途；改以部署级隔离（独立实例或 `SELECT` 独立库）+ 启动自检告警落实，需在部署文档中要求事件流使用独立 Redis 实例或库。
- [x] **P2-4 指标与告警**：补齐 `gap` 次数、降级次数、租约丢失、重放事件数、事件字节数、续流 p99。
  - 已实现：新增 `TurnMetrics`，默认写入 `Metrics.globalRegistry`（Boot 的 `MeterRegistryPostProcessor` 会把 Prometheus 注册表并入全局），指标如下：
    | 指标 | 类型 | 埋点 |
    | --- | --- | --- |
    | `payroll.chat.turn.gap` | Counter | `ChatSessionController.sendGap` |
    | `payroll.chat.turn.degrade` | Counter | `RedisTurnEventBus.acquire` 熔断期间的操作 |
    | `payroll.chat.turn.lease.lost` | Counter | 续流校验租约失败、扫描清理失联轮次 |
    | `payroll.chat.turn.event.bytes` | DistributionSummary（histogram） | `ChatSessionManager.emit` |
    | `payroll.chat.turn.replay.events` | DistributionSummary（histogram） | 每次续流重放条数 |
    | `payroll.chat.turn.resume` | Timer（histogram） | 续流追上存量事件的耗时 |
  - 告警建议：`increase(payroll_chat_turn_gap_total[5m]) > 0` 需要关注裁剪配置；`increase(payroll_chat_turn_degrade_total[5m]) > 0` 检查 Redis 与熔断；`histogram_quantile(0.99, rate(payroll_chat_turn_resume_seconds_bucket[5m])) > 2` 检查续流追平延迟。指标随 `/actuator/prometheus` 暴露。
- [x] **P2-5 取消标记生命周期**：TTL 与轮次绝对上限对齐，轮次结束时主动删除取消键。
  - 已实现：取消键 TTL 改为 `payroll.chat.turn-max-minutes`（默认 30 分钟，与轮次绝对上限一致），不再固定 1 小时；新增 `TurnEventBus.clearCancel(turnId)`，在 `finishTurn`、`finishRemoteTurn`、`expireTurn` 与失联轮次扫描落终态后主动删除取消键。看门狗终止轮次时保留取消标记作为执行线程的停止信号，由执行线程收尾时清理，异常退出则由 TTL 兜底。
- [x] **P2-6 裁剪策略统一**：时间优先（`MINID`）+ `MAXLEN` 兜底，避免条数与时长脱节。
  - 已实现：事件 ID 保持 `<seq>-0`（序号是续流游标，不能改用时间戳当首部），因此把两个维度拆开统一：**条数**用 `seq - maxLen` 作为 MINID 水位裁剪、再以 `MAXLEN` 兜底；**时长**不再按固定条数间隔刷新 TTL，而是在写入时检查 `PTTL`，剩余不足一半即重置，避免「条数没到、键已过期」或「条数到了、时间远超」的脱节。仍每 32 条事件执行一次裁剪，控制单次脚本开销。

### 业务模块扩展

- [x] **B-1 知识库系统配置流程融合到协同智能体**
  - 目标：在对话内用自然语言完成知识库的建库、解析/分块/检索配置、目录与成员权限调整、入库任务查询；写操作仍走既有提案确认，用户不必再跳到「知识库」页逐项点选。
  - 改动：
    - 把 `payroll-backend/.../kb/KbConfigController.java`、`KbDirectoryController.java`、`KbMemberController.java`、`KbDocumentController.java` 背后已有服务抽成一组 `KbSkill` 工具，按只读/写入分级注册给协同智能体；
    - 写操作（建库、保存配置版本、加/移成员、移动目录、触发重建）标记风险等级并生成提案卡片，确认后执行，结果写审计；只读查询（库列表、配置详情、任务状态）直接调用，不产生提案；
    - `AgentRouter` 增加知识库配置类能力标签，`TeamTemplate` 增加 `KB_CONFIG` 单角色模板，避免与薪酬核算模板混流；
    - 权限沿用 `kb:read` / `kb:create` / `kb:manage`，无权限时工具返回明确拒绝，不静默降级到通用回答。
  - 验收：
    - 对话输入「给研发部建一个知识库，检索 topK 调到 8」能生成一条待确认提案，确认后库与配置版本真实落库；
    - 未授权账号触发写操作被拒绝，审计有记录；
    - 只读问句不产生提案，也不会被路由进团队核算模板；
    - 原「知识库」页面行为与接口契约不变。
  - 批次：第 6 批（业务扩展），依赖现有 P0 正确性修复完成。
  - 已实现（2026-11-14）：
    - 工具与通道：`payroll-common/.../agent/KbTools.java` 提供 7 个只读与 6 个写工具；`KbSkillClient` 以 HMAC 签名调用 Web 的 `/internal/kb/check` 与 `/internal/kb/execute`，未配置令牌或 `PAYROLL_KB_SKILL_ENABLED=false` 时不注册工具。
    - Web 执行：`KbSkillService` 复用既有系列、配置、成员、目录、文档与任务服务，按 `kb:read` / `kb:create` / `kb:manage` 做全局权限加库级角色双重校验；`KbActionContributor` 把写工具注册为本地动作，提案确认后由 Web 节点执行；拒绝写入审计 `kb.skill.denied`。
    - 对话侧：写工具先权限预检再交 `ActionGuard` 生成提案，只读直接执行并保留工具追溯；`PayrollAgentFactory` 只向非流水线协同智能体注册工具并注入知识库提示；`AgentRouter` 对知识库写操作禁止选择 pipeline；`TeamIntent` / `TeamTemplate` 新增 `KB_CONFIG` 单角色模板承接只读问答，写操作仍不进入动态团队。
    - 验收对应：`KbToolsTest`、`ToolSafetyTest`、`TeamIntentTest` 覆盖读写分级、拒绝不执行与模板选择；`KbSkillInternalTest` 以真实 Spring Boot 实例验证签名内部接口（未签名 401）、只读放行、无权限写入拒绝并落 `kb.skill.denied` 审计、按 `department_name` 建库后 `recallTopK=8` 真实落库；原知识库控制器与接口契约未改动。

### 不建议做

- 逐 token 写入关系库：仅合规场景使用独立分区表 + 异步批量写，且保留 Redis 读路径。
- 崩溃后重跑 Agent：模型调用不可重入，工具可能已产生副作用。
- 用 WebSocket 替换 SSE：当前收益不足，反而增加多节点复杂度。

### 建议执行批次

| 批次 | 包含任务 | 目标 |
| --- | --- | --- |
| 第 1 批 | P0-1、P0-2、P0-3 | 消除误杀与重复消息，保证数据正确 |
| 第 2 批 | P0-4、P0-5 | 把 Redis 成本降一个量级，补齐断点过期契约 |
| 第 3 批 | P1-1、P1-2、P1-4 | 提升刷新体验，限制失控轮次，恢复 Redis 降级能力 |
| 第 4 批 | P1-3、P1-5、P1-6 | 加固多节点一致性与扩展性 |
| 第 5 批 | P2-1 ~ P2-6 | 运维、安全与长期可维护性 |
| 第 6 批 | B-1 | 知识库配置能力进入对话（业务扩展） |
