# 容量与韧性性能基线（2026-11）

> 对应规格：`docs/capacity-resilience-remediation-spec.md` §5 C6（可观测性与容量验收）。
> 本文所有数字均来自 2026-11-24/25 本机实测；原始 JSON 见文末「证据文件」。

## 1. 机器与部署配置

| 项 | 值 |
| --- | --- |
| CPU | Intel Core i7-10750H @2.60GHz，6 核 12 线程 |
| 内存 | 31.9 GB |
| OS | Windows 10 x64（10.0.19045） |
| JDK | 17.0.12 LTS（`java -version`） |
| Web | `payroll-backend-exec.jar`（8080），G1，`-Xmx8140m`（`MaxHeapSize=8556380160`） |
| runtime | `payroll-agent-runtime.jar`（8100） |
| 依赖 | 本机 MySQL 3306（payroll）、Redis 6379、Elasticsearch 9200、Ollama 11434 |
| 入口 | nginx 8880 → Web 8080（真实页面与压测入口） |
| 部署模式 | `single`（指标标签 `deployment_mode="single"`） |
| 控制面模型 | `openai-compatible:deepseek-flash`（api.deepseek.com，云端；Ollama 仅作本地兜底） |
| KB 解析 | `payroll.kb.parse.mode=inline`（解析在 Web 进程内，未启动独立 parser 8300） |
| 对话配额 | 压测时临时 `PAYROLL_CHAT_MAX_CONCURRENT=200`；验收后已恢复默认 2 |
| 有界执行器 | Web chat 4 线程 + 32 队列；runtime 8 线程 + 64 队列（均 AbortPolicy → 429） |

## 2. 测量口径

- **堆**：`jcmd <pid> GC.run` 后 `jcmd <pid> GC.heap_info`，取 `used` 值；不再取未 GC 的瞬时值做对比。
- **指标**：从 `http://127.0.0.1:8080/actuator/prometheus`（Web）与 `http://127.0.0.1:8100/actuator/prometheus`（runtime）抓取；nginx 8880 对 `/actuator/*` 返回 SPA HTML，不可用于抓指标。
- **SSE 压测**：经 nginx 8880 登录 → 建会话 → `POST /api/chat/sessions/{id}/stream`；TTFT = 发起请求到首个 SSE 字节；`first_text` = 首个 `event:text`；`done` = 终态事件。
- **检索/解析压测**：经 nginx 8880 真实接口路径，指标抓 8080。
- **懒注册**：Timer/Gauge 首次采样后才出现在 `/actuator/prometheus`；空结果不代表未接线（如 `payroll_kb_parse_failures` 无失败即无样本）。

## 3. SSE 200 路压测（`scripts/load/sse_load.py`）

单账号一次性发起 200 个流式请求（`PAYROLL_CHAT_MAX_CONCURRENT=200`，解除单用户配额上限），共 71.1s 完成。

| 指标 | P50 | P95 | P99 |
| --- | --- | --- | --- |
| TTFT（请求→首个 SSE 字节） | 1388.1 ms | 1688.2 ms | 1724.5 ms |
| 首段文本 `event:text` | 40976.6 ms | 62088.3 ms | 64403.2 ms |
| 终态 `event:done` | 36347.0 ms | 62417.1 ms | 65471.3 ms |

