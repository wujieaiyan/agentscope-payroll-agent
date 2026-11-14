# 对话续传（SSE Resume）

网络闪断、网关超时、页面刷新或节点故障后，正在生成的回答可以从断点继续输出，不重复、不丢内容。实现参照 Qoder 的流式续传语义：**事件带序号可重放，轮次有生命周期，客户端只负责记住游标并重连**。

## 1. 事件与游标

- 运行期节点把每个事件写入 Redis Stream `payroll:chat:events:{turnId}`，序号由 `INCR` 分配，24 小时或 10000 条封顶。
- 在线 SSE 与续流 SSE 都携带同一个序号：SSE `id:` 行 + JSON 体内的 `seq` 字段。
- 结构事件（start/phase/tool/done 等）同时写回 `chat_turns.last_seq`，`/turns/active` 返回的就是这个游标。
- 空闲每 10 秒发送 `event: ping`，`data: {"after_seq":N,"ts":...}`，**不带 id**，避免污染 `Last-Event-ID`。
- 续流时若收到的 `seq > lastSeq + 1`，服务端记 WARN：事件日志已被裁剪，前端只得到部分内容，最终以 `done` 落库消息为准。

## 2. 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/chat/sessions/{id}/stream` | 首轮流式回答，返回 `turn` 事件（含 `turn_id`） |
| GET | `/api/chat/sessions/{id}/turns/active` | 会话当前 RUNNING 轮次；刷新后先查这里 |
| GET | `/api/chat/sessions/{id}/turns/{turnId}` | 轮次状态（RUNNING/SUCCEEDED/FAILED/CANCELED/EXPIRED） |
| GET | `/api/chat/sessions/{id}/turns/{turnId}/events?after_seq=N` | 重放并尾随事件，直到 `done` 或终态 |
| POST | `/api/chat/sessions/{id}/turns/{turnId}/cancel` | 取消本轮 |

续流接口同时接受 `Last-Event-ID` 请求头，与 `after_seq` 取较大值；自研 fetch 客户端显式传 `after_seq`，`Last-Event-ID` 供原生 EventSource / 代理重连使用。

## 3. 前端行为

- 收到 `turn` 事件后，把 `{turnId,lastSeq,startedAt}` 写入 `sessionStorage`（键 `payroll.activeTurn.{sessionId}`，800ms 节流，24 小时过期）。
- `done` / `error` 事件到达时删除标记；取消、放弃、重置会话同样清理。
- 流断开后进入指数退避续传：500ms → 1s → 2s → 4s → 8s，共 6 次；每次先查轮次终态：
  - `SUCCEEDED`：拉取历史消息，结束；
  - `FAILED/CANCELED/EXPIRED`：展示服务端错误，结束；
  - 其他：带 `after_seq` 续流，成功后继续尾随。
- 6 次失败后保留回答气泡，显示「连接已中断」与「重试续传 / 放弃本轮」。
- `online` 事件和标签页重新可见时，若存在可续传气泡则自动重试。
- 页面刷新或切回会话：`/turns/active` → 有 RUNNING 轮次则重建气泡并从 `seq=0` 重放；没有则退回本地标记查询轮次状态。
- 有未完成回答时禁止发送新问题（服务端也会以「该会话正在生成回答」拒绝并发轮次）。
- **打印机式输出**：`text` 事件先进入待输出队列，再由定时器逐字写入气泡（约 125 字/秒；积压超过 300 字时自动提速，避免长回答拖尾）。续流重放同样逐字呈现，可在输出中途刷新页面观察断点续传。
- **全链路同源**：单智能体、三段流水线、流程编排（FLOW）与动态团队（TEAM）都由同一个 `text` 事件驱动。FLOW/TEAM 中唯一的末端智能体以 `answerStage` 执行，模型增量直接推流；多个末端智能体时不推流，流程结束后整段发送，收尾只补发尚未流出的尾部差量。
- 收尾时等待队列吐完再切换为落库消息；停止、放弃、切换会话会立即清空队列。`done` 后本地标记的清理会先取消尚未落盘的节流写入，避免标记被延迟写回。

## 4. 服务端守护

- **租约判定**：`TurnLeaseService` 以 `payroll:chat:turn:{sessionId}` 续约（TTL 30s、心跳 10s）。续流超过 45s 无事件时查租约：
  - 租约仍存在 → 模型或工具只是慢，继续等待并重置计时；
  - 租约消失 → 判定执行节点失效，`EXPIRED` 并返回「回答中断，请重新发起提问」。
- **硬上限**：单次续流最长 30 分钟，超时标记过期，防止线程长期占用。
- **终态兜底**：轮次已是终态但没收到 `done` 时，非成功终态补发 `error` 事件。
- **跨节点**：事件在 Redis，Web 节点之间可互相接管续流；同一会话的并发锁与租约保证只有一个执行者。

## 5. 配置

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `payroll.chat.turn-lease-seconds` | 30 | 轮次租约 TTL |
| `payroll.chat.turn-heartbeat-seconds` | 10 | 租约续约间隔 |
| `payroll.chat.turn-event-ttl-hours` | 24 | 事件流保留时长（续流窗口） |
| `payroll.chat.turn-event-max` | 10000 | 单轮事件条数上限 |
| `payroll.chat.session-lock-minutes` | 15 | 会话跨实例互斥锁 |

`ChatSessionController` 内的 `TURN_HEARTBEAT_MS=10s`、`TURN_LEASE_GRACE_MS=45s`、`TURN_HARD_LIMIT_MS=30min` 与上表配合使用。

## 6. 失败模式

| 现象 | 行为 |
| --- | --- |
| 网络闪断，轮次仍在跑 | 自动退避续传，从 `after_seq` 继续，不重复文本 |
| 刷新页面 | 重建气泡，从 0 重放全部事件 |
| Web 节点重启 | 新节点按 Redis 事件续流；租约仍由运行期节点持有 |
| 运行期节点崩溃 | 45s 无事件 + 租约消失 → EXPIRED，提示重新提问 |
| 事件被裁剪 | 服务端 WARN，客户端拿到的内容可能不完整，`done` 后以数据库消息为准 |
| Redis 不可用 | 事件退化为进程内总线，仅同节点可续流；跨节点恢复失效（既有降级策略） |
| 用户停止 | 先中断本地连接，再调用 cancel；本轮不落助手回答 |

## 7. 验证

1. 发送一个长回答的问题，在输出中途断开网络（DevTools → Offline）。恢复网络后应看到「正在尝试续流…」，随后文本从断点继续且无重复。
2. 输出中途刷新页面。会话应自动重建回答气泡并完整回放，结束后只保留一条助手消息。
3. 中途点击「停止」：回答立即结束，服务端轮次变 CANCELED，刷新后无残留气泡。
4. 多节点：`web-1` 发起请求后停掉 `web-1`，用 `web-2` 的登录态刷新页面，应从 Redis 事件续流。
5. 接口层：
   ```bash
   curl -s -b ck.txt http://localhost:18088/api/chat/sessions/1/turns/active
   curl -N -b ck.txt "http://localhost:18088/api/chat/sessions/1/turns/<turnId>/events?after_seq=0"
   ```