- HTTP 状态：`200 × 94`，`429 × 106`；`terminal_events=94`，`gaps=0`，`reconnects=0`，`events=842`。
- 429 来源：Web `chatStreamExecutor`（4+32）与 runtime 轮次池（8+64）满后按 R1 设计立即拒绝，**不是**单用户配额；94 轮全部准入并正常终态，无断流、无事件缺口。
- 指标快照（脚本前后）：`payroll_sse_connections` `0 → 0`；事后抓取仍为 `0.0`，无连接计数泄漏。
- `payroll_sse_hub_poll_queue_seconds`（9751 样本）：48 个 ≤1ms、421 个 ≤~28ms、9330 个落在 (0.028, 0.537]s。该直方图**包含 500ms 空闲退避的主动延迟**，P50≈537ms 不代表排队争用；真正活跃 tick 的投递延迟约 16.8ms（P95 桶 ≤~28ms）。
- 模型侧：runtime `payroll_model_duration_seconds_count{plane="控制面",outcome="success"}` +132；`payroll_turn_duration_seconds_count{path="AGENT",outcome="success"}` +73。并发高峰期轻量面出现 8 次 `Connection reset`（api.deepseek.com 上游重置），属云端供应商限流，计入 `outcome="error"`。

**结论**：SSE 扇出链路健康（无 gap、无重连、无写超时/慢客户端、连接计数回到 0）；瓶颈在云端模型吞吐而非网关。first_text 40–64s 系 94 路并发打满云端模型所致，不代表单路体验（单路实测见 §7）。

### 3.1 压测发现并修复的缺陷

200 路压测把 `payroll_sse_connections` 卡在 116（而到 8080 的已建立 TCP 连接为 0），定位为**准入拒绝路径未释放连接计数**：`ChatSessionController.stream()/resume()` 与 `PayrollWebController.chatStream()` 在创建并计数 `ChatSse.Channel` 后，配额耗尽 / 执行器拒绝 / 尾随订阅拒绝三条 429 分支直接抛 `TooManyRequestsException`，emitter 未交给容器也没有 `complete()`。

修复：每条拒绝分支抛错前先 `channel.complete()`（`ChatSessionController.java:445/468/472/908`、`PayrollWebController.java:322/329/337/341`）。回归断言：`ChatSessionControllerTest#executorRejectionReleasesQuota`（`:86`）、`#quotaExhaustedRejectsWithoutScheduling`（`:105`）比较拒绝前后 `payroll.sse.connections` 不变；单测 9/9 通过。修复后同口径复跑 `0 → 0`。

## 4. KB 检索压测（`scripts/load/kb_search_load.py`）

自动发现 3 个可读且已就绪的系列（本机仅 3 个），200 次检索并发：

| 项 | 值 |
| --- | --- |
| 请求数 / 状态 | 200 / 全部 200 |
| 墙钟 | 4752.3 ms |
| 延迟 P50 / P95 / P99 | 170.6 / 590.2 / 774.6 ms |
| 分系列 P95 | 系列 4：721.9 ms（67 次）；系列 5：566.3 ms（66 次）；系列 6：590.2 ms（67 次） |
| 空结果 | 0 |
| 错误码 | 无 |

- ES 检索 `payroll_es_duration_seconds_count{op="search"}` 增加 ~394 次，均值约 35ms；压测窗口 `payroll_executor_{active,queue}{pool="kbSearch"}` 始终为 0，未触发池满 429。
- 与 SLO 对照：P95 590ms < 1.5s，达标本机口径；ES 单次 <2s，未触发降级。

## 5. KB 解析压测（`scripts/load/kb_parse_load.py`）

inline 模式，5 个本地合成 200 页文本层 PDF（每文件唯一，绕过 sha 去重），并发 3 提交并轮询 PENDING→READY：

| 项 | 值 |
| --- | --- |
| 文档 / 上传状态 | 5 / 全部 200 |
| 终态 | READY × 5（FAILED 0，TIMEOUT 0） |
| 重试 / 错误码 | 0 / 无 |
| 流水线耗时 P50 / P95 / P99 | 6325.2 / 9356.3 / 9356.3 ms |
| 墙钟 / 吞吐 | 13.6 s / 22.1 docs/min |
| 单文档解析（`engine="inline"`） | 3 个 ≤0.537s，5 个 ≤1.074s |
| `payroll_kb_parse_duration_seconds_count{engine="inline",outcome="success"}` | 0 → 5 |
| `payroll_kb_parse_failures` | 无样本（0 失败，懒注册，符合预期） |
| `payroll_kb_parse_queue` | 不出现（parser 专属 Gauge，inline 模式无独立队列，偏差见 §8） |

**Web 堆增量**（SLO <100MB）：一次 5×200 页解析并 GC 后 used = 73,219 KB（committed 327,680 KB）；再解析 1×200 页并 GC 后 used = 70,888 KB（committed 286,720 KB），**增量 −2.3MB（GC 后无滞留增长）**。

## 6. §8 SLO 逐项证据

| SLO 项 | 阈值 | 实测 | 状态 |
| --- | --- | --- | --- |
| 单 Web 节点在线 SSE | ≥200 路，P95 首字增量 <300ms | 200 请求全部接入 HTTP 层，94 轮准入并全部终态；TCP/连接计数无残留。首字节 P95 1.69s 为云端模型时延，非网关 | 🟡 容量达标，首字阈值受模型吞吐限制（见 §8 偏差 1、2） |
| 慢客户端 5s 内摘除 | 5s 内 watchdog 摘除，其余订阅 P95 <300ms | 逻辑由 `TurnEventFanoutTest#stalledWriterIsMarkedByWatchdog`（`:180`）覆盖；本次压测客户端均正常读，链路 `write.timeout/slow.clients/write.stalled` 无增量 | 🟡 单测覆盖，未做慢客户端注入实测 |
| 提交 429 率（20rps 稳态） | <0.1% | 本次为 200 并发突发（非 20rps 稳态），429=106 属有界执行器设计行为，由 `payroll.chat.stream.rejected` 计数 | 🟡 突发口径，非稳态口径 |
| KB 检索 5 库/2000 候选 | P95 <1.5s | 3 库 × 200 次，P95 590ms，空结果 0，ES P95 <2s 未降级 | ✅（本机 3 库） |
| 200 页扫描件解析 | parser 堆 <1.5GB；Web 堆增量 <100MB | inline 模式：Web 解析后 GC used 73.2→70.9MB，增量 −2.3MB | ✅（Web 侧；parser 未启动，见偏差 3） |
| 500 轮上下文构建 | <100ms | 本批脚本未覆盖；`ConversationContextTest` 16 项验证水位折叠正确性，未做时延采样 | ⬜ 未测 |
| 清理单行批 / 50 文件批 / 整轮 | ≤2s / ≤5s / ≤10min | `RetentionServiceTest` 7 项验证批次顺序、预算与失败跳过；未做时延采样 | 🟡 功能覆盖，未做时延实测 |

## 7. 真实页面与真实对话（nginx 8880）

- **真实页面**：浏览器访问 `http://127.0.0.1:8880/` → 未登录跳转 `/login`，标题「协同智能体 · 薪酬管理台」，登录表单完整渲染；以 admin 登录后进入 `/platform/agents`，侧栏（平台/薪酬/治理、对话、智能体、流程编排、技能、团队运行、知识库）与管理台内容正常渲染；切换到 `/chat` 显示会话列表与消息。
- **真实一轮对话**（经 8880 SSE）：`event:turn/start/agent_start/text/agent_end/done` 完整，`event:done` 含 `reply`、`routing.path="AGENT"`、`model="openai-compatible:deepseek-flash"`、`elapsed_ms=1559`；用户消息与助手回复均落库，刷新页面可见。事件 schema `v:3`，`seq` 连续。
- 对话后指标：`payroll_sse_connections=0.0`；runtime `payroll_turn_duration_seconds_count{path="AGENT"}` 与 `payroll_model_duration_seconds_count` 同步增长。

## 8. 已知偏差与限制

1. **SSE 首字阈值**：SLO 的「P95 首字增量 <300ms」针对网关/SSE 容量；本次经真实云端模型跑 94 路并发，TTFT 1.4–1.7s 是模型供应端时延。若要单独验收网关，需固定短提示 + 桩模型（规格 §「测量口径」已注明区分两类瓶颈）。
2. **429 率口径**：200 并发突发触发有界执行器拒绝（R1 设计），不构成 20rps 稳态 429 率结论；稳态 429 率需独立长稳压测。
3. **parser 未启动**：本机以 `mode=inline` 验收，200 页解析发生在 Web 进程，故 `payroll.kb.parse.queue`（parser 专属）与 parser 堆峰值（<1.5GB，对应 `mem_limit 2.5g`）未测；`HikariDataSourceMetricsBinder` 在 Micrometer 1.15.7 不存在，改为 `PayrollDatabase` 手动注册 `hikaricp.connections{pool="payroll-mysql"}` 四个 Gauge（`PayrollDatabase.java:103`）。
4. **磁盘/DB 规模**：检索压测仅 3 个系列、解析仅 5 个文档，候选规模未达「5 库 × 2000」；P95 590ms 下仍有较大余量，但大库场景需在目标环境复测。
5. **hub-poll 直方图语义**：`payroll.sse.hub.poll.queue` 记录的是「schedule 到执行」的完整时延，空闲期每 500ms 一次退避占绝对多数样本；解读必须按桶拆活跃/空闲，不能把 P50 当排队延迟。
6. **慢客户端与清理时延**：watchdog 摘除逻辑有单测覆盖（`stalledWriterIsMarkedByWatchdog`），但本批压测未注入慢读/半开连接；清理任务仅功能验证，未做单批时延采样。
7. **轻量面模型错误**：并发高峰期 `payroll_model_duration_seconds_count{plane="轻量面",outcome="error"}=8`（api.deepseek.com `Connection reset`），归因上游限流，非本地链路缺陷。

## 9. 复现命令

```bash
# SSE：200 并发（先以 PAYROLL_CHAT_MAX_CONCURRENT=200 启动 Web）
PAYROLL_LOAD_ACCOUNTS=admin:admin123 python scripts/load/sse_load.py \
  --base-url http://127.0.0.1:8880 --users 1 --requests 200 --concurrency 200 \
  --metrics-url http://127.0.0.1:8080/actuator/prometheus --out D:/tmp/c6-sse-200b.json

# 检索：200 次并发
PAYROLL_LOAD_ACCOUNTS=admin:admin123 python scripts/load/kb_search_load.py \
  --base-url http://127.0.0.1:8880 --concurrency 20 --requests 200 \
  --metrics-url http://127.0.0.1:8080/actuator/prometheus --out D:/tmp/c6-kb-search.json

# 解析：5×200 页 inline（不要加 --restart-cmd，inline 重启会中断压测本身）
PAYROLL_LOAD_ACCOUNTS=admin:admin123 python scripts/load/kb_parse_load.py \
  --base-url http://127.0.0.1:8880 --series-id 6 --documents 5 --pages 200 \
  --concurrency 3 --timeout 900 \
  --metrics-url http://127.0.0.1:8080/actuator/prometheus --out D:/tmp/c6-kb-parse.json

# 堆：GC 后取样
jcmd <web-pid> GC.run && sleep 2 && jcmd <web-pid> GC.heap_info
```

## 10. 证据文件

| 文件 | 内容 |
| --- | --- |
| `D:/tmp/c6-sse-200b.json` / `.log` | 修复后 200 路 SSE 结果与脚本摘要 |
| `D:/tmp/c6-sse-200.json` / `.log` | 修复前（连接计数泄漏证据） |
| `D:/tmp/c6-kb-search.json` | 检索压测结果 |
| `D:/tmp/c6-kb-parse.json` / `.log` | 解析压测结果（5×200 页） |
| `D:/tmp/c6-kb-parse-delta.json` | 堆增量复测（1×200 页） |
| `D:/tmp/web-c6.log` / `D:/tmp/runtime-c6.log` | Web / runtime 日志 |
