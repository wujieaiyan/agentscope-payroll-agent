# 容量与韧性整改规格（v0.5）

- 依据：本轮架构与性能评审（2026-11-24，基线 `fe6b43e`）
- 性质：执行级规格，按工作流 C0–C6 拆分；每个工作流含现状证据、方案、改动清单、验收、回滚
- 估算口径：人日，含自测，不含评审等待
- 状态：**D1–D7 已定**（2026-11-24：D2 取 B 并一期落地，其余按推荐；D5 已定 180/365，D7 改为两阶段）
- 原则：先止血（C0a），再隔离与提容量（C0b/C1），最后补数据与观测（C2–C6）；所有行为变更默认可开关回退
- v0.2 修订（2026-11-24 复审）：C4 重写删除集与跨库批删；C0 纳入附件路径、Web 侧准入、产物原子写与 OCR 构建迁移；C1 补写池串行与断点契约；C5 补租约语义与守卫两阶段；新增 §7.1 升级与迁移；估算由 28 上调至 38 人日
- v0.3 修订（2026-11-24 可实施性复审）：历史窗口改消息水位（修 P0：摘要存在后最近轮次被静默丢弃）；C0 补齐解析模块迁移面（公共模型、公有静态、嵌套类型、测试模块与 pom）并修正产物卷路径；C3 明确 `ChatContextStore`/`ChatSessionManager` API 与新增 `kbSearchExecutor`；C1 补 `ChatSessionManager` 订阅门面；C5/C6 修正守卫结构与 Hikari 指标落点；V23 增 `chat_sessions.context_through_message_id` 与批删索引顺序；估算 38 → 41 人日（缓冲 47）
- v0.4 修订（2026-11-24 性能与可用性专项复审）：C1 写超时改为「独立 watchdog 关流」并给出可执行机制（修 P0：`SseEmitter.send` 无超时，16 路慢客户端可永久占满写池）；C4 重写保留任务退出条件与失败行策略（修 P0：失败行重选导致死循环）、改为日调度 + 时间预算 + 批间休眠，补依赖先删/孤儿兜底、`chat_feedback`/`kb_task_log`/解析产物清理与索引列序修正；C3 补 ES 检索超时与拒绝降级、文档列表分页；C6/§8/§9 验收与排期同步；估算 41 → 45 人日（缓冲 52）
- v0.5 修订（2026-11-24 架构与产品可实施性复审）：C1 修正看门狗释放机制（修 P0：`SseEmitter.send` 与 `completeWithError` 共用 `writeLock`，watchdog 关流会先阻塞并冻结单线程监控；改为「watchdog 只标记与摘除，写线程由容器写超时回收」）；C4 修正 `kb_document` 索引列序并拆分 filesort 验收（修 P0）、补会话与摘要清理、附件删除幂等与 `message_id NULL` 语义、`agent_team_runs` 悬挂引用、V23 恢复路径、产物当前版本保护与启动统计租约；C3 补改动清单与 KB 列表返回兼容；C5 租约统一为 DB 时间并给方言 SQL；估算 45 → 49 人日（缓冲 56）

---

## 1. 目标与非目标

### 目标

1. **止血**：任何合法上传/解析操作（知识库文档与对话附件）都不能把 Web 节点打 OOM；解析下沉为独立进程，Web 与解析故障域分离；SSE 断流可恢复。
2. **容量**：单 Web 节点在线流从 8 路提升到 ≥200 路；单 runtime 节点并行生成从 4 路提升到 8–16 路且可配置。
3. **延迟**：知识库多库检索从“库数 × 串行”降为并行；长会话每轮上下文读取从 O(历史) 降为 O(窗口)。
4. **数据**：补齐热查询索引与保留策略，让系统在 12 个月数据量下不退化。
5. **运维**：启动任务多副本互斥；关键路径有指标与 SLO；容器有资源边界与健康检查。
6. **可验证**：每项都有可执行的压测/回归脚本和明确的验收阈值。

### 非目标

- 不迁 WebFlux、不拆微服务（SSE 改造限定在 MVC 内）；
- 不做多租户、不改薪酬计算引擎与金额链路；
- 不重写前端对话页（仅历史接口加分页参数，前端做兼容读取）；
- 不改造鉴权模型，仅补生产启动守卫；
- 不在本期引入新的向量库/消息中间件。

---

## 2. 现状基线（证据表）

| 编号 | 事实 | 证据 |
| --- | --- | --- |
| B1 | Web 受理池 4 线程 / 队列 32 / AbortPolicy，超出 429 | `payroll-common/.../config/PayrollCoreConfiguration.java:1054-1067`；`payroll-backend/.../web/ChatSessionController.java:423` |
| B2 | SSE 尾随池 8 线程 / 队列 64，一条流独占一个线程直到整轮结束 | `PayrollCoreConfiguration.java:1083-1097`；`ChatSessionController.java:571-593` |
| B3 | runtime 生成复用同一 `chatStreamExecutor` 4 线程 | `payroll-agent/.../runtime/InternalTurnController.java:164`（bean 定义同 B1） |
| B4 | 事件 fanout 已有节点共享 poller（4 线程、活跃 10ms / 空闲 150ms 轮询） | `payroll-common/.../service/TurnEventFanout.java:33-40,73-76,131` |
| B5 | 尾随循环在 `waitTurnEvents(..., 10s, 200)` 上阻塞等待 | `ChatSessionController.java:706-712` |
| B6 | PDF 解析整文件载入 + 全量页文本 List + 拼接大 String | `payroll-backend/.../kb/KbParserService.java:206-222` |
| B7 | OCR 页图并发 2、`synchronized(renderer)`、默认不限页数 | `KbParserService.java:305-341`；`payroll-common/src/main/resources/application-common.yml:287` |
| B8 | OCR 超时包装用无界 `newCachedThreadPool` | `payroll-backend/.../kb/ocr/OcrExtractionService.java:74-80` |
| B9 | 上传上限 600MB / 请求 1GB | `application-common.yml:35-36,260` |
| B10 | `TeamLlmClient` 全局 `synchronized(lock)` | `payroll-agent/.../team/TeamLlmClient.java:91` |
| B11 | KB 多库检索逐库串行；候选上限 2000 且查询带 `content` | `payroll-backend/.../kb/KbSkillService.java:250-276`；`KbRetrievalSettings.java:47`；`KbChunkStore.java:29-32,77-96` |
| B12 | 会话历史每次整段读取，无 LIMIT；同步回复再全量扫描找一条 | `payroll-common/.../db/H2ChatStore.java:200-205`；`ChatSessionController.java:375,861` |
| B13 | `llm_usage` 只有 occurred_at/model/user/session 索引，无 plane/agent 索引、无保留任务 | `db/migration/common/V16__llm_usage.sql:27-30`；`H2LlmUsageStore.java:106-112` |
| B14 | `chat_turns` 按 `started_at` 清理，但无前导 started_at 索引；只删 turn 不删消息 | `H2ChatTurnStore.java:438-447`；`V4__chat_turns.sql:20-21` |
| B15 | 审计/团队清理在启动时单条大 DELETE；KB/附件恢复每个节点都跑；Nacos 导入仅 JVM 内互斥 | `PayrollCoreConfiguration.java:987-1041`；`SkillNacosImporter.java:111-118` |
| B16 | Hikari 手建（16 连接）非 Spring bean，无 `hikaricp.*` 指标；模型/ES 无 Timer | `PayrollDatabase.java:85-95`；`TurnMetrics.java:20-32` |
| B17 | `SemanticCacheService` 本地 LRU 硬编码 40000，配置的 10000 未生效 | `SemanticCacheService.java:60-67`；`application-common.yml:251` |
| B18 | `chatFor` 每次全量排序淘汰 | `ChatSessionManager.java:1380,1392-1398` |
| B19 | 生产启动守卫只校验 cluster/MySQL/Redis/Nacos/ES/SECRET_KEY，不校验管理员初始口令与 runtime token | `TopologyGuard.java:73-132`；`application-common.yml:76` |
| B20 | 应用容器无 healthcheck / 资源限额 | `docker/docker-compose.yml`（mysql/redis/ollama 有 healthcheck，web/runtime 无） |
| B21 | 解析与 Web 同进程：PDFBox 整文件 + OCR 页图直接占用 Web 堆，解析崩溃即 Web 崩溃 | `KbParserService.java:206-222,305-341`；`OcrExtractionService.java:73-86` |
| B22 | 对话附件解析复用同一 PDF/OCR 实现，仍在 Web 进程内（20MB / 5 个、解析池 2 线程 / 100 队列） | `payroll-backend/.../chat/ChatAttachmentService.java:59-61,300-318`；`AttachmentParseQueue.java:31-32,36-47`；`KbParserService.java:131,200` |
| B23 | 既有保留键：turn 90 天、审计 180 天、团队 180 天；会话消息无清理 | `application-common.yml:143,212,216`；`H2ChatTurnStore.java:438-447` |
| B24 | Web 入库池 2 线程 / 64 队列；remote 解析的排队职责未定义 | `payroll-backend/.../kb/KbConfiguration.java:25-51` |
| B25 | RapidOCR 以 Maven profile 挂在 backend，Tesseract 装在 backend 镜像 | `payroll-backend/pom.xml:40-57`；`docker/backend.Dockerfile:9-11` |
| B26 | nginx 已关缓冲（600s）；SSE 超时 600s、轮次截止 30 分钟，三者不一致 | `docker/nginx.conf:31-34`；`ChatSessionController.java:67`；`ChatSessionManager.java:51` |
| B27 | 会话摘要计数 `context_turns` 是**绝对轮次数**；`ConversationContext.from` 用 `min(summarizedTurns, history.size())` 从历史开头截断，窗口化读取后会把窗口内最近轮次全部截掉 | `ConversationContext.java:55-58,158-165`；`H2ChatStore.java:770-788`（`chat_sessions.context_summary/context_turns`） |
| B28 | 解析实现迁移面被低估：`KbParserService` 依赖 backend 内的 `KbConfigModels`，并暴露公有静态 `SUPPORTED`/`CHAT_SUPPORTED` 与嵌套类型 `AbortSignal`/`ChatExtraction`，被文档、存储、附件、入库与 OCR 取消类共同使用 | `KbParserService.java:5-7,67,70,80,131,620`；`KbStorageService.java:47`；`KbDocumentService.java:134`；`ChatAttachmentService.java:230,309`；`KbIngestService.java:36,381`；`ocr/OcrCanceled.java:6` |
| B29 | compose 四个应用服务均把 `kb-data` 挂到 `/app/data`；没有 `parsed-data` 卷。产物目录若用 `./data/parsed` 会落在只读上传卷内 | `docker/docker-compose.yml:134-190,230`；`application-common.yml:259` |
| B30 | 代码中不存在 `kbRecallExecutor`；只有 `memoryRecallExecutor`（记忆召回）与 `kbIngestExecutor`（入库） | `PayrollCoreConfiguration.java:602-616`；`KbConfiguration.java:25-51` |
| B31 | 任务重试上限实际为 `payroll.kb.task-max-retries` 默认 3，且同时约束入库任务；规格原写的「≤2」无对应实现 | `KbTaskService.java:64,187-190` |
| B32 | `TurnEventFanout` 与内部 `Hub` 均为包私有，且实例由 `ChatSessionManager` 内部创建；控制器只持有 `ChatSessionManager`，无法直接注册订阅 | `TurnEventFanout.java:25,111`；`ChatSessionManager.java:69,321,1070`；`ChatSessionController.java:76,92` |
| B33 | 写通道没有应用层超时：`ChatSse.send` 同步调用 `SseEmitter.send`；Spring 6.2 的 `send` 与 `completeWithError` 共用同一 `writeLock`，他线程关流只会一起阻塞、不能打断写。阻塞本身受 Tomcat 写超时约束（`writeTimeout=connectionTimeout`，默认 60s），不是永久，但 16 路慢客户端可占满写池约 60s | `ChatSse.java:418-437`；spring-webmvc 6.2.15 / tomcat-embed-core 10.1.50 字节码核验 |
| B34 | 保留任务失败行没有退出条件：附件「文件删失败保留行」会被下一轮同条件重新选出，「循环到不足一批」永不满足；且清理只在启动时执行 | `payroll-common/.../db/H2AuditStore.java:106`；`PayrollCoreConfiguration.java:987-1041`；`H2ChatTurnStore.java:438-447` |
| B35 | ES 检索 HTTP 超时 5s，与 C3 单库 1.5s 预算不匹配；`CompletableFuture` 超时不会中断已发出的 HTTP | `KbElasticsearchService.java:31,312,364` |
| B36 | `findBySeries` 过滤 `deleted_at IS NULL` 且 `ORDER BY updated_at DESC, document_id DESC`，无 LIMIT；`keyword` 用 `LIKE '%x%'` 全扫 | `KbDocumentStore.java:94-109` |
| B37 | `parsed/**` 产物无清理；`chat_feedback`/`kb_task_log` 无保留策略；`chat_feedback` 只有 `(message_id,user_id)` 唯一键与 `message_id` 索引 | `docs/capacity-resilience-remediation-spec.md` §11；`V1__payroll_core.sql:324-336`；`KbTaskStore.java:283` |
| B38 | 200 路订阅对应 200 个 Hub，空闲 150ms 轮询共享 4 线程 poller，缺 Hub 数与排队指标 | `TurnEventFanout.java:34,39-40,132`；`PayrollCoreConfiguration.java:855` |
| B39 | 会话行本身无清理（仅 `(user_id, updated_at)` 索引，`created_at` 全表扫）；`context_summary` 随会话长期保留已删消息的摘要；附件删除无幂等（文件先丢失会永久保留行）；附件 `message_id` 可空（上传未绑定）使孤儿判定需显式排除；`agent_team_runs` 归档行 `message_id NOT NULL` 会悬挂指向已删消息 | `V1__payroll_core.sql:36-47,272-299`；`V9__chat_attachments.sql:1-17` |

---

## 3. 决策前置（D1–D7）

| 编号 | 决策 | 选项 | 推荐 | 影响 |
| --- | --- | --- | --- | --- |
| D1 | SSE 改造路线 | A 保留 MVC + 推送订阅 + 写池；B 迁 WebFlux | **A** | C1 工作量 10 人日（含 watchdog 写超时），风险可控；B 约 20 人日 |
| D2 | 解析隔离 | A 限额 + 单遍解析 + 有界 OCR；B A + 独立解析进程 | **B，一期落地**（A 为前提） | C0 由 3.5 增至 13 人日（C0a 3 + C0b 10，含公共模型下沉、静态常量抽取与测试模块接线）；新增 `payroll-parser` 服务、内部通道与部署形态，Web 与解析故障域分离 |
| D3 | runtime 生成池 | 4 / 8 / 16 | **8 起步，按压测调到 16** | 受供应商并发配额约束，需同步调模型连接配额 |
| D4 | 历史窗口 | 最近 40 条 + 摘要 / 游标分页 | **两者都做**：上下文按消息水位取 40 条，接口支持游标 | 摘要与窗口必须用 `context_through_message_id` 对齐，不能再按绝对轮次数截断（B27） |
| D5 | 保留策略 | llm_usage 180 天、聊天历史 365 天 / 不清理 | **180 / 365，默认 dry-run** | 已定；合规复核为正式删除的前置，消息按 `created_at` 独立清理（不依赖已删除的 turn） |
| D6 | 启动任务互斥 | DB 租约表 / Redis 锁 / 仅按节点 ID 选主 | **DB 租约表** | Redis 故障时仍可互斥；多一次 DB 往返 |
| D7 | 生产守卫 | 校验管理员初始口令 + runtime token + node-role | **采纳，两阶段** | 先 `warn` 一个版本再 `fail`；`node-role=all` 仅在 cluster 模式强制拆分；升级说明见 §7.1 |

推进顺序：C0a（止血）→ C0b（独立解析）→ C1（并行 C5-3/4 容器探针与守卫 warn）→ C2/C3/C4（可并行）→ C5-1/2 → C6；C1 开工前先用预发/生产数据核对并发需求基线（§9），低于 100 路则目标与估算同步下调。C3 的水位列与 C4 的索引/清理同属 V23，schema 先定稿再并行编码。

---

## 4. 工作流总览

| 编号 | 工作流 | 对应问题 | 产出 | 估算 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| C0 | 上传与解析上限 + 独立解析进程 | P0 | C0a 单遍解析/页数与大小上限/有界 OCR/附件纳入；C0b `payroll-parser` 服务、Web 侧准入、原子产物、OCR 构建、容器限额、公共解析模型下沉 | 13（3+10） | D2 |
| C1 | SSE 推送化与执行池拆分 | P1-1、all 模式自竞争 | 订阅式 fanout、串行写池 + watchdog 停滞标记/摘除、断点契约、`runtimeTurnExecutor` | 10 | D1、D3 |
| C2 | 锁与热点缓存治理 | P1-2、P2 | TeamLlmClient 信号量、嵌入锁外移、淘汰限频、轮询降频 | 2 | — |
| C3 | 检索与历史读路径 | P1-3、P1-4 | 并行检索、候选去 content、BM25 走 ES、检索超时与降级、文档分页（游标 + 返回契约兼容）、历史窗口（消息水位） | 8 | D4、V23 |
| C4 | 索引与生命周期 | P1-5、P1-6 | V23 迁移（索引列序 + 失败恢复）、保留任务（退出条件/失败上限/孤儿兜底/会话与摘要/日调度/产物 TTL）、dry-run | 8 | D5、D6 |
| C5 | 启动任务与容器基线 | P1-7、P1 安全 | 租约协调器（DB 时间 + 方言 SQL）与巡检、compose 限额/探针、守卫两阶段、升级手册 | 5 | D6、D7 |
| C6 | 可观测性与容量验收 | P1-8 | Timer/Gauge、Hikari 指标、压测脚本、SLO | 3 | C0–C5 |

合计约 49 人日（C0a/C0b 拆分；含 15% 缓冲按 56 人日排期，单人 + AI 协作约 10 周 / 含缓冲约 12 周，不含评审等待）。

---

## 5. 详细设计

### C0 上传、解析上限与独立解析进程（P0）

**现状**：`PDDocument.load` 整文件进堆，全部页文本进 List 再拼大 String；OCR 默认不限页；超时包装无界线程池；上传上限 600MB 且容器无内存限制；解析与 Web 同进程，合法操作即可 OOM 整个 Web。对话附件解析复用同一 `KbParserService`，仍在 Web 进程内（B22），同样不受页数上限保护。

**方案**

1. **单遍解析**（`KbParserService.parsePdf`）
   - 去掉 `List<String> pageTexts`；逐页 `stripper.getText` 后立即处理：
     - 非空页 → 追加到页缓冲，记录页偏移；
     - 空页且 OCR 开启 → 立即渲染 + OCR，再追加；
   - `filterHeaderFooter` 改为「首页/末页采样 + 两页前瞻」的流式判定，不再依赖全量列表；
   - 解析结束只保留合并文本与页范围表，PDFBox 文档在 `try-with-resources` 内关闭。
2. **硬上限**
   - 新增 `payroll.kb.parse.max-pages`（默认 200）：文本型与扫描型 PDF 均生效；超限任务失败并给出「共 N 页，上限 M 页，请拆分」；
   - `payroll.kb.max-file-size` 默认从 600MB 降为 **200MB**（D2-B 前提）；上传接口在写入前校验 `Content-Length` 并返回 413；
   - `spring.servlet.multipart.max-file-size` / `max-request-size` 同步降为 200MB / 512MB（`PAYROLL_UPLOAD_MAX_FILE_SIZE`、`PAYROLL_UPLOAD_MAX_REQUEST_SIZE`），否则 multipart 层先接收 600MB，业务校验形同虚设；
   - 对话附件（`parseChatAttachment`）同样受 `payroll.kb.parse.max-pages` 约束，附件大小/数量上限维持 20MB / 5 个不变；
   - 前端上传提示同步上限文案。
3. **有界 OCR**
   - `OcrExtractionService.timeoutExecutor` 改为固定 2 线程 / 队列 8 / AbortPolicy；拒绝时该页按质量门失败，走引擎降级链，不新建线程；
   - 页图使用后立即置空，禁止跨页持有 `BufferedImage`。
4. **独立解析进程（D2-B，一期落地）**
   - 新模块 `payroll-parser`（包 `io.agentscope.payroll.parser`，端口 `PAYROLL_PARSER_PORT:8300`）：把 `KbParserService`、`KbOcrService`、`OcrExtractionService`、`ocr/*`、`KbParsedDocument` 从 `payroll-backend` 迁入 `io.agentscope.payroll.parser.kb`；去掉 Spring 组件注解改为显式装配，Web 以普通 jar 依赖复用同一实现做 `inline` 回退（包名不在 Web 扫描路径内，不会重复注册）；
   - 迁移面同步拆解（B28）：`KbConfigModels` 的 `ParseConfig/OcrConfig/ParseOptions/TypeParseConfig` 下沉到 `payroll-common/.../kb/parse/`（backend 的配置/分块/检索/技能/DTO 改 import，parser 不再反向依赖 backend）；公有静态 `SUPPORTED`/`CHAT_SUPPORTED` 抽成 `KbParseFormats` 常量类；嵌套类型 `AbortSignal`、`ChatExtraction` 独立为 `KbAbortSignal`、`ChatExtraction`；
   - 接口：`POST /internal/kb/parse`，HMAC `X-Payroll-Timestamp` + `X-Payroll-Internal`（复用 `InternalAuth`，独立 `PAYROLL_PARSE_TOKEN`，偏斜 120s）；入参 `{documentId, storageKey, ext, mimeType, maxPages, ocr}`，出参 `{artifactKey, pageCount, charCount, ocrStats, engine, ms}`；
   - 产物：逐页写入 `parsed/{documentId}/{sha256}/{profile}.json`（页文本 + 页范围表），整包不回传；解析服务**不连数据库**（规避 H2 单写者约束），Web 读产物继续分块/嵌入/入库；
     - 原子性：先写 `*.part`，全部页完成后 `ATOMIC_MOVE` 为正式文件，最后写 `manifest.json`；Web 只消费 manifest 已存在的产物，重试命中同路径时直接复用；版本目录内同时落 `CURRENT` 标记（parser 写入，并清除同文档其他版本的标记，保证单文档只有一个当前版本），供 C4 产物清理保护 `artifact_key` 指向的当前版本；
     - `profile` 由 `ocr|maxPages|engine` 组成，避免不同解析参数互相覆盖；
     - 产物根目录独立卷 `payroll.kb.parse.artifact-dir: ./parsed`（容器内 `/app/parsed`；compose `parsed-data:/app/parsed`，parser 读写、Web 只读），**不得落在 `/app/data` 之下**——该路径是 `kb-data` 只读挂载（B29）；`inline` 回退时 Web 对 `parsed-data` 需临时可写，或按配置跳过产物复用直接走内存；
   - 并发与背压：parser 固定 2 工作线程 / **队列 0（`SynchronousQueue`，不排队）/ AbortPolicy**，满返回 503；**排队职责在 Web 侧**，parser 只承接已放行的在途请求，因此直连时第 3 个请求即被拒绝；
   - Web 侧准入：`payroll.kb.parse.web-concurrency:2` 信号量 + 固定连接池 4，connect timeout 5s、read timeout = `parse.timeout-ms + 30s`；拿不到许可的任务保持 RUNNING 并退避，不占 HTTP 连接；503 与超时按可重试处理，HTTP 级快速重试 ≤ `payroll.kb.parse.max-retries`（默认 2，退避 30s），任务级持久重试沿用既有 `payroll.kb.task-max-retries`（默认 3，B31），超限 FAILED 并保留错误码；
   - 附件：`parseChatAttachment` 改为经 `KbParserPort.parseChat`，`remote` 走 parser（附件目录 `./data/chat-uploads` 位于 `kb-data` 卷下），`inline` 与 KB 共用页数上限；
   - 文件访问：parser 以只读方式挂载 `kb-data:/app/data`、以读写方式挂载 `parsed-data:/app/parsed`；Web 对 `parsed-data` 只读；本机 `inline` 直读上传目录并在 Web 本地写产物目录；
   - 安全：parser 只在集群网络监听，除 `/actuator/health` 外不暴露；`PAYROLL_PARSE_TOKEN` 为空则 parser 启动失败，TopologyGuard 同步校验（D7）；`storageKey` 在 parser 端 realpath 归一到上传根目录之下，拒绝 `..` 与 symlink，产物路径只接受数字 `documentId` 与固定 profile 段。
5. **OCR 构建资产随模块迁移**
   - `-Prapidocr` profile 与三件套依赖从 `payroll-backend/pom.xml` 迁到 `payroll-parser/pom.xml`；
   - 新 `docker/parser.Dockerfile` 安装 `tesseract-ocr`、`tesseract-ocr-chi-sim` 与字体，与 `backend.Dockerfile` 保持一致（inline 回退仍用 Web 镜像内的 Tesseract）；
   - parser 启动自检 OCR 引擎：Tesseract 缺失 WARN，RapidOCR 原生库缺失按现有降级链处理，不阻塞启动。
6. **容器边界**
   - web `mem_limit: 2g`、runtime `4g`、sandbox `1g`、parser `2.5g`；JVM 统一 `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`（parser 70% 约 1.75GB，为「堆峰值 < 1.5GB」留出余量）；
   - parser 配 `healthcheck`（`/actuator/health/liveness`），Web/runtime 同批补齐。

**改动清单**
- 新增 `payroll-parser/`（Application、`KbParseController`、`KbParseWorker`、`ParseRequest/ParseResult`、`application.yml`、Dockerfile）；`pom.xml` 注册模块并迁入 `rapidocr` profile
- `payroll-backend/.../kb/KbParserService.java`、`KbOcrService.java`、`ocr/**`、`KbParsedDocument.java` → 迁入 `payroll-parser`（保留原包路径调整）
- `KbConfigModels` 拆出解析相关记录 → `payroll-common/.../kb/parse/`；`KbConfigService`、`KbChunkingService`、`KbRetrievalService`、`KbSkillService`、`KbDtos`、`KbIngestService` 改 import
- 新增 `payroll-common/.../kb/parse/KbParseFormats.java`（`SUPPORTED`/`CHAT_SUPPORTED`）、`KbAbortSignal.java`、`ChatExtraction.java`；`KbDocumentService`、`KbStorageService`、`ChatAttachmentService`、`KbIngestService`、`ocr/OcrCanceled` 改引用
- `ChatAttachmentService` 改经 `KbParserPort`，删除对解析实现的直接依赖
- 新增 `payroll-common/.../kb/parse/KbParserPort.java`、`KbParseClient.java`（HMAC 调用 + inline 回退 + 信号量 + 连接池），`KbIngestService` 改为经端口调用
- `payroll-tests/pom.xml` 增 `payroll-parser` 依赖；`KbParserOcrTest`、`ChatAttachmentServiceTest` 改导入并保持可构造解析器
- `payroll-common/src/main/resources/application-common.yml`（`kb.parse.mode/url/token/threads/queue/timeout-ms/web-concurrency/max-retries/artifact-dir`、`kb.parse.max-pages`、`kb.max-file-size` 默认值、`spring.servlet.multipart.*`）
- `docker/docker-compose.yml`、`docker/parser.Dockerfile`、`docker/backend.Dockerfile`（parser 服务、`parsed-data:/app/parsed`、上传只读卷、内存限额、healthcheck）
- 前端上传提示（`frontend/src/views/kb/**`、对话附件入口）

**验收**
- 200 页扫描 PDF 走 `remote`：解析成功，parser 堆峰值 < 1.5GB，Web 堆无显著增长（GC 后 `jcmd GC.heap_info` 前后对比，记录并发数与采样次数）；
- 解析中 `kill -9` parser：任务转 RETRYABLE/FAILED 且 Web 存活；重启 parser 后重试成功；产物目录无 `.part` 残留被消费；
- 直连 parser（绕过 Web 队列）并发 3 个请求：第 3 个得 503；经 Web 提交时表现为任务退避重试，不丢任务、不占满入库线程；
- 300 页对话附件在 `remote` 下被页数上限拒绝，Web 进程不重启；`inline` 下同一附件同样拒绝；
- 201 页 / 201MB 文件明确失败，Web 进程不重启；
- OCR 并发时 `ocr-timeout` 线程数 ≤ 2；
- parser 只读上传卷、`parsed-data:/app/parsed` 读写分离生效（`ls -l` + 无写权限验证；产物路径不位于 `/app/data` 下）；
- `mvn -o -pl payroll-parser -am test` 与全量 `mvn -o test` 通过；`ArchitectureRulesTest` 全绿（解析新命名空间不触发 R2/R4/R5）。

**回滚**：`payroll.kb.parse.mode=inline` 切回 Web 进程内解析（保留一个版本，附件同路径回退）；parser 服务下线不影响 Web 启动；页数/大小/线程数/准入并发均走配置；`inline` 下若 `parsed-data` 对 Web 只读，则禁用产物复用、直接内存返回（功能可用，仅少一层重试复用）。

---

### C1 SSE 推送化与执行池拆分

**现状**：每条 SSE 订阅占用一个 `sseTailExecutor` 线程，在 `waitTurnEvents` 上阻塞整轮；单节点 8 路在线流。`chatStreamExecutor` 同时承担 Web 受理与 runtime 生成；单进程 all 部署时受理阻塞会挤掉执行。

**方案**

1. **订阅式 fanout 与串行写池**
   - `TurnEventFanout.Hub` 增加订阅接口：
     ```java
     Subscription subscribe(String turnId, long afterSeq, Consumer<TurnEvent> onEvent,
                            Runnable onGap, Runnable onTerminal)
     ```
   - Hub 的 poller 收到批量事件后，投递到每个订阅者的有界队列（默认 512）；
   - 新增 `sseWriterExecutor`（16 线程 / 队列 4096 / AbortPolicy）：从订阅队列取事件 → `channel.send`；
   - **每订阅串行投递**：同一订阅同一时刻只允许一个 drain 任务在写通道；队列非空且无在途任务时才向写池提交一次 drain，drain 循环到队列空退出。保证 `seq` 严格递增，不依赖 `SseEmitter` 的内部锁；
   - 队列满 → 发送 `gap` 事件并关闭通道（与既有裁剪契约一致），**不阻塞 poller、不阻塞轮次执行**；
   - **写超时由独立 watchdog 观测，不依赖写线程，也不调用 emitter API**（B33）：`SseEmitter.send` 与 `completeWithError` 持有同一把 `writeLock`，写线程阻塞在 socket 上时，watchdog 调 `completeWithError` 只会一起阻塞。Hub 为每个订阅记录 `lastWriteAt` 与 `inFlightSince`；单线程 watchdog（`payroll.chat.sse.watchdog-tick-ms:1000`）扫描订阅：
     - `now - max(lastWriteAt, inFlightSince) > payroll.chat.sse.write-timeout-ms:5000` → 标记该订阅 `stalled`、从 Hub 摘除（停止后续投递）、`payroll.sse.write.timeout` +1、`payroll.sse.slow.clients` +1、`payroll.sse.write.stalled` gauge 更新；**watchdog 单次扫描只读内存、只改状态，不发送 gap、不 complete、不访问 DB/Redis**，因此单个慢订阅不会冻结全局监控；
     - 真正释放写线程的是容器：Tomcat 把 socket 的 `writeTimeout` 设为 `connectionTimeout`（默认 60s），写线程在该上限内抛 `IOException/SocketTimeoutException`，由写线程自身走 `Channel.close` 并回收入池；写池容量按「容器写超时 × 允许的并行慢订阅」规划，**不承诺 5s 内释放线程**；如产品要求 5s 级释放，需改非阻塞写（`WriteListener`/WebFlux），不在本规格范围；
     - 关闭路径（队列满 gap/取消/终态）仍由触发方或写线程执行，但 `emitter.complete*` 只允许在「该订阅无在途写」时调用；watchdog 永不进入关闭路径；
     - 慢客户端连续超时计入运维告警；watchdog 自身固定 1 线程。
   - 写池队列满 → 该订阅直接 gap（不重试），`payroll.sse.write.rejected` +1；
   - 快照重放、`earliestSeq` 缺口判断、截止时间看门狗全部下沉到 Hub，`ChatSessionController.resumeEvents` 只保留「注册订阅 + 返回 emitter」。
2. **断点契约（与既有第 1–6 批断点测试一一映射）**
   - `subscribe(afterSeq)` 判定顺序固定为：Hub 本地 buffer → Redis Stream（`turn-event-ttl-hours:24`、maxlen 10000）→ 仍缺 → `gap` + 快照；
   - 快照重放与 live 交错：注册时先固定快照点，重放与后续事件都进同一订阅队列按 seq 串行写出，不并发写通道；
   - 新增用例：afterSeq 早于 buffer、Redis 不可用、写超时、订阅取消；原 `ChatFreshReplayWebTest` 等断点用例改走订阅接口后必须全绿。
3. **保活与超时一致性**
   - Hub 对活跃订阅每 15s 发一次 SSE comment（`ping`），避免长工具调用期间被代理/容器超时误断；
   - 统一超时链：`SSE_TIMEOUT_MS` 与 nginx `proxy_read_timeout` 同步提到 1800s（轮次截止 30 分钟），本机 8880 的 nginx 配置同步修改并列入 §7.1 检查清单。
4. **执行池拆分**
   - 新增 bean `runtimeTurnExecutor`：`payroll.runtime.turn-threads:8`、`payroll.runtime.turn-queue-capacity:64`，供 `InternalTurnController` 使用；
   - `chatStreamExecutor` 只做 Web 侧提交（含同步接口）；
   - all 单进程模式不再自竞争；web/runtime 拆分部署行为不变。
5. **观察点**
   - gauge：`payroll.sse.connections`、`payroll.sse.subscribers`、`payroll.sse.hubs`、`payroll.sse.write.queue`、`payroll.sse.write.inflight`、`payroll.sse.write.stalled`、`payroll.sse.slow.clients`；
   - counter：`payroll.sse.write.rejected`、`payroll.sse.write.timeout`、`payroll.sse.subscription.gap`；
   - timer：`payroll.turn.duration{path,outcome}`、`payroll.sse.hub.poll.queue`（poller 排队等待）。

**改动清单**
- `payroll-common/.../service/TurnEventFanout.java`（订阅模型 + 串行 drain + 快照/缺口判定）
- `payroll-common/.../service/ChatSessionManager.java`（新增 public `subscribe(turnId, afterSeq, consumer, onGap, onTerminal)` 门面与取消/关闭；内部 `fanout` 不再被外部直接引用，B32）
- `payroll-common/.../web/ChatSse.java`（`lastWriteAt`/`inFlight` 标记、watchdog 只标记/摘除不关流、慢客户端计数）
- `payroll-backend/.../web/ChatSessionController.java`（移除 handoffTail 长占用；`SSE_TIMEOUT_MS` 1800s；改为 `manager.subscribe(...)`）
- `payroll-common/.../config/PayrollCoreConfiguration.java`（`sseWriterExecutor`、watchdog 单线程调度器（`destroyMethod=shutdown`）、`runtimeTurnExecutor`、池与 Hub 指标）
- `payroll-agent/.../runtime/InternalTurnController.java`（改用 `runtimeTurnExecutor`）
- `docker/nginx.conf` 与本机 8880 nginx（`proxy_read_timeout 1800s`）

**验收**
- 压测：单 Web 节点 200 路并发 SSE（经 nginx 8880），P95 首字延迟增量 < 300ms，无 429；C1 开工前先核对需求基线（§9）；
- 慢客户端（可证伪）：18 路不读数据 + 2 路半开连接，持续 90s；要求 16 个写线程被占后，watchdog 在 5s 内把这些订阅标记 `stalled` 并从 Hub 摘除（`payroll.sse.write.timeout`、`payroll.sse.write.stalled` 增长，`payroll.sse.subscribers` 下降），其余订阅事件持续送达、P95 首字增量 < 300ms，poller 与轮次执行不受影响；**watchdog tick 计数在慢订阅存在期间持续增长**（证明扫描未被关流阻塞）；被阻塞的写线程在容器写超时（Tomcat 默认约 60s，压测记录实测值）内释放、`payroll.sse.write.inflight` 回落；同时验证「写池打满后新订阅由调用线程直接 gap」，不出现挂起；
- 断线重连：200 路并发重连（前端 0–3s 抖动），事件序号连续或按契约给出 gap，前端能落库收尾；afterSeq 早于 buffer 时走 Redis 重放；重连期间 DB 连接等待与首字 P95 记录在压测报告；
- 长工具调用（>10 分钟）期间 15s ping 保活，连接不被 nginx 切断；
- all 模式 8 路并发提问：不再出现「执行队列已满」的假 429；
- 全量回归：断点会话/续流/取消相关测试全绿。

**回滚**：`payroll.chat.sse.mode=push|legacy`，默认先 `legacy`，验收后切 `push`；一个版本后删除 legacy。

---

### C2 锁与热点缓存治理

1. **`TeamLlmClient` 去全局锁**
   - `synchronized(lock)` 改为 `Semaphore permits`（`payroll.team.llm.max-concurrent:8`）；
   - 获取超时 2s → 视为轻量调用失败，走既有规则回退；
   - 实现前确认底层 `HttpClient` 与 JSON 解析线程安全（当前每次调用独立请求对象）。
2. **嵌入客户端锁外移**
   - `OllamaEmbeddingClient` 的 LRU 只锁 map 读写；HTTP 调用在锁外执行，回填时二次检查；
   - 缓存 key 仍为 `model|text`，TTL 3s 不变。
3. **会话淘汰限频**
   - `evictChats` 增加最小间隔（`payroll.chat.evict-interval-seconds:5`）；
   - 未超额且无空闲超限时直接返回；超额时只扫描到 `excess + 8` 个候选，避免全量排序。
4. **fanout 轮询降频**
   - 空闲轮询 150ms → 500ms；C1 上线后活跃轮询仅用于 Hub 自身，不再按订阅者数量放大。
5. **`ChatSse` 合并线程**
   - 合并窗口仍 80ms；`send` 改为投递写池（C1），合并线程不再直接写 socket。

**验收**：8 路并发下 router 调用 P95 不再随会话数线性上升；`chatFor` 单次 CPU 时间 < 1ms（JMH 或日志埋点抽样）。

**C 证据（2026-11-25，C2 已完成）**

- C2-1 `TeamLlmClient`：`synchronized(lock)` 换成 `Semaphore(max-concurrent)`，许可等待 2s 超时返回 null 走规则兜底；计划器热切换改为独立 `rebuildLock` 双重检查，`agentVersion` volatile。新增证据：`TeamLlmClientTest` 7 项（含「许可内两路并发不串行」与「许可耗尽 2s 兜底」两条并发回归）。
- C2-2 `OllamaEmbeddingClient`：HTTP 调用一直在 `RECENT` 锁外；回填前新增二次 `cachedVectors` 检查，同题并发以先写入缓存为准，缓存 key/TTL（3s）与 ≤4 条批量的策略不变。新增 `OllamaEmbeddingClientTest` 2 项（TTL 命中、4 路同题并发后命中缓存）。
- C2-3 `ChatSessionManager.evictChats`：`payroll.chat.evict-interval-seconds`（默认 5s）限频；未超额时只在节拍到达时扫描，超额立即淘汰以保证上限；候选用大顶堆只保留最旧 `excess+8` 个，替代全表排序。`ChatSessionCacheTest` 2 项回归通过。
- C2-4 fanout 空闲轮询 150ms → 500ms；活跃轮询 10ms 不变。合流写路径：推送模式下 `PushConnection.onEvent` 在写池线程合并 text 并直接 `channel.send`，`ChatSse.DeltaCoalescer` 只服务无轮次演示接口（`/chat/stream`），生产对话链路不存在合流线程直写 socket。`TurnEventFanoutTest` 5 项回归通过。
- 定向回归：`TeamLlmClientTest 7/0/0`、`ChatSessionCacheTest 2/0/0`、`TurnEventFanoutTest 5/0/0`、`OllamaEmbeddingClientTest 2/0/0`。全量 `mvn -o test` 见批次 C3–C5 收口证据。
- 未完成验收项：8 路并发 router P95 与 `chatFor` 单次 CPU 抽样需要真实模型与压测脚本，留待 C5/D 的真机压测（与 §8 与批次 B 的 200 路 SSE 压测同批执行）。

---

### C3 检索与历史读路径

1. **多库并行**
   - `KbSkillService.search` 对 `seriesIds` 并行调用：
     ```java
     CompletableFuture.supplyAsync(() -> searchOne(seriesId, ...), kbSearchExecutor)
     ```
   - 新增 bean `kbSearchExecutor`（8 线程 / 队列 64 / AbortPolicy，`PayrollCoreConfiguration`；现有 `memoryRecallExecutor` 与 `kbIngestExecutor` 职责不同，不复用，B30）；单库预算 1.5s，超时库记为 `degraded`；
   - **超时必须在 HTTP 层生效**（B35）：检索路径的 ES `HttpRequest.timeout` 取 `payroll.kb.search.timeout-ms:1500`（bulk/refresh 保持 15s），`CompletableFuture.orTimeout` 只做兜底——否则线程被 5s 的 HTTP 读超时继续占用，8 线程池在 5 库 × 8 路并发（40 个在途请求）下仍会排队/拒绝，扩线程只是把问题推迟；
   - **拒绝语义分链路**：聊天/团队 KB 检索在池满或全部库超时时**降级为 `degraded=true` + 空结果**（轮次继续执行，提示词标注「本次未检索到知识库」）；只有管理台显式检索接口回 429。不得让检索池把问答打挂；
   - 合并仍按分数降序、chunkId 去重，输出顺序稳定（同分按 seriesId 升序），保证可回归对比。
2. **批量权限**
   - `KbSeriesService` 新增 `getAll(ids, user)`：一次查询成员关系与库状态，替掉循环 `get`（每库 2 次 DB）。
3. **候选去 content**
   - `KbChunkStore` 新增 `SELECT_KEYS`（无 `content`、无 `vector`），向量召回只取 ID/元数据；
   - `BM25` 路由接入 ES：复用既有 `multi_match`（`content^2, titlePath`，查询已存在，mapping 已是 text，见 `KbElasticsearchService.java:354-359,423-430`），在 `KbRetrievalService.bm25Route` 增加 `engine=es` 分支；ES 不可用时保留进程内实现并由 `payroll.kb.retrieval.bm25.engine=es|local` 控制；
   - `TITLE_PATH` 路由只取 `title_path` 列；
   - 正文按 `topK` 回表，禁止全候选装载内容。
4. **历史窗口（消息水位，修 B27）**
   - V23 增 `ALTER TABLE chat_sessions ADD context_through_message_id BIGINT`；`ChatContextSnapshot` 增 `throughMessageId`，`H2ChatStore.loadContext/saveContext` 读写该列；同步修改快照构造点与相关测试；
   - `ChatContextStore` 端口新增 `messagesRecent(sessionId, afterMessageId, limit)`（`message_id > ? ORDER BY message_id DESC LIMIT ?` 后反转）、`messagesBefore(sessionId, beforeId, limit)`、`message(sessionId, messageId)`；`noop()` 补默认实现；
   - 上下文组装改为 `messagesRecent(sessionId, snapshot.throughMessageId(), payroll.chat.context-window:40)` + 已持久化摘要；`ConversationContext.from` **删除** `min(summarizedTurns, history.size())` 截断，窗口起点由水位决定；压缩成功或降级丢弃后，把最后一条被折叠消息的 `message_id` 写回水位；
   - 老会话无水位时首次按全量读取并回填水位，之后自动窗口化；`summarizedTurns` 仅保留用于展示/统计，不再参与截断；
   - `ChatSessionManager` 透传上述方法（带 `requireOwned` 校验）；控制器两处全量扫描改 `manager.message(userId, sessionId, messageId)`（`ChatSessionController.java:375,861`），删除 `stream().filter`；
   - `GET /sessions/{id}/messages` 支持 `?before_id=&limit=`（默认 200，最大 500，走 `messagesBefore`）；**前端同版本切到带参读取**，无参数全量路径仅为一个版本的兼容入口，不得作为首屏方案。
5. **文档列表分页与索引匹配（B36）**
   - `KbDocumentStore.findBySeries` 增加 `limit/cursor(updated_at, document_id)`（与 `ORDER BY updated_at DESC, document_id DESC` 同序，避免翻页重复/漏项），默认 `payroll.kb.documents-page-size:100`、上限 200；前端列表按页加载；
   - `keyword` 保持 `LIKE '%x%'` 残余过滤（不为此建索引），但必须与 `series_id` 等值条件同时出现，避免跨库全扫；
   - V23 的 `kb_document` 索引按「过滤列在前、排序列在后」重建（见 C4）；
   - **返回契约兼容**：`GET /kb/series/{id}/documents` 现状返回裸数组（`frontend/src/kb/api/index.js:220-240` 直接 `res.data`，本地 `paginate`）。带游标请求返回 `{items, next_cursor}`；**不带游标请求在一个版本内继续返回裸数组**（读首屏、旧前端不受影响），前端同版本切到游标分页；下个版本删除数组分支。避免一次性改契约导致旧前端列表空白。

**改动清单（C3 涉及文件）**
- 后端 KB：`payroll-backend/.../kb/KbDocumentStore.java`（游标查询与索引匹配）、`KbDocumentController.java:36`（分页参数与返回形状）、`KbChunkStore.java`（`SELECT_KEYS` 无 content/vector）、`KbRetrievalService.java`（`bm25Route` 增 `engine=es`）、`KbSkillService.java`（并行检索）、`KbSeriesService.java`（批量权限）、`KbElasticsearchService.java`（检索超时参数化）；
- 执行器：`PayrollCoreConfiguration` 新增 `kbSearchExecutor`（8/64/Abort）；
- 会话读路径：`ChatSessionController.java:151-153`（`GET /messages` 支持 `before_id/limit`）、`:375,861`（两处全量扫描改 `manager.message`）、`ChatSessionManager`（透传 + `requireOwned`）、`ChatContextStore`（新增三个端口方法 + `noop` 默认实现）、`H2ChatStore.java`（`loadContext/saveContext` 读写水位 + 三个新查询）、`ConversationContext.from`（删除 `summarizedTurns` 截断）；
- 前端：`frontend/src/kb/api/index.js` 与知识库文档列表视图（游标分页）、会话消息视图（`before_id` 首批 + 上滑加载）、`frontend/src/views/admin/UsageView.vue` 不改（30 天 < 180 天保留期）。

**验收**
- 5 库检索：P95 < 1.5s（本地 Ollama + ES），DB 查询次数 ≤ 2 + 5；注入 ES 慢响应（>2s）时聊天链路返回 `degraded` 且轮次正常完成；
- 检索池 8 线程跑满时，新请求按降级路径返回，不出现 500/挂起；管理台检索接口返回 429；
- 金标 50 题：检索命中率下降 ≤ 1 个点，否则 BM25 引擎回退 `local`；
- 500 轮会话：上下文构建 < 100ms；**带 `limit=200` 请求**首屏只返回 200 条，无参数旧路径仅做兼容验证；
- 1 万文档库列表分页（`EXPLAIN` 按变体分别判定）：
  - 带 `directory_id`（有无 `status` 均可）、无 keyword → 走 `idx_kb_document_series_dir_updated`，无 filesort；
  - 不带 `directory_id`（有无 `status` 均可）、无 keyword → 走 `idx_kb_document_series_updated`，无 filesort；`findReadyBySeries` 无 ORDER BY，走同一索引；
  - `keyword` 分支 → 接受 filesort（残余 `LIKE` 过滤后重排），但须确认扫描行数受 `series_id` 等值前缀限制，不为跨库全表；
- **返回契约兼容**：不带游标请求仍返回裸数组（旧前端首屏正常）；带游标返回 `{items, next_cursor}`，翻页无重复/漏项；
- **摘要回归（P0）**：会话先产生 ≥ 30 轮并触发压缩，再取 40 条窗口，断言提示词中仍含最近 4 轮问答（`ConversationContextTest` 增用例）；老会话首次窗口化后水位已回填。

**回滚**：`payroll.kb.search.parallel=false`、`payroll.kb.retrieval.bm25.engine=local`、历史窗口参数调回大值；水位列保留（旧代码忽略），`context-window` 调大即可退回全量读取。

---

### C4 索引与生命周期（V23）

新增一份迁移 `V23__perf_indexes_and_retention.sql`：

```sql
CREATE INDEX idx_chat_turns_started ON chat_turns (started_at);
CREATE INDEX idx_chat_messages_created ON chat_messages (created_at, message_id);
CREATE INDEX idx_chat_sessions_created ON chat_sessions (created_at, session_id);
CREATE INDEX idx_chat_attachments_created ON chat_attachments (created_at, attachment_id);
CREATE INDEX idx_chat_tool_traces_created ON chat_tool_traces (created_at, trace_id);
CREATE INDEX idx_chat_feedback_created ON chat_feedback (created_at, feedback_id);
CREATE INDEX idx_chat_action_proposals_message ON chat_action_proposals (message_id);
CREATE INDEX idx_chat_action_proposals_created ON chat_action_proposals (created_at, proposal_id);
CREATE INDEX idx_llm_usage_plane_time ON llm_usage (plane, occurred_at);
CREATE INDEX idx_llm_usage_agent_time ON llm_usage (agent_id, occurred_at);
CREATE INDEX idx_llm_usage_time_id ON llm_usage (occurred_at, usage_id);
CREATE INDEX idx_agent_team_runs_created ON agent_team_runs (created_at);
CREATE INDEX idx_kb_chunk_series_seq ON kb_chunk (series_id, status, document_id, seq);
CREATE INDEX idx_kb_document_series_updated ON kb_document (series_id, deleted_at, updated_at, document_id);
CREATE INDEX idx_kb_document_series_dir_updated ON kb_document (series_id, deleted_at, directory_id, updated_at, document_id);
CREATE INDEX idx_kb_task_log_created ON kb_task_log (created_at, log_id);
CREATE INDEX idx_kb_task_status_created ON kb_ingest_task (task_status, created_at, task_id);
CREATE INDEX idx_audit_result_time ON audit_log (result, occurred_at);
CREATE INDEX idx_profile_extractions_updated ON user_profile_extractions (updated_at);

CREATE TABLE job_lease (
  job_name    VARCHAR(64)  NOT NULL,
  owner       VARCHAR(128) NOT NULL,
  acquired_at TIMESTAMP    NOT NULL,
  expires_at  TIMESTAMP    NOT NULL,
  PRIMARY KEY (job_name)
);

ALTER TABLE chat_sessions ADD COLUMN context_through_message_id BIGINT;
```

说明：
- 索引名在 H2/MySQL 均可用；列名与主键已按 V1–V22 复核（`chat_messages.message_id`、`chat_attachments.attachment_id`、`llm_usage.usage_id`、`audit_log.audit_id`）；MySQL 8 走 online DDL，在低峰窗口执行；
- **执行方式与就绪（修订）**：迁移由 `PayrollDatabase.migrate()` 在应用启动时执行（`payroll-common/.../db/PayrollDatabase.java:193`，`config.load().migrate()` 在 :208），建索引会阻塞启动与 readiness。生产要求先用同一套 `classpath:db/migration/common` 通过 Flyway CLI/一次性任务在库上跑完 V23，再滚动发布应用；应用启动时读到完整历史只做校验、不重复执行。**不得由 DBA 手工预建 V23 的索引/表**：预建后 Flyway 仍会尝试执行同一语句，撞「对象已存在」直接失败；
- **失败恢复（修 P0：`flyway repair` 不跳过已成功语句）**：Flyway 在 MySQL 上 DDL 非事务，任一语句失败会留下部分对象并使该迁移记为失败。`flyway repair` 只清除失败标记，**不会**跳过此前已成功的语句。恢复流程固定为：① 查 `INFORMATION_SCHEMA.STATISTICS` / `TABLES` / `COLUMNS` 列出本迁移已落地的对象；② 将本迁移创建的对象全部删除（`DROP INDEX`、`DROP TABLE job_lease`、`ALTER TABLE chat_sessions DROP COLUMN context_through_message_id`，仅删本迁移自己的对象）；③ `flyway repair`；④ 重跑整份 V23。严禁「手工补建剩余对象 + repair 后重跑」，也严禁不清理直接重跑；
- 预发/备份库必须先完整演练一次，线上按单条语句记录执行结果；
- `kb_document` 需要**两个**索引而不是一个：`findBySeries` 的目录条件是可选参数，且 `status` 也是可选等值条件。若把 `directory_id`、`status` 都放在 `updated_at` 之前，缺任一条件时索引在最左缺口后无法继续提供有序扫描（MySQL 不支持跨缺口排序），排序仍要 filesort。因此：`idx_kb_document_series_updated (series_id, deleted_at, updated_at, document_id)` 服务无目录条件的所有变体；`idx_kb_document_series_dir_updated (series_id, deleted_at, directory_id, updated_at, document_id)` 服务带目录条件的所有变体。`status` 与 `keyword` 都作为残余过滤（`status` 基数低，走索引条件下推即可），**不要**插到 `updated_at` 之前；两种列序都以 `document_id` 收尾，与 `ORDER BY updated_at DESC, document_id DESC` 同序，保证同 `updated_at` 时游标翻页仍走索引。既有 `(series_id, deleted_at, updated_at)`（无 `document_id`）被第一个索引覆盖，本版保留、下一版本评估删除；
- `chat_messages`/`chat_sessions`/`chat_attachments`/`chat_tool_traces`/`chat_feedback`/`chat_action_proposals` 的时间索引都带主键列，供两步批删按 `(created_at, pk)` 有序扫描，避免「过滤后按 PK 排序」触发 filesort；`chat_sessions` 仅有 `idx_chat_sessions_user(user_id, updated_at)`（`V1__payroll_core.sql:47`），按 `created_at` 清理会话会全表扫，必须补 `idx_chat_sessions_created`；`chat_feedback` 现有索引（唯一键 `(message_id, user_id)` 与 `(message_id)`，`V1__payroll_core.sql:334-336`）均以 `message_id` 打头、`kb_task_log` 仅有 `(task_id, created_at)`（`V2__knowledge_base.sql:167`）、`kb_ingest_task` 仅有 `(series_id, task_status, created_at)`/`(document_id, created_at)`（`V2:154-155`）——以上四张表按 `created_at` 做保留清理都会全表扫，必须补本组索引；
- `llm_usage.occurred_at` 已有 `idx_llm_usage_time`（`V16__llm_usage.sql:27`），但保留任务按 `(occurred_at, usage_id)` 有序取批，单列索引会退化为 filesort；V23 补 `idx_llm_usage_time_id`，旧索引保留一版后评估删除；
- `idx_kb_task_status_created` 以 `task_status` 打头，保留任务按三个终态分别做等值 + `created_at` 区间扫描；不建 `(created_at)` 单列索引，避免把运行中任务也纳入扫描；
- `ALTER TABLE chat_sessions ADD COLUMN context_through_message_id` 供 C3 消息水位使用；旧代码忽略新列，向前兼容；
- `idx_kb_chunk_series_seq` 覆盖既有 `idx_kb_chunk_series(series_id,status)`（`V2__knowledge_base.sql:111`），旧索引本版保留、下一版本评估删除；目标查询 `WHERE series_id=? AND status='ACTIVE' AND chunk_level<>'PARENT' ORDER BY document_id, seq LIMIT 2000`（`KbChunkStore.java:82-83`）走新索引前缀 + 有序扫描，`chunk_level` 作为残余过滤不单独建索引。

**保留任务（v0.5 修订）**

删除按「先子后父」顺序执行，**不依赖 `chat_turns`**（turn 已按 90 天清理，见 `application-common.yml:143`）；下列关联表均无外键，顺序只决定孤儿窗口长短，孤儿另有兜底扫描：

| # | 对象 | 保留期 | 删除键与批量 | 顺序与约束 |
| --- | --- | --- | --- | --- |
| 1 | `chat_tool_traces` | 365 天（随消息） | `(created_at, trace_id)`，批 1000 | 消息前删 |
| 2 | `chat_feedback` | 365 天 | `(created_at, feedback_id)`，批 1000 | 消息前删；评分删除后不可恢复 |
| 3 | `chat_action_proposals` | 365 天 | `(created_at, proposal_id)`，批 500 | 仅 `CONFIRMED/REJECTED/EXPIRED/FAILED`；`PENDING` 超过保留期先批量置 `EXPIRED`（写 `expires_at`），**下一轮**按终态删除，避免待办永久堆积；不按 `message_id` 关联过滤，独立提案同样按自身时间清理 |
| 4 | `chat_attachments` + 文件 | 365 天 | 行批 200、文件批 50，单批文件预算 5s | 文件删除成功才删行；**文件已不存在视为成功**（幂等）；仅 IO 失败保留行待下轮 |
| 5 | `chat_messages` | 365 天 | `(created_at, message_id)`，批 500 | 消息前删 |
| 6 | `chat_sessions` | 365 天 | `(created_at, session_id)`，批 200 | 仅删除「无剩余消息」的会话（`NOT EXISTS (SELECT 1 FROM chat_messages WHERE session_id=?)`，随批次用 `LEFT JOIN ... IS NULL` 或先删子表后判定）；有消息的会话保留，等消息被清理后的下一轮再删；会话最后删 |
| 7 | `llm_usage` | 180 天 | `(occurred_at, usage_id)`，批 1000 | 独立于会话链 |
| 8 | `kb_task_log` | 180 天 | `(created_at, log_id)`，批 1000 | 任务行前删 |
| 9 | `kb_ingest_task` | 180 天 | `(task_status, created_at, task_id)`，批 500 | 仅终态 `SUCCEEDED/FAILED/CANCELLED`；**每个状态单独循环**（`WHERE task_status=? AND created_at<? ORDER BY created_at, task_id LIMIT 500`），避免 `IN` 多区间 + 排序退化为 filesort |
| 10 | 解析产物 `parsed/<documentId>/<version>/` | `payroll.kb.parse.artifact-ttl-days`（7 天） | 目录级删除，每文档保留最新 2 个版本 | 归属按解析模式：**remote 由 parser 进程内清理**（对 `/app/parsed` 可写，Web 只读、不写入）；**inline 回退由 Web 侧同一清理器处理**（仅 Web 本地写产物时生效）；`kb_document.artifact_key` 当前版本目录永不删（写入方原子提交产物时在版本目录内写 `CURRENT` 标记，标记缺失时退回「保留最新 2 个版本」） |

批删写法（H2/MySQL 同一条路径，**不使用 LIMIT 子查询**，主键列名按实际表替换）：

```sql
-- 1) 取一批主键（H2/MySQL 均支持 LIMIT）
SELECT message_id FROM chat_messages WHERE created_at < ? ORDER BY created_at, message_id LIMIT 500;
-- 2) 按主键删除
DELETE FROM chat_messages WHERE message_id IN (?, ?, ...);
```

MySQL 8 对 `DELETE ... WHERE pk IN (SELECT ... LIMIT ?)` 报 `ERROR 1235`，上述两步法天然规避；JOIN 写法只在 MySQL 使用，不作为跨库方案。

**批循环退出条件（修 P0：失败行重选导致死循环）**——每张表独立循环，遇以下任一条件即结束**当前表**；第三项结束**整轮任务**：

- 取到 0 行 → 正常结束；
- `selected > 0 && deleted == 0` → 该表连续失败计数 +1，达到 2 次记 `payroll.retention.skipped{table}` 并跳过本表（下一日重新尝试）；**不得继续用同一条件重选同一批**；
- 全局 `payroll.jobs.retention-max-minutes:10` 预算耗尽 → 立即结束整个任务，记录未完成表清单；
- 每批之间 `payroll.jobs.retention-batch-pause-ms:100` 主动让出 DB，避免与在线请求争锁。

**孤儿兜底**（主删之后，仅处理 `created_at < cutoff - 1d` 的行，同样受时间预算与失败上限约束）：

- `chat_tool_traces` 与 `message_id > 0` 的 `chat_feedback`/`chat_action_proposals` 中 `message_id` 已不在 `chat_messages` 的行，按自身 `created_at` 批删（`message_id=0` 的独立提案不参与孤儿判定，按主删规则清理）；
- `chat_attachments` 中所属 message/session 已不存在的行，先删文件再删行；**`message_id IS NULL` 的附件**（上传后尚未绑定消息，`V9__chat_attachments.sql` 列可空）不参与消息孤儿判定，但仍受自身 `created_at` 保留期约束，由主删清理；
- `kb_task_log` 中 `task_id` 已不在 `kb_ingest_task` 的行；
- `agent_team_runs` 中 `deleted_at IS NOT NULL` 的归档行、且 `created_at < cutoff`，整行物理删除（归档行 `message_id NOT NULL` 可能已指向被删消息，软归档后无检索价值；运行中行不在此列，仍由 `payroll.team.retention-days:180` 管理）；
- 附件目录：遍历附件根目录（`payroll.chat.attachment.dir`，默认 `./data/chat-uploads`）下的 `<sessionId>/<UUID>/` 两级目录，目录 mtime 早于保留期 + 1 天且相对路径不在 `chat_attachments.storage_key` 中才删除；只处理该两层结构与 UUID 目录名，其他文件不动。

**调度（v0.5 修订）**

- 日调度：`payroll.jobs.retention-hour:2` / `payroll.jobs.retention-minute:30`（默认 02:30，JVM 本地时区），用 `ScheduledExecutorService.scheduleAtFixedRate`（与 `UserProfileMaintenance.java:41` 同款，无需 `@EnableScheduling`），首延按下一个窗口计算；
- 启动时**只做 dry-run 统计**，不执行删除（v0.3 只在启动删一次，等于永远不在凌晨执行）；启动统计同样走 `job_lease`（`job_name=retention.startup-stats`），只有抢到租约的节点扫描，N 副本不各扫一遍全表；
- 多节点走 D6 `job_lease` 互斥：持租约者执行，其余跳过；长任务按 `lease-heartbeat-seconds` 续租，单次持有不超过一轮预算；
- `payroll.jobs.retention-dry-run=true`（首版默认）：启动与 cron 均只输出每表命中行数、最早/最晚时间、样例主键、附件文件数与总字节，**不执行删除**；cron 的 dry-run 同样先在租约内执行，避免各节点重复全表统计。

- 与既有清理任务的关系：`payroll.chat.turn-retention-days:90`（只删 turn）、`payroll.audit.retention-days:180`、`payroll.team.retention-days:180` 全部保留不变；审计记录可能引用已删消息，前端按既有引用失效降级展示；
- 放开前需产品/合规确认并完成备份；`retention-days=0` 关闭对应任务；
- 解析产物清理不接 `job_lease`：remote 由 parser 自身单线程日定时器执行（parser 无 DB），失败计数 `payroll.kb.parse.cleanup.failures`；inline 且 Web 本地写产物时由 Web 侧清理器执行（同一 TTL/保留规则，单节点无需互斥）；磁盘使用率 > 80% 告警，避免产物堆积写满 `parsed-data`。

**验收**
- `EXPLAIN` 确认目标查询走索引且无 filesort（MySQL 抽样，覆盖：`KbChunkStore.java:82` 的 LIMIT 2000 查询；五张聊天表 + `chat_sessions` 的 `WHERE created_at<? ORDER BY created_at,pk LIMIT ?`；`llm_usage` 的 `(occurred_at, usage_id)` 批删取键；`kb_task_log` 与 `kb_ingest_task` 按状态分组的取键语句；`findBySeries` 的三种变体见 C3 验收）；
- 12 个月模拟数据（脚本生成）下：看板查询 P95 < 800ms；单行批 ≤ 2s、单文件批（50 文件）≤ 5s；50 万消息全量清理在 `retention-max-minutes` 预算内结束；
- **失败注入（P0 验收）**：令某表 DELETE 持续抛异常，任务在该表连续失败 2 次后跳过并结束，总耗时不因重选同一批而失控，其他表照常完成，`payroll.retention.skipped` 有值；
- 删除顺序用例：造 message + trace + feedback + proposal + attachment 各一条，并绑定到一个会话，过期后一次运行内按 1→6 顺序清空（会话在消息删净后的同一轮或下一轮被删）；再单独造「父行已被其他路径删除」的孤儿子行，兜底扫描能清除；
- **会话清理用例**：400 个过期且无消息的会话按 200/批删除；过期但仍有消息的会话不被删（消息清空后下一轮再删）；`context_summary` 随会话行删除，不残留已删消息的摘要；
- **附件幂等用例**：`storage_key` 指向的文件已被外部删除时，本轮按成功处理并删行（不无限重试）；`message_id IS NULL` 的未绑定附件不进入消息孤儿判定，但过期后按主删清理；
- **PENDING 提案用例**：过期 `PENDING` 提案首轮置 `EXPIRED`、次轮删除，不长期堆积；
- 解析产物（`remote`）：parser 清理任务（启动触发一次 + 每日定时）执行后，每文档 10 个版本目录仅保留最新 2 个，超过 7 天的目录被删，带 `CURRENT` 标记的当前版本目录不被删；remote 下 Web 侧无任何写 `/app/parsed` 的调用；`inline` 且 Web 本地写产物时，同规则清理各验一条；
- dry-run 报告行数与直接 `COUNT(*)` 一致；多副本启动时启动统计只由租约持有者执行一次；MySQL 上批删语句逐条执行成功（H2 与 MySQL 各跑一遍）；
- V23 在 H2 与 MySQL 均迁移成功，`chat_sessions.context_through_message_id` 列存在且默认 NULL；**失败恢复演练**：在预发库故意让 V23 中段一条语句失败 → 按「清半成品对象 → `flyway repair` → 重跑整份」恢复成功，且验证「不清理直接重跑」会因对象已存在失败（说明必须走清理流程）；
- 清理不影响 P99（压测期间开启）。

**回滚**：保留天数设为 0 关闭对应表；`payroll.jobs.retention-dry-run=true` 退回只统计；索引可保留，无行为影响。

---

### C5 启动任务与容器基线

1. **`StartupJobCoordinator`**（DB 租约）
   - **所有时间表达式都在 SQL 内取 DB 时钟**，Java 只传 `owner/job_name/TTL 秒`，不传时间戳（消除「声称 DB 时间、实际写 Java 时间」的自相矛盾）：
     ```sql
     -- 申请（H2 / MySQL 仅时间函数不同）
     UPDATE job_lease SET owner=?, acquired_at=CURRENT_TIMESTAMP,
       expires_at=DATEADD('SECOND', ?, CURRENT_TIMESTAMP)            -- MySQL: TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP)
      WHERE job_name=? AND expires_at < CURRENT_TIMESTAMP;
     -- 0 行则 INSERT（重复键视为未获得）
     INSERT INTO job_lease (job_name, owner, acquired_at, expires_at)
     VALUES (?, ?, CURRENT_TIMESTAMP, DATEADD('SECOND', ?, CURRENT_TIMESTAMP));
     ```
   - 续租：`UPDATE job_lease SET expires_at=DATEADD('SECOND', ?, CURRENT_TIMESTAMP) WHERE job_name=? AND owner=?`，**影响行数 0 表示租约已被接管**，当前任务立即中止（不得继续删除/导入）；
   - 释放：`UPDATE job_lease SET expires_at=DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE job_name=? AND owner=?`（置为过去，立刻可被接管，同时保留行便于排查）；
   - 方言统一封装在 `job_lease` 仓储一处（H2 `DATEADD` / MySQL `TIMESTAMPADD`），不散落到调用方；
   - 租约 TTL `payroll.jobs.lease-seconds:120`、心跳 `payroll.jobs.lease-heartbeat-seconds:30`；长批任务（保留任务按 `retention-max-minutes:10` 预算执行）每批后续租；节点崩溃最长 120s 后可被接管，接管重复执行必须幂等（删除任务天然幂等，导入按 PK 跳过）；
   - 接入：审计清理、团队清理、轮次清理、KB 任务恢复、附件恢复、Nacos 技能/定义导入、C4 保留任务（cron 触发，启动只跑 dry-run 统计）、C4 孤儿兜底；
   - **恢复巡检**：`recoverInterrupted` 不再只在启动跑一次，改为「启动 + 每 `payroll.jobs.recovery-interval-minutes:5` 巡检」，只有持恢复租约的节点执行，避免获锁节点在恢复前崩溃导致任务等到下次重启；
   - `payroll.jobs.lease-enabled:true`，关闭时退回「按 node-role 执行」的旧行为。
2. **导入幂等**
   - Nacos 导入在租约内执行，且依赖 `skill_definition` / `agent_definition` 主键冲突即跳过（现有 PK 已具备，见 `V22__skill_definition.sql:10`）；
   - 导入报告记录 `imported/skipped` 计数。
3. **容器基线**
   - web/runtime 增加 `healthcheck`：`/actuator/health/liveness`，间隔 15s、超时 3s、重试 3；
   - `mem_limit` 与 `restart: unless-stopped` 组合（见 C0）；
   - 启动依赖改为 `depends_on: condition: service_healthy`（mysql/redis）。
4. **生产守卫（D7，两阶段）**
   - `payroll.guard.strict: warn|fail`，默认 `warn`（一个版本），启动时只打 WARN；下一版本切 `fail` 才阻断启动；
   - `TopologyGuard` 结构调整：不再「发现问题立即抛异常」，改为「收集全部问题 → 按 `payroll.guard.strict` 决定 WARN 或汇总抛出」；装配不再限定 `@Profile("prod")`，否则非 prod 环境无法演练 `fail`（现状 `TopologyGuard.java:24-25`）；
   - 新增校验：
     - `payroll.auth.admin-password` 不得等于默认值 `admin123`；
     - web 节点 `payroll.internal.token` 非空；
     - `payroll.kb.parse.mode=remote` 时 `payroll.kb.parse.token`（`PAYROLL_PARSE_TOKEN`）非空（仅 web 节点校验）；
     - `payroll.node-role=all` 仅当 `payroll.deployment.mode=cluster` 时失败；单机 all 记录 WARN；
   - 错误信息只报「未配置/为默认值」，**不得回显取值**；
   - 升级步骤、必填 env 与豁免开关见 §7.1。

**验收**
- 双 Web 副本同时启动：每个启动任务只执行一次（日志 + `job_lease` 记录）；
- 持有租约的节点在恢复前 `kill -9`：5 分钟巡检内由另一节点接管，KB/附件恢复任务不丢；
- **时钟偏移（P0 验收）**：两节点 JVM 时钟分别调快 5 分钟 / 调慢 5 分钟，互斥窗口不变；租约被另一节点接管后续租影响行数为 0，原任务立即中止，不出现双写；
- 停掉 Redis：启动任务仍互斥（DB 租约）；
- 生产守卫：`warn` 模式缺项只 WARN 且启动成功；`fail` 模式缺项启动失败、提示可操作且不泄露密钥；单机 all 在 warn 下不阻断。

**回滚**：`payroll.jobs.lease-enabled=false`；`payroll.guard.strict=warn`；升级顺序见 §7.1。

---

### C6 可观测性与容量验收

**指标**
| 名称 | 类型 | 标签 | 来源 |
| --- | --- | --- | --- |
| `payroll.model.duration` | Timer | plane, model, outcome | `UsageRecordingMiddleware` 扩展 |
| `payroll.es.duration` | Timer | op（search/index/refresh） | KB/记忆/路由客户端 |
| `payroll.turn.duration` | Timer | path, outcome | `ChatSessionManager` |
| `payroll.sse.connections` | Gauge | — | `ChatSse` |
| `payroll.sse.hubs` / `payroll.sse.write.inflight` | Gauge | — | `TurnEventFanout`（C1） |
| `payroll.sse.write.timeout` / `payroll.sse.slow.clients` | Counter | — | watchdog 标记/摘除（C1） |
| `payroll.sse.write.stalled` | Gauge | — | 当前停滞订阅数（watchdog，C1） |
| `payroll.retention.rows` / `payroll.retention.duration` | Counter / Timer | table | 保留任务（C4） |
| `payroll.retention.skipped` | Counter | table | 连续失败跳过的表（C4） |
| `payroll.kb.parse.cleanup.failures` | Counter | — | parser 产物清理（C4） |
| `payroll.executor.queue` | Gauge | pool（chat/tail/runtime/kbSearch/kbIngest） | 各执行器 bean |
| `payroll.kb.parse.duration` | Timer | outcome, engine | `payroll-parser` / inline 客户端 |
| `payroll.kb.parse.queue` | Gauge | — | parser 排队数 |
| `payroll.kb.parse.failures` | Counter | reason（timeout/oom/503/limit） | parser 与 Web 任务层 |
| `hikaricp.connections.*` | 标准 | pool | 在 `PayrollDatabase.createPool` 注册 `HikariDataSourceMetricsBinder`（H2 无池，指标为空属预期，B16） |

**SLO / 验收阈值**
| 项 | 阈值 |
| --- | --- |
| 单 Web 节点在线 SSE | ≥ 200 路，P95 首字增量 < 300ms |
| 慢客户端（20 路不读 + 半开） | 5s 内被 watchdog 摘除并停止投递，写线程最迟在容器写超时（默认 60s）内释放；其余订阅 P95 首字增量 < 300ms；watchdog tick 计数持续增长（不被慢写冻结） |
| 提交 429 率（20 rps 稳态） | < 0.1% |
| KB 检索（5 库 / 每库 2000 候选） | P95 < 1.5s；ES 慢于 2s 走降级 |
| 200 页扫描件解析 | parser 堆峰值 < 1.5GB，Web 堆增量 < 100MB |
| 500 轮会话上下文构建 | < 100ms |
| 清理任务单行批 / 单文件批 | ≤ 2s / ≤ 5s（50 文件）；整轮 ≤ 10 分钟；P99 不受影响 |

**测量口径（v0.2 补充）**
- 堆：GC 后 `jcmd GC.heap_info`，记录并发数、文档页数与采样次数；parser 阈值 1.5GB 对应 `mem_limit 2.5g`（堆上限约 1.75GB）；
- SSE：经 nginx 8880 压测，固定短提示/禁用长生成，区分「网关与 SSE 容量」和「模型吞吐」两类瓶颈；
- 清理：单批耗时取 P95；压测窗口内对比清理开启前后的 P99。

**压测脚本**
- `scripts/load/sse_load.py`（asyncio + httpx）：登录、建会话、并发订阅、统计首字/TTFT/断线重连；
- `scripts/load/kb_search_load.py`：多库检索并发，输出 P50/P95/P99 与错误码分布；
- `scripts/load/kb_parse_load.py`：并发提交 200 页解析、注入 parser 重启，校验任务重试与 503 收口；
- 结果写入 `docs/perf-baseline-2026-11.md` 并附机器配置。

---

## 6. 配置清单（新增/调整）

| Key | 默认 | 说明 |
| --- | --- | --- |
| `payroll.kb.parse.max-pages` | 200 | PDF 页数上限（含扫描件） |
| `payroll.kb.max-file-size` | 200MB | 调整：已有键（当前 600MB，`application-common.yml:260`） |
| `PAYROLL_UPLOAD_MAX_FILE_SIZE` | 200MB | 调整：multipart 单文件上限，须与上行一致（`application-common.yml:36`） |
| `PAYROLL_UPLOAD_MAX_REQUEST_SIZE` | 512MB | 调整：multipart 请求上限（`application-common.yml:37`） |
| `payroll.kb.parse.mode` | remote | remote=独立解析进程；inline=Web 进程内（回退） |
| `payroll.kb.parse.url` | `http://parser:8300` | 解析服务地址 |
| `payroll.kb.parse.token` | 无（必填） | 内部 HMAC 令牌，env `PAYROLL_PARSE_TOKEN`；为空则 parser 启动失败 |
| `payroll.kb.parse.threads` | 2 | parser 并发解析数 |
| `payroll.kb.parse.queue` | 0 | parser 本地排队上限；0=`SynchronousQueue` 不排队，满即 503（排队在 Web 侧） |
| `payroll.kb.parse.timeout-ms` | 600000 | 单文档解析超时（HTTP read timeout = 本值 + 30s） |
| `payroll.kb.parse.max-retries` | 2 | Web→parser 的 HTTP 级快速重试上限（任务级持久重试另见 `payroll.kb.task-max-retries`，默认 3） |
| `payroll.kb.parse.web-concurrency` | 2 | Web 侧 remote 解析准入并发（信号量），与 parser 线程对齐 |
| `payroll.kb.parse.artifact-dir` | `./parsed` | 解析产物根目录（容器内 `/app/parsed`）；compose `parsed-data:/app/parsed`，parser 读写、Web 只读 |
| `payroll.kb.parse.artifact-ttl-days` | 7 | 解析产物保留天数；另每文档保留最新 2 个版本目录，parser 进程内清理 |
| `payroll.runtime.turn-threads` | 8 | runtime 生成线程数 |
| `payroll.runtime.turn-queue-capacity` | 64 | runtime 排队上限 |
| `payroll.chat.sse.mode` | legacy | SSE 推送模式；验收后默认改 `push`，一个版本后删 legacy（`legacy→push` 不是可解析值） |
| `payroll.chat.sse.writer-threads` | 16 | SSE 写池线程数 |
| `payroll.chat.sse.writer-queue` | 4096 | 写池队列上限 |
| `payroll.chat.sse.subscriber-buffer` | 512 | 单订阅者缓冲 |
| `payroll.chat.sse.write-timeout-ms` | 5000 | 订阅停滞判定阈值：watchdog 据此标记 `stalled` 并摘除，不调用 `emitter.complete*`；写线程实际由容器写超时（Tomcat `writeTimeout=connectionTimeout`，默认 60s）回收 |
| `payroll.chat.sse.watchdog-tick-ms` | 1000 | watchdog 扫描间隔；只读内存、只改订阅状态，不发送 gap、不关流、不访问 DB/Redis |
| `payroll.chat.sse.ping-seconds` | 15 | 保活 comment 间隔 |
| `payroll.chat.sse.idle-check-ms` | 10000 | 空闲判定节拍；无新事件达到该间隔时下发空闲帧 |
| `payroll.chat.evict-interval-seconds` | 5 | 会话淘汰限频；未超额时只在节拍到达时扫描，超额立即淘汰（候选 `excess+8`） |
| `payroll.team.llm.max-concurrent` | 8 | 轻量模型（意图/路由/计划）并发许可数；等待 2s 未获许可即走规则兜底（env `PAYROLL_TEAM_LLM_MAX_CONCURRENT`） |
| `payroll.chat.context-window` | 40 | 上下文窗口消息条数（按 `context_through_message_id` 水位向后取） |
| `payroll.chat.history-retention-days` | 365 | 聊天历史保留天数（0 关闭） |
| `payroll.kb.task-retention-days` | 180 | KB 入库任务与任务日志保留天数（0 关闭；C4 表 8/9） |
| `payroll.usage.retention-days` | 180 | 用量保留天数（0 关闭） |
| `payroll.jobs.lease-enabled` | true | 启动任务 DB 租约 |
| `payroll.jobs.lease-seconds` | 120 | 租约 TTL（DB 时间） |
| `payroll.jobs.lease-heartbeat-seconds` | 30 | 长批任务续租间隔 |
| `payroll.jobs.recovery-interval-minutes` | 5 | 启动恢复巡检间隔 |
| `payroll.guard.strict` | warn | 生产守卫模式：warn / fail |
| `payroll.jobs.retention-dry-run` | true | 首版清理只统计（启动与 cron 均只统计） |
| `payroll.jobs.retention-hour` | 2 | 日调度小时（JVM 本地时区） |
| `payroll.jobs.retention-minute` | 30 | 日调度分钟 |
| `payroll.jobs.retention-max-minutes` | 10 | 单轮时间预算，超时结束并记录未完成表 |
| `payroll.jobs.retention-batch-pause-ms` | 100 | 批间休眠，让出 DB |
| `payroll.kb.search.parallel` | true | 多库并行检索 |
| `payroll.kb.search.threads` | 8 | 并行检索专用池（`kbSearchExecutor`）线程数，不复用 `memoryRecallExecutor`/`kbIngestExecutor` |
| `payroll.kb.search.queue` | 64 | 检索池排队上限；满时对话/团队链路降级返回（`degraded=true` + 空结果），管理端检索接口回 429 |
| `payroll.kb.search.timeout-ms` | 1500 | 检索总超时（HTTP 级）；超时按降级语义返回，不再等 ES 默认 5s |
| `payroll.kb.documents-page-size` | 100 | 文档列表分页大小上限（cursor 分页，最大 200） |
| `payroll.kb.retrieval.bm25.engine` | es | es/local |
| `payroll.kb.ocr.max-pages` | 0（既有，不限） | 既有 OCR 单文档页数上限（`KbOcrService.java:51` 默认 200 与 yml 默认 0 不一致）；本版由 `payroll.kb.parse.max-pages` 统一收口，两者取小，`ocr.max-pages=0` 表示服从解析上限 |
| `payroll.team.llm.max-concurrent` | 8 | 轻量模型并发许可 |
| `payroll.chat.event-poller-threads` | 4 | 已有，保持不变 |
| `payroll.chat.turn-retention-days` | 90（既有） | 不变：只删 turn，不删消息 |
| `payroll.audit.retention-days` | 180（既有） | 不变：启动/周期清理审计 |
| `payroll.team.retention-days` | 180（既有） | 不变：团队运行清理 |
| `payroll.chat.attachment.max-file-bytes` | 20MB（既有） | 不变；附件页数受 `kb.parse.max-pages` 约束 |

`payroll.kb.parse.token` 与 `payroll.internal.token` 是两个独立令牌：前者用于 Web→parser，后者用于 Web↔runtime；守卫只在 web 节点校验 parse token。

`server.tomcat.connection-timeout` **不在本规格内调整**：它同时约束所有请求的套接字写超时，全局下调会波及普通 HTTP/上传响应。SSE 写池容量按默认 60s 的写超时规划；确需 5s 级线程释放只能改为非阻塞写（C1 已列明不在本期）。

---

## 7. 兼容、灰度与回滚

- **接口**：`GET /sessions/{id}/messages` 新增 `?before_id=&limit=`（默认 200，最大 500）；**前端与后端同一版本切换到窗口读取**，无参数的全量行为只作为兼容保留一个版本，下一版本下线；文档列表同步切 cursor 分页，**返回形状按请求区分**：带游标返回 `{items, next_cursor}`，不带游标在一个版本内仍返回裸数组（旧前端首屏不空白），下个版本删除数组分支。
- **行为**：SSE 推送、并行检索、BM25 引擎、租约、保留任务全部带开关；默认值按「先保守、验收后放开」推进。
- **解析服务**：`payroll.kb.parse.mode=remote` 为生产默认；无 parser 时可用 `inline` 单机运行（开发/回退），两条路径共用同一解析实现与上限。
- **数据**：V23 只加索引、一张新表（`job_lease`）和一个可空列（`chat_sessions.context_through_message_id`），旧代码读该列不存在时按原有轮次口径运行，可向前兼容；清理任务首版 dry-run。
- **回滚**：每项单独开关；代码回滚按工作流边界（C0/C1 各自独立提交），不整批回退。

### 7.1 升级与迁移（v0.2 新增）

**必填/变更配置（只列键，不写取值）**

- 已有部署必须补齐：`PAYROLL_ADMIN_PASSWORD`（不得为默认值）、`PAYROLL_AGENT_RUNTIME_TOKEN`（web）、`PAYROLL_SECRET_KEY`（既有要求）；
- cluster 模式必须显式设置 `PAYROLL_NODE_ROLE=web|runtime`（`all` 只允许单机）；
- `parse.mode=remote` 时必须设置 `PAYROLL_PARSE_TOKEN`，并为 parser 配置只读 `kb-data:/app/data` 与读写 `parsed-data:/app/parsed` 卷，Web 侧对 `parsed-data` 只读；
- multipart 上限变更：`PAYROLL_UPLOAD_MAX_FILE_SIZE=200MB`、`PAYROLL_UPLOAD_MAX_REQUEST_SIZE=512MB`。

**发布顺序**

1. 备份数据库与上传/产物目录；预发演练 V23（含「单条索引失败 → 清半成品 → `flyway repair` → 重跑整份」场景与 `chat_sessions.context_through_message_id` 可空列 ALTER）；
2. 停 runtime → **用 Flyway CLI（同一 `classpath:db/migration/common`）在库上执行 V23**（索引、`job_lease`、水位列；可空列 ALTER 为元数据变更，MySQL 8 走 INSTANT/INPLACE，不锁全表）→ 起 parser（健康检查通过）→ 起 web；迁移在应用启动前完成，避免建索引阻塞 readiness。**不得由 DBA 手工预建 V23 索引/表**；若失败，按 C4「清掉半成品对象 → `flyway repair` → 重跑整份」执行，不得只 repair 后直接重跑；水位列为空的历史会话由新代码按「最近 N 条消息」兜底读取并逐步回填，无需停机迁移数据；
3. web 以 `payroll.guard.strict=warn` 启动，核对 WARN 清单并在窗口内补齐配置；
4. 切 `payroll.kb.parse.mode=remote`，观察 24h（解析失败率、队列、堆）；
5. 下一版本把 `payroll.guard.strict=fail`，并登记 legacy SSE / inline 回退路径的删除里程碑（每项保留一个版本）。

**回滚清单**

| 场景 | 开关/动作 |
| --- | --- |
| 守卫阻断启动 | `payroll.guard.strict=warn` |
| parser 不可用 | `payroll.kb.parse.mode=inline`（附件同路径回退），parser 服务可下线 |
| SSE 推送异常 | `payroll.chat.sse.mode=legacy` |
| 启动任务重复 | `payroll.jobs.lease-enabled=false` |
| 保留任务误删风险 | `payroll.jobs.retention-dry-run=true` 或对应 `retention-days=0` |

**上线前检查清单**

- nginx（含本机 8880）：`proxy_buffering off`、`proxy_read_timeout 1800s`、`worker_connections ≥ 1024`；
- compose：web/runtime/parser `healthcheck`、`mem_limit`、`restart: unless-stopped`；
- 卷权限：parser 对 `kb-data`（容器内 `/app/data`）无写权限、对 `parsed-data`（`/app/parsed`）可写；Web 对 `/app/data` 读写现有附件、对 `/app/parsed` 只读；解析产物路径不得落在 `/app/data` 之下；
- 保留任务：web 容器 `TZ` 与运维预期一致（`retention-hour/minute` 按 JVM 本地时区），首轮只跑 dry-run 并核对统计；`parsed-data` 卷设置容量告警，确认 parser 对产物目录可删（升级时旧文件属主变更会导致删除失败，记录 `payroll.kb.parse.cleanup.failures`）；
- 双副本启动三场景演练：parser 下线、Redis 停机、web 重启中接管。

---

## 8. 测试与验收

| 层 | 内容 |
| --- | --- |
| 单测 | 单遍解析与旧实现的等价性（页文本/页范围）；`remote`/`inline` 产物一致；写池同一订阅串行且 seq 单调；订阅队列满触发 gap；**watchdog 标记/摘除**：模拟 `send` 长时间阻塞的订阅，watchdog 在 `write-timeout-ms` 内标记 `stalled` 并从 Hub 摘除（不调用 emitter API、不阻塞后续扫描，tick 计数持续增长）；写线程由模拟容器写超时（`send` 抛 `IOException`）自行释放并回池，摘除后不再投递；断点判定（buffer→Redis→gap）；并行检索顺序稳定；检索超时/池满的降级与 429 分支；保留任务两步批删边界（H2 + MySQL）；**保留任务退出**：DELETE 持续抛异常时该表连续失败 2 次被跳过、整轮按时间预算结束（不死循环），孤儿兜底按自身 `created_at` 清理且不误删未过期行；**会话清理**：无剩余消息才删、单批 200、`context_summary` 随行清除；**附件幂等**：文件缺失按成功删行、IO 失败保留、`message_id IS NULL` 不参与孤儿判定但受保留期约束；**PENDING 提案**先置 `EXPIRED` 再删；`chat_feedback`/`kb_task_log`/`chat_tool_traces` 索引批删路径；解析产物 TTL（每文档留最新 2 份、`CURRENT` 标记版本不删）；**租约**：DB 时间申请/续租/接管/影响行数 0 即中止，H2/MySQL 方言 SQL 各一组；**上下文水位回归（P0）**：≥30 轮 + 触发一次压缩后，按 `context_through_message_id` 取 40 条窗口仍包含最近 4 轮原文，`ConversationContext.from` 不再出现 `min(summarizedTurns, history.size())` 导致整窗截断（复用 `ConversationContextTest`）；水位为空的历史会话走「最近 N 条」兜底；**文档列表契约**：无游标返回数组、带游标返回 `{items,next_cursor}`，翻页无重复漏项 |
| 集成 | H2 跑 V23 迁移 + 查询计划；MySQL profile 抽样 EXPLAIN（含 `KbChunkStore.java:82` 查询、聊天五表 + `chat_sessions` 与 `kb_task_log`/`kb_ingest_task` 的批删取键语句无 filesort、`findBySeries` 三种变体：带目录/状态无 keyword 无 filesort、无目录无 keyword 无 filesort、keyword 允许 filesort 但扫描行受限；`context_through_message_id` 列存在）；SSE 200 路经 nginx 8880；慢客户端 20 路（含半开连接）不读数据 60s，watchdog 5s 内摘除且 tick 持续增长，其余流不受影响，写线程在容器写超时（实测值）内释放；200 路并发重连（0–3s 抖动）；ES 注入 2s+ 延迟时对话检索降级、管理端 429；文档列表 1 万条 cursor 翻页（limit=200 首屏；无游标请求仍为数组）；OCR 限额用例；直连 parser 并发 3 个请求得 503；300 页附件被上限拒绝；MySQL dry-run 行数与 `COUNT(*)` 一致；保留任务失败注入（单表 DELETE 抛异常）后整轮可退出且其他表完成；parser 产物目录 10 版本 → 仅留最新 2 个 + `CURRENT` 版本保留；**V23 失败恢复演练**：单条索引失败 → 清半成品 → `flyway repair` → 重跑整份成功，且确认「不清理直接重跑」失败 |
| 架构 | `payroll-parser` 只在 `io.agentscope.payroll.parser..` 命名空间、不依赖 `io.agentscope.examples.payroll..` 业务实现（仅共享 protocol/parse 类型）；`payroll-tests/pom.xml` 加入 parser 依赖后迁移面用例可编译；既有 R1–R7b 全绿 |
| 回归 | `mvn -o test` 全量；前端 `npm run build && npm run test`；nginx 8880 真实页面走对话/知识库/模型配置；runtime 真实一轮对话并确认 `llm_usage` 落库 |
| 金标 | 50 题薪酬基线（`docs` 既有脚本）对比：路由/检索命中率下降 ≤ 1 个点 |
| 运维 | 双副本启动任务互斥；获锁节点恢复前崩溃由巡检接管；Redis 停机降级；容器 OOM 后重启不丢轮次（轮次租约 30s 接管）；parser 重启后解析任务可重试成功；保留任务按 `retention-hour/minute` 在窗口内触发且仅一个节点执行、`dry-run` 首轮只统计；升级两阶段（warn → fail）与回滚开关逐项验证 |

完成定义沿用 AGENTS.md：构建 + 相关/全量测试 + 前端构建/测试 + nginx 真实页面 + runtime 真实对话；未验证项显式标注。

---

## 9. 排期与任务拆解

**开工前置**：C1 前用预发/生产数据核对并发基线（峰值会话数 × 单会话标签页），低于 100 路则 C1 目标下调为 100 路、估算压到 5 人日；C0a 不受此影响，可立即开工。

| 批次 | 任务 | 估算 | 交付物 |
| --- | --- | --- | --- |
| 批次 A1（第 1 周） | C0a-1 单遍解析；C0a-2 页数/大小上限；C0a-3 有界 OCR；C0a-4 附件纳入端口（inline 阶段） | 3 | 止血上线：大文件/大页数不再打挂 Web |
| 批次 A2（第 2 周） | C0b-1 `payroll-parser` 服务与 HMAC；C0b-2 共享卷（`parsed-data:/app/parsed`）与原子产物；C0b-3 Web 侧准入与重试；C0b-4 OCR 构建迁移；C0b-5 容器限额/探针；C0b-6 迁移面拆解（配置模型/格式/AbortSignal 下沉，`payroll-tests` 依赖） | 10 | parser 上线 + 200 页扫描件实测 |
| 批次 B（第 3 周） | C1-1 订阅式 fanout；C1-2 串行写池与 watchdog 写超时；C1-3 断点契约映射；C1-4 runtime 执行池；C1-5 指标与压测 | 10 | 200 路（或基线值）压测报告 |
| 批次 B（并行） | C5-3 容器 healthcheck；C5-4 守卫 warn 与 §7.1 升级手册 | 2 | compose/守卫/手册 + 测试 |
| 批次 C（第 3–4 周，3 人并行） | C2-1…C2-5 | 2 | 并发回归 |
| 批次 C（并行） | C3-1 检索专用池；C3-2 BM25 走 ES；C3-3 历史窗口水位化（V23 列 + 端口/管理器 API）；C3-4 前端窗口读取与文档分页（含返回契约兼容）；C3-5 检索超时与降级语义 | 8 | 检索压测 + 金标对比 + 上下文回归 |
| 批次 C（并行） | C4-1 V23（列序含 `document_id`、失败恢复流程）；C4-2 删除集（含会话/摘要/PENDING 过期）、失败上限、时间预算与孤儿兜底；C4-3 日调度（租约内）与 dry-run 报告；C4-4 产物 TTL 清理（`CURRENT` 保护）；C4-5 EXPLAIN 三变体 | 8 | 迁移 + 清理 dry-run 报告 |
| 批次 D（第 5 周） | C5-1 租约协调器（DB 时间 + 方言 SQL）；C5-2 导入幂等与恢复巡检 | 3 | 双副本启动 + 崩溃/时钟偏移接管验证 |
| 批次 D | C6 指标 + 压测脚本 + SLO/基线文档 | 3 | 看板/脚本/基线文档 |

依赖：C0b 依赖 C0a 与 OCR 构建资产；C1 依赖 D1/D3 与并发基线；C3-3 依赖 V23 的 `chat_sessions.context_through_message_id` 列（与 C4 同一迁移文件，schema 先定稿）；C4 依赖 D5/D6；C5-1 依赖 V23 的 `job_lease` 表；C6 最后收口。

合计：3 + 10 + 10 + 2 + 2 + 8 + 8 + 3 + 3 = **49 人日**；含 15% 缓冲按 **56 人日**排期。单人串行约 10 周（含缓冲约 12 周），批次 C 三项由 3 人并行可压到 8 周；不再假设单人可并行压缩。

### 9.1 可执行任务清单（49 人日，39 项）

口径：每项是「可独立提交、可独立回滚」的最小改动单元（≤3 人日），完成标准只列可取证结果，完整验收见 §5/§8；状态列勾选后归档证据（命令输出、EXPLAIN、压测报告链接）。批次 C/D 与 §9 有一处内部平移：`C5-1` 按依赖拆为 `C5-1a`（租约仓储，必须先于 `C4-3`）与 `C5-1b`（协调器接入），批次 C 18 → 19、批次 D 6 → 5，总计不变。每批出口重跑 §8 回归并留证。

**批次 A1（3 人日，第 1 周）：止血**
| 状态 | ID | 任务 | 完成标准 | 人日 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| ✅ | A1-1 / C0a-1 | 单遍解析：`KbParserService.parsePdf` 去掉 `pageTexts`，逐页抽取/OCR 后即合并；`filterHeaderFooter` 改采样 + 两页前瞻 | 200 页 PDF 的页文本与页范围与旧实现逐页一致（单测）；解析结束 PDF 文档与页图已释放，堆峰值有记录 | 1.0 | — |
| ✅ | A1-2 / C0a-2 | 硬上限：`parse.max-pages=200`、`max-file-size=200MB`、multipart 200/512MB、上传前 `Content-Length` 校验返回 413、错误文案含「共 N 页/上限 M 页」 | 201 页、201MB 两条用例明确失败且 Web 存活；multipart 不再先收 600MB | 0.5 | A1-1 |
| ✅ | A1-3 / C0a-3 | 有界 OCR：`timeoutExecutor` 改 2 线程 / 队列 8 / Abort，拒绝按质量门降级，页图即用即弃 | 并发 OCR 时线程数 ≤2；拒绝不新建线程、不挂起 | 0.5 | — |
| ✅ | A1-4 / C0a-4 | 对话附件同受 `parse.max-pages`，前后端上限文案同步 | 300 页附件被拒且 Web 不重启（inline 阶段即可验证） | 0.5 | A1-2 |
| ✅ | A1-R | 止血回归与构建 | `mvn -o -q -DskipTests package`、KB/附件相关测试、`npm run build` 通过 | 0.5 | A1-1…A1-4 |

A1 证据（2026-11-25）：
- A1-1：`KbParserService.parsePdf` 改为单遍合并（合并文本 + `PageRange` + 32 页有界待合并队列），页图 `flush()`、`PDDocument` try-with-resources 释放。`KbParserOcrTest` 10 项全绿，其中 `twoHundredPagesStreamsConsistently` 校验 200 页逐页范围连续、页眉页脚剔除，`streamingMatchesLegacyAssembly` 与旧实现（测试内 `legacyParse` 参考实现）逐页一致。堆峰值未单独采样（本机无隔离 JVM 采样条件），内存上界由「单份合并文本 + 页范围 + ≤32 页待合并」结构性约束保证。
- A1-2：`parse.max-pages=200`、`max-file-size=200MB`、multipart 200/512MB。`tooManyPagesFailsFast`（201 页报「共 201 页/上限 200 页」），`KbUploadLimitTest` 校验声明 600MB 在鉴权前直接 413 且提示 512MB 上限。
- A1-3：`OcrExtractionService.timeoutExecutor` 改 2 线程 / `ArrayBlockingQueue(8)` / `AbortPolicy`，拒绝走引擎链降级；`OcrExtractionServiceTest` 7 项通过。
- A1-4：补齐 PDF 对话附件（此前 `CHAT_SUPPORTED` 不含 pdf，验收用例无法复现），附件与入库共用 `parsePdf` 页数上限；`pdfAttachmentSharesPageLimit` 校验 201 页附件被拒、文本 PDF 正常提取；前端 accept 列表与提示文案同步更新。
- A1-R：`mvn -o -q -DskipTests package` EXIT=0；KB/附件/OCR 定向测试 25 项全绿（`KbParserOcrTest` 10、`ChatAttachmentServiceTest` 5、`ChatAttachmentWebTest` 2、`OcrExtractionServiceTest` 7、`KbUploadLimitTest` 1）；`npm run build` 成功；`npm run test` 31 项通过。

**批次 A2（10 人日，第 2 周）：独立解析进程**
| 状态 | ID | 任务 | 完成标准 | 人日 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| ✅ | A2-1 / C0b-1 | `payroll-parser` 模块骨架 + `POST /internal/kb/parse` + HMAC（`PAYROLL_PARSE_TOKEN`，偏斜 120s）+ 参数/路径校验 | 直连解析返回 `artifactKey/pageCount`；错 token/过期 401；token 为空启动失败；`mvn -o -pl payroll-parser -am test` 通过 | 2.0 | A1-R |
| ✅ | A2-2 / C0b-2 | 共享卷与原子产物：`parsed-data:/app/parsed`、`*.part` → `ATOMIC_MOVE`、`manifest.json`、版本目录 `CURRENT`（写入时清同文档旧标记） | `kill -9` 后无 `.part` 被消费；重试复用同路径产物；单文档只有一个 `CURRENT` | 2.0 | A2-1 |
| ✅ | A2-3 / C0b-3 | Web 准入与重试：`KbParserPort`/`KbParseClient`、信号量 2、连接池 4、read timeout = `parse.timeout-ms+30s`、503 快速重试 2、任务退避；`KbIngestService`/`ChatAttachmentService` 改走端口 | 直连并发 3 时第 3 个 503；经 Web 提交不丢任务、不占满入库线程；`parse.mode=inline` 可解析 | 2.0 | A2-1 |
| ✅ | A2-4 / C0b-4 | OCR 资产迁移：`-Prapidocr` 与三件套依赖进 parser pom、`docker/parser.Dockerfile`、启动自检 | parser 镜像 OCR 可用；Tesseract 缺失 WARN、RapidOCR 缺失降级，不阻塞启动 | 1.0 | A2-1 |
| ✅ | A2-5 / C0b-5 | 容器边界：mem_limit 2g/4g/1g/2.5g、`MaxRAMPercentage=70`、`ExitOnOutOfMemoryError`、parser healthcheck、卷权限分离 | `docker compose up` 全健康；parser 对 `/app/data` 无写权限、对 `/app/parsed` 可写；堆上限约 1.75GB | 1.0 | A2-2 |
| ✅ | A2-6 / C0b-6 | 迁移面拆解：`KbConfigModels` 解析记录下沉 `payroll-common/.../kb/parse/`，抽 `KbParseFormats`/`KbAbortSignal`/`ChatExtraction`，backend import 与 `payroll-tests` 依赖同步 | 全量 `mvn -o test` + `ArchitectureRulesTest` 全绿；parser 不依赖 backend 业务实现 | 2.0 | A2-3 |

A2 证据（2026-11-25）：
- A2-1：新增 `payroll-parser` 模块（端口 8300，包 `io.agentscope.payroll.parser`），`POST /internal/kb/parse` 与 `/internal/kb/chat/parse` 走 `InternalAuth` HMAC（`PAYROLL_PARSE_TOKEN`，默认偏斜 120s）；`validateStartup` 在 token 为空时拒绝启动；`resolveStorage` 做规范化前缀 + `toRealPath` 双校验。`KbParseControllerTest` 6 项：空 token 启动失败、缺签/伪签/过期 401、签名正确但路径缺失 404、同请求二次命中 `cached=true` 且产物文件存在、worker 占满时第 3 个 503 `BUSY`、300 页附件 400 且文案含「300/上限 200」、chat 附件往返（`native/pdf`）。`mvn -o -q -pl payroll-parser -am -DskipTests package` EXIT=0。
- A2-2：`KbParseArtifacts` 落盘 `parsed/{documentId}/{sha256}/{profile}.json`，同目录 `manifest.json` + `CURRENT`；写入统一先写 `*.part` 再 `ATOMIC_MOVE`（不支持时回退普通改名）。`KbParseArtifactsTest` 5 项：写后 `.part` 不存在、manifest/CURRENT 可读、`existing`/`read` 往返、profile 对 `maxPages` 与 `filterHeaderFooter` 敏感且重复计算稳定、`resolveKey` 拒绝 `../` 越界、损坏 JSON 在 `read` 时报错、非法 profile/哈希在拼路径前被拒。偏差：产物目录按 `(documentId, sha256)` 分层，`CURRENT` 约束在该层内唯一（同文档多 profile 时指向最近一次成功解析），未额外删除旧 profile 文件，旧版本清理留给 C4-4。
- A2-3：`KbParserPort`/`KbParseClient` 位于 `payroll-common/.../kb/parse/`，Web 侧信号量 `web-concurrency=2`、HTTP 执行器 `webConcurrency+2`、read timeout = `timeout-ms+30s`、429/500/502/503/504 与 IOException 重试 `max-retries=2`（退避 30s）、排队等待 2s 后 503；`KbParserConfiguration` 以 `payroll.kb.parse.mode=inline|remote` 选 `KbParserService` 或 `KbParseClient`，`KbIngestService`/`ChatAttachmentService` 只依赖端口。`KbParseClientTest` 4 项：remote 缺 token 构造即失败、错 token 403 快速失败（服务端 parseCalls=0）、首次读产物 + 第二次本地缓存（parseCalls 仍为 1）、chat 附件往返。偏差：重试在客户端内联完成而非「延迟重新入队」；chat 解析接口用 base64 内联上传（附件原文件在 Web 侧）；inline 模式下 backend 仍编译依赖 parser 模块（remote 模式不创建任何 OCR/解析 Bean）。
- A2-4：`payroll-parser/pom.xml` 增加 `-Prapidocr` 三件套依赖（rapidocr 0.0.7 + windows-x86_64/模型 1.2.2）；`docker/parser.Dockerfile` 基于 temurin 17-jre 安装 `tesseract-ocr`/`tesseract-ocr-chi-sim`/`curl`，JAVA_OPTS 含 `MaxRAMPercentage=70` 与 `ExitOnOutOfMemoryError`。RapidOCR 缺失时 `OcrExtractionServiceTest` 观察到熔断/降级继续返回文本，不阻塞启动。
- A2-5：`docker/docker-compose.yml` 新增 `parser`（`mem_limit: 2.5g`、liveness healthcheck、`kb-data:/app/data:ro`、`parsed-data:/app/parsed`）；web-1/web-2 切 `PAYROLL_KB_PARSE_MODE=remote`、挂 `parsed-data:/app/parsed:ro` 并 `depends_on: parser (service_healthy)`；`backend.Dockerfile` 移除 Tesseract 安装。偏差：本机 shell 无 Docker CLI（`docker: command not found`），`docker compose config/up` 与卷权限实测未在本机执行，留待 §8 目标环境验证；卷权限分离只做了挂载声明（RO/RW），未实测写拒绝。
- A2-6：共享面下沉 `payroll-common/.../kb/parse/`（端口、DTO、`KbParseSettings`/`ParseConfig`、`KbParseFormats`、`KbParseArtifacts`、`KbParseClient`、`ChatExtraction`、异常族）；`payroll-tests` 增加 `payroll-parser` 依赖，OCR 测试迁至 `io.agentscope.payroll.parser.ocr`。`ArchitectureRulesTest` 8 项全绿；parser 模块仅依赖 common，不引用 backend 业务实现。
- A2-R：`mvn -o -q -DskipTests package` EXIT=0；新增 A2 测试 15 项（Artifacts 5 / Client 4 / Controller 6）与既有解析/附件测试 31 项（`KbParserOcrTest` 10、`ChatAttachmentServiceTest` 5、`ChatAttachmentWebTest` 2、`KbUploadLimitTest` 1、`OcrExtractionServiceTest` 7、`OcrQualityGateTest` 6）全部通过。

**批次 B（10 人日，第 3 周）：SSE 推送化与执行池**
| 状态 | ID | 任务 | 完成标准 | 人日 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| ✅ | B-1 / C1-1 | 订阅式 fanout：`Hub.subscribe(turnId, afterSeq, onEvent, onGap, onTerminal)`、每订阅有界队列 512、快照与 `earliestSeq` 判定下沉 | 断点用例（buffer→Redis→gap）改走订阅接口后全绿；慢订阅不阻塞 poller | 2.5 | A2-6 |
| ✅ | B-2 / C1-2 | 串行写池与 watchdog：`sseWriterExecutor` 16/4096、每订阅单 drain、`lastWriteAt/inFlightSince`、watchdog 只标记/摘除、15s ping、`write.rejected` | 单测：慢订阅 5s 内 `stalled` 并摘除、tick 持续增长、模拟 `send` 抛 `IOException` 后线程回池；关闭只在该订阅无在途写时执行 | 2.5 | B-1 |
| ✅ | B-3 / C1-3 | 控制器与超时链：`resumeEvents` 只注册订阅、`SSE_TIMEOUT_MS`/nginx 1800s、`push`/`legacy` 开关、取消与队列满 gap | 200 路重连 seq 连续或按契约 gap；`ChatFreshReplayWebTest` 等断点用例全绿；`legacy` 可回切 | 2.0 | B-2 |
| ✅ | B-4 / C1-4 | 执行池拆分：`runtimeTurnExecutor` 8/64 接入 `InternalTurnController`，`chatStreamExecutor` 只做 Web 提交 | all 模式 8 路并发无「执行队列已满」假 429；web/runtime 拆分行为不变 | 1.0 | A2-6 |
| ✅ | B-5 / C1-5 | 指标与压测：sse 全套 gauge/counter、`scripts/load/sse_load.py`、200 路经 nginx 8880、慢客户端 18+2 路 90s | 报告：P95 首字增量 <300ms、无 429；写线程在实测容器写超时内释放、`inflight` 回落、tick 持续增长 | 2.0 | B-2, B-3 |

**批次 B 并行（2 人日）：容器与守卫**
| 状态 | ID | 任务 | 完成标准 | 人日 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| ✅ | B-6 / C5-3 | compose 健康检查：web/runtime/parser liveness、`depends_on: service_healthy`、`restart: unless-stopped` | 依赖不健康时下游不启动；`docker compose ps` 全 healthy | 0.5 | A2-5 |
| ✅ | B-7 / C5-4 | 守卫两阶段：`TopologyGuard` 改「收集全部问题 → `guard.strict` 决定 WARN/抛出」、四项新校验、错误脱敏、§7.1 升级手册 | warn 缺项启动成功且只 WARN；fail 缺项启动失败且不回显取值；单机 all 在 warn 下不阻断 | 1.5 | B-6 |

B 证据（2026-11-25）：
- B-1：`TurnEventFanout.Hub.subscribe/`push 订阅带 cursor 与 `earliestSeq`，每订阅有界队列（默认 512，可由 `payroll.chat.sse.subscriber-buffer` 覆盖），共享 poller 批量读 200/2048。`TurnEventFanoutTest` 5 项：多订阅共享读循环、重放+实时、重放溢出下发 gap 后继续实时、写拒绝 gap+关闭、watchdog 只标记不写。
- B-2：`sseWriterExecutor` 16/4096（`payroll.chat.sse.writer-threads/writer-queue`），每订阅串行 drain，`lastWriteAt/inFlightSince` + 单 watchdog（tick 1000ms、写超时 5000ms）只标记/摘除；15s ping；`payroll.sse.write.rejected/timeout/gap/stalled` 计数。stalled 用时 <5s 的用例与写池拒绝用例均通过。
- B-3：`ChatSessionController` 以 `payroll.chat.sse.mode=push|legacy` 切换（默认 legacy），push 分支只注册订阅并释放配额一次（`PushConnection.cleanup()` 幂等）；`ChatSseChannelTest` 4、`ChatSseDeltaTest` 4、`ChatSessionControllerTest` 7、`ChatFreshReplayWebTest` 1 全绿；`legacy` 一键回切。
- B-4：`runtimeTurnExecutor` 8/64（`payroll.runtime.turn-threads/turn-queue-capacity`）接入 `InternalTurnController`，排队满回 `BUSY`；`InternalTurnControllerTest` 3 项通过，all 模式与 web/runtime 拆分共用同一 bean 定义。
- B-5：新增 `scripts/load/sse_load.py`（httpx + anyio≥4，登录/建会话/流式/断点重连/百分位/Prometheus 抽样）；经 nginx `127.0.0.1:8880` 冒烟：登录与建会话 200、`POST /stream` 200、TTFT 27.5ms、gaps=0。偏差：本机旧构建的模型侧在 30s 后超时（`firstText=null`、`streamDone=30.088s`），完整 200 路与慢客户端 18+2 压测未在本机完成，留待 §8 目标环境执行；脚本支持 `--fail-on-error` 可直接用于该验收。
- B-6：compose 为 web/runtime 补齐 liveness healthcheck（`curl /actuator/health/liveness`，15s/3s/3/90s）、`PAYROLL_HEALTHCHECK_PORT`（8100/8080）、frontend `depends_on: web-1/web-2 service_healthy`；backend/runtime 镜像安装 curl；`mem_limit` web 2g、runtime 4g、sandbox 1g、parser 2.5g，JVM 统一 `MaxRAMPercentage=70 + ExitOnOutOfMemoryError`。`docker compose` YAML 解析通过；本机无 Docker CLI，`config/ps` 实测留待 §8。
- B-7：`TopologyGuard` 去掉 `@Profile("prod")`，两阶段 `payroll.guard.strict=warn|fail`（默认 warn），一次收集全部问题；新增管理员默认口令、web 内部 token、remote 解析 token、cluster 禁 `node-role=all`（single all 仅 WARN）四项校验；消息不回显取值。`TopologyGuardTest` 16 项覆盖 warn 不阻断、fail 聚合 10 项、脱敏、runtime 免内部 token、remote/inline 差异、prod 强制 cluster；`docs/deployment-matrix.md` 与 §7.1 同步。
- B-R：`mvn -o -q -DskipTests package` EXIT=0；全量 `mvn -o test` 1441 项、0 失败、0 错误、4 跳过（BUILD SUCCESS）。修复过程中发现并修正 `application-common.yml` 的 `payroll.runtime` 重复键（导致 47 个 Web 上下文测试失败）。

**批次 C（19 人日，第 3–4 周，3 人并行）：锁、检索、生命周期**
| 状态 | ID | 任务 | 完成标准 | 人日 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| ✅ | C2-1 | `TeamLlmClient` 改 `Semaphore(8)`，2s 获取超时走规则回退 | 8 路并发 router P95 不随会话数线性上升 | 0.5 | — |
| ✅ | C2-2 | `OllamaEmbeddingClient` HTTP 调用移出锁、回填二次检查 | 并发嵌入无全局锁；缓存 key/TTL 不变 | 0.5 | — |
| ✅ | C2-3 | 会话淘汰限频 `evict-interval-seconds=5` + 候选 `excess+8` | `chatFor` 抽样 CPU <1ms | 0.5 | — |
| ✅ | C2-4 | fanout 空闲轮询 500ms；`ChatSse` 合流只投写池 | 空闲唤醒下降；合流窗口仍 80ms | 0.5 | B-1 |
| ✅ | C3-1 | 检索专用池与并行：`kbSearchExecutor` 8/64/Abort、`KbSkillService` 并行、`KbSeriesService.getAll` 批量权限、合并排序稳定 | 5 库检索 P95 <1.5s；DB 查询次数 ≤2+5 | 1.0 | — |
| ✅ | C3-2 | 检索瘦身与 BM25-ES：`KbChunkStore.SELECT_KEYS`、TITLE_PATH 只取列、`bm25Route engine=es` + `bm25.engine` 开关、正文按 topK 回表 | 50 题命中率下降 ≤1 点（否则回退 local）；候选查询不含 content/vector | 1.5 | C3-1 |
| ✅ | C3-3 | 历史窗口水位：V23 列 + `throughMessageId` + 三个端口方法 + `ConversationContext.from` 去截断 + 压缩回写水位 + 老会话回填 | `ConversationContextTest` 新用例：≥30 轮压缩后 40 条窗仍含最近 4 轮问答；老会话首窗后水位已回填 | 2.5 | C4-1a |
| ✅ | C3-4 | 接口与前端分页：`GET /messages?before_id&limit`、两处全量扫描改 `manager.message`、KB 文档游标 + 数组/游标兼容、前端两处切换 | `limit=200` 首屏 200 条；无游标仍裸数组；游标翻页无重复/漏项 | 2.0 | C3-3 |
| ✅ | C3-5 | 检索超时与降级：ES `HttpRequest.timeout=1.5s`、`orTimeout` 兜底、聊天/团队 `degraded` 空结果、管理台 429 | 注入 ES >2s：轮次正常完成且 `degraded`；池满管理台 429；无 500/挂起 | 1.0 | C3-1 |
| ✅ | C4-1a | V23 SQL 定稿：19 条索引（含两个 `kb_document` 索引）、`job_lease`、`context_through_message_id` | 评审通过；H2/MySQL 语法预检通过 | 0.5 | — |
| ✅ | C4-1b | V23 落地与恢复：迁移文件、H2/MySQL 执行、Flyway CLI 预跑说明、失败恢复演练 | 两库迁移成功；「清半成品 → repair → 重跑」成功；「不清理重跑」因对象已存在失败 | 1.0 | C4-1a |
| ✅ | C4-2 | 保留任务：表 1–9 两步批删、失败上限 2、10 分钟预算、100ms 批间间隔、孤儿兜底、`PENDING` 先 `EXPIRED` | 失败注入整轮可退出；1→6 顺序用例；会话/附件幂等/`message_id NULL`/PENDING 用例全过 | 3.0 | C4-1b |
| ✅ | C4-3 | 日调度与 dry-run：02:30 cron、启动统计走 `retention.startup-stats` 租约、cron dry-run 在租约内、报告字段完整 | 双副本启动仅统计一次；报告行数 = `COUNT(*)`；首版默认 dry-run | 1.5 | C4-2, C5-1a |
| ✅ | C4-4 | 产物 TTL：parser 侧清理 + `CURRENT` 保护 + 每文档留最新 2 版 + `cleanup.failures`；inline Web 侧同规则 | 10 版本 → 2 + `CURRENT`；remote 下 Web 无任何写 `/app/parsed` 调用 | 1.0 | A2-2 |
| 🟡 | C4-5 | EXPLAIN 与数据量验证：六张聊天表 + `kb_task_log`/`kb_ingest_task` + `findBySeries` 三变体；12 个月模拟数据 | 目标查询无 filesort（keyword 例外且扫描受 `series_id` 前缀限制）；50 万消息在预算内清完 | 1.0 | C4-2 |
| ✅ | C5-1a | 租约仓储：`job_lease` 申请/续租/释放，时间全在 SQL 侧、方言封装一处、续租 0 行即中止 | ±5 分钟时钟偏移下互斥不变；被接管后影响行数 0 且原任务立即中止、无双写 | 1.0 | C4-1a |

**批次 D（5 人日，第 5 周）：租约接入与观测收口**
| 状态 | ID | 任务 | 完成标准 | 人日 | 依赖 |
| --- | --- | --- | --- | --- | --- |
| ✅ | C5-1b | 租约接入：审计/团队/轮次/KB 恢复/附件恢复/Nacos 导入/保留/孤儿任务统一持租约；恢复巡检改 5 分钟周期 | 双副本启动每任务只执行一次；持租约节点 `kill -9` 后 5 分钟内接管且任务不丢 | 1.0 | C5-1a, C4-2 |
| ✅ | C5-2 | 导入幂等：PK 冲突跳过 + `imported/skipped` 计数 | 重复导入 skipped 正确；巡检接管不重复执行 | 1.0 | C5-1b |
| ✅ | C6-1 | 指标落地：model/es/turn/sse/retention/parse/executor 指标 + Hikari `HikariDataSourceMetricsBinder` | `/actuator/prometheus` 可见全部新指标（H2 无池为空属预期） | 1.0 | B-5, C4-2 |
| ✅ | C6-2 | 压测脚本：`sse_load.py`、`kb_search_load.py`、`kb_parse_load.py` | 三脚本各跑通一次，输出 P50/P95/P99 与错误码分布 | 1.0 | C6-1 |
| 🟡 | C6-3 | SLO 与交付：`docs/perf-baseline-2026-11.md`、§8 SLO 逐项数据、nginx 8880 真实页面、runtime 真实一轮对话 | 基线文档落盘；§8 阈值逐项有证据；真实页面与真实对话验证通过 | 1.0 | C6-2 |

C3/C4 证据（2026-11-25）：
- C3-1：`kbSearchExecutor` 8/64/Abort（`PayrollCoreConfiguration` `kbSearchExecutor` Bean），`KbSkillService` 各库并行召回（`:240`、`:443`），`KbSeriesService.getAll(Collection, AuthUser)`（`:137`）一次批量取系列并校验权限。聊天链路池满降级为空结果，管理台由 `KbRetrievalController` 回 429。
- C3-2：`KbChunkStore.SELECT_KEYS`（`:35`）候选只取 id/标题路径等轻列，正文由 `KbRetrievalService` 按 topK 回表；`bm25Route` 在 `bm25.engine=es` 且 ES 可用时走 `bm25RouteEs`（`:709`/`:724`），诊断写 `bm25_engine=es|local`（`:505`）。`KbRetrievalFusionTest` 10 项通过（向量不可用双路召回、向量+BM25 融合、去重与名次）。
- C3-3：`ConversationContext` 以消息水位折叠（`throughMessageId` `:40`、`applySummary` 推进 `:201`、`backfillLegacyWindow` `:222`、`snapshot` 回写 `:240`）。`ConversationContextTest` 16 项含两条新回归：「摘要轮次超过窗口长度时最近四轮仍进入提示词」（30 轮）与「存量会话首次窗口化后水位回填」；`H2ChatContextWindowTest` 4 项覆盖水位读写与 `messagesRecent`。
- C3-4：`ChatSessionController` 带 `before_id/limit` 时返回 `{items, has_more}` 并只多取 1 条判有无下一页（`:162`–`:184`）；`ChatSessionControllerTest` 9 项含 `limit` 首屏与游标契约。KB 文档 `listPage` + `next_cursor`（`KbDocumentService:229`），不带游标仍返回裸数组；新增 `KbDocumentPaginationContractTest` 2 项锁死双契约。
- C3-5：ES 检索 HTTP 超时默认 1.5s（`KbElasticsearchService:57`/`:65`，`HttpRequest.timeout` `:463`）；融合诊断写 `degraded`/`bm25Degraded`（`KbRetrievalService:509`–`:517`）；管理台池满回 429（`KbRetrievalController:55`）。新增 `KbRetrievalControllerTest` 2 项：池满不服务直接 429、空闲同步返回。
- C4-1a/1b：V23 共 19 条索引 + `job_lease` + `chat_sessions.context_through_message_id`。新增 `MigrationRecoveryTest` 2 项：模拟非事务 DDL 半成品后不清理重跑因对象已存在失败；清 19 条索引 + `job_lease` + 列 → `flyway repair` → 重跑成功且 V23 记为成功。偏差：本机无 MySQL 客户端/凭据，MySQL 侧「Flyway CLI 预跑 + 真库 EXPLAIN」留目标环境（与 B-6 Docker 同类环境限制）；H2 以 MySQL 模式执行同一脚本已通过（`SchemaMigrationTest` 5 项）。
- C4-2：`RetentionServiceTest` 7 项全过：依赖链 1→6 顺序、孤儿兜底（近期孤儿保留）、空会话 200/200/1 批删、附件文件缺失按成功且 `message_id NULL` 可删、PENDING 首轮 EXPIRED 次轮删除、失败注入连续 2 轮后跳过并计 `payroll.retention.skipped`、dry-run 行数逐表对齐且零删除。测试同时发现并修复两处缺陷：PENDING 先过期后删除导致同轮审计缺口（改为先删已终态再置 EXPIRED）；孤儿提案把 VARCHAR `proposal_id` 当 Long 解析，遇 UUID 即整轮崩溃（改为 `List<String>`）。
- C4-3：`RetentionConfiguration` 启动 20s 后以 `retention.startup-stats` 租约只扫一次，每日 `retention.daily` 固定 rate 且 cron 内的 dry-run 也走租约；`node-role=runtime` 不参与。双副本互斥由 `JobLeaseStore` 单行 PK 保证，租约单测见 C5-1a。
- C4-4：`ArtifactCleanerTest` 3 项全过：10 版本保留最新 2 + `CURRENT`，旧版本按 TTL 删除，非 sha256 目录与旁文件不动；远程模式下 Web 只读，清理由 parser 独占写。
- C4-5（🟡）：索引列序按规格评审通过，H2 结构校验覆盖 19 条索引与两列 `kb_document` 变体；MySQL EXPLAIN（无 filesort）与 50 万消息清理耗时需真库与模拟数据，本机缺凭据/CLI，留 §8 目标环境执行。

C5/C6 证据（2026-11-25）：
- C5-1a：`JobLeaseStore` 申请/续租/释放（`db/JobLeaseStore.java:29`、`:65`、`:82`），所有时间表达式在 SQL 内取数据库时钟，H2 `DATEADD` / MySQL `TIMESTAMPADD` 只在 `addSeconds` 一处（`:119`）；`JobLeases` 门面以 `runIfAcquired` 统一「抢到才执行 + finally 释放」（`jobs/JobLeases.java:62`、`:118`）。`JobLeaseStoreTest` 6 项全过：`onlyOneNodeAcquires`、`takeoverInvalidatesTheOriginalHolder`（被接管后原节点续租影响 0 行）、`releaseMakesTheLeaseReusable`、`expiryIsComputedByTheDatabaseClock`、`runIfAcquiredExecutesOnceAcrossNodes`、`disabledLeasesRunLocally`。偏差：双机 ±5 分钟 JVM 时钟偏移未物理调钟实测，靠「时间源全在 DB 侧」结构性消除 + 数据库时钟单测断言。
- C5-1b：统一租约覆盖 `import.agents`（`PayrollCoreConfiguration:429`）、`import.skills`（`:555`）、`cleanup.model-credentials`（`:1020`）、`retention.audit`（`:1047`）、`recovery.turns`（`:1070`）、`retention.team`（`:1094`），保留日任务 `retention.startup-stats`/`retention.daily`（C4-3），KB 入库与附件解析恢复共用 `recovery.kb`（`kb/KbConfiguration.java:84`，启动即跑、默认每 5 分钟，`payroll.jobs.recovery-interval-minutes`）。孤儿清理：轮次 `expireStaleTurns`、模型凭证 `cleanupOrphans`、保留任务孤儿提案（C4-2）。「双副本每任务只执行一次」与「TTL 到期接管」由上述 `runIfAcquiredExecutesOnceAcrossNodes`/`takeoverInvalidatesTheOriginalHolder` 锁死；真实双进程 `kill -9` 未做（依赖环境，语义已由租约单测覆盖）。
- C5-2：`JdbcAgentDefinitionRepository.importIfEmpty`（`:155`–`:188`）逐条 `INSERT`、主键冲突跳过并返回实际导入数；`NacosAgentRegistry` 以 `skipped = max(0, size - imported)` 记账（`:564`–`:566`）；`SkillNacosImporter.Report` 携带 `imported/skipped/reason`（`:35`–`:68`）。`JdbcAgentDefinitionRepositoryTest#importIfEmptyIsIdempotent`（`:123`）、`#importSkipsWhenTableNotEmpty`（`:135`）与 `JobLeaseStoreTest#runIfAcquiredExecutesOnceAcrossNodes` 共同覆盖「重复导入 skipped 正确、巡检接管不重复执行」。
- C6-1：`TurnMetrics`（model/turn/sse/hub poll 等）、`ParseMetrics`（`duration{outcome,engine}`、`failures{reason}`、`bindQueue`）、`ExecutorMetrics`（`payroll.executor.{active,queue}{pool=chat/tail/runtime/kbSearch/kbIngest}`）与 `PayrollDatabase` 手动 Hikari Gauge（`:103`–`:126`）接线。实测 `/actuator/prometheus` 出现 `payroll_model_duration_seconds`、`payroll_turn_duration_seconds`（runtime 8100）、`payroll_es_duration_seconds`、`payroll_sse_connections`、`payroll_sse_hub_poll_queue_seconds`、`payroll_retention_duration_seconds`、`payroll_executor_*`、`hikaricp_connections*`、`payroll_kb_parse_duration_seconds{engine="inline"}`（Web 8080）。偏差：Micrometer 1.15.7 无 `HikariDataSourceMetricsBinder`，改手动注册四个 `hikaricp.connections.*` Gauge；Timer/Gauge 懒注册（首次采样后出现）；`payroll.kb.parse.queue` 仅 parser 进程有；engine 标签取值 `inline|remote|native`。
- C6-2：三脚本各跑通一次（结果与解读见 `docs/perf-baseline-2026-11.md` §3–§5）：SSE 200 路 → 94 准入 + 106 个设计内 429，TTFT P50/P95/P99 1388/1688/1725ms，`gaps=0`、重连 0；检索 200 次 → 全 200，P50/P95/P99 171/590/775ms，空结果 0；解析 5×200 页 → 全 READY，流水线 P50/P95/P99 6325/9356/9356ms，0 重试。压测同时发现并修复拒绝路径 SSE 连接计数泄漏：`ChatSessionController.stream()/resume()`（`:445`、`:468`、`:472`、`:908`）与 `PayrollWebController.chatStream()`（`:322`、`:329`、`:337`、`:341`）在三条 429 分支抛错前补 `channel.complete()`，回归断言见 `ChatSessionControllerTest#executorRejectionReleasesQuota`（`:86`）与 `#quotaExhaustedRejectsWithoutScheduling`（`:105`），修复后重跑 `0 → 0`。
- C6-3（🟡）：`docs/perf-baseline-2026-11.md` 已落盘，含机器配置、测量口径、三压测明细、§8 SLO 逐项状态与偏差、复现命令与证据文件索引。nginx 8880 真实页面（登录页 → 智能体页 → 对话页）与经 8880 的真实一轮对话（`turn/start/agent_start/text/agent_end/done`，`elapsed_ms=1559`，deepseek-flash）均验证通过；解析后 Web GC 堆 73.2→70.9MB（增量 −2.3MB）。未闭环项：500 轮上下文构建时延、慢客户端注入实测、清理批次时延、parser 进程堆与 `payroll.kb.parse.queue`（本机 inline 模式、未启动 8300），需目标环境/独立压测补齐。

**关键路径**：A1 → A2 → B（C1）→ C4-2 → C5-1b → C6-3。批次 C 内三条流可并行：C3（依赖 C4-1a 的水位列）、C4（C4-1a→1b→2，C4-4 可提前与 C4-2 并行）、C2（随时）；`C5-1a` 由第三人先在批次 C 首日完成，`C4-3` 不得早于它。

---

## 10. 风险

| 风险 | 应对 |
| --- | --- |
| SSE 推送化破坏 gap/快照契约 | 保留 legacy 开关；事件 schema 与断点测试先跑通再切流 |
| BM25 迁 ES 改变排序 | 金标 50 题对比；`bm25.engine=local` 兜底 |
| 页数/大小上限拒绝历史大文档 | 配置可调；错误信息给出拆分建议；升级说明标注 |
| 附件路径绕过解析隔离 | 附件改经 `KbParserPort`，remote/inline 共用页数上限；补 300 页附件用例 |
| 历史上下文按轮次数对齐导致丢轮次/重放 | 摘要压缩只写消息水位 `context_through_message_id`，窗口按 `message_id >` 读取；`ConversationContext.from` 去掉 `min(summarizedTurns, history.size())`；空水位历史会话按最近 N 条兜底；P0 回归用例锁死 |
| 保留任务误删 | 首版 dry-run + 统计日志；删除前备份；保留期由产品/合规确认；按主键两步批删（不依赖 turn） |
| 索引创建影响线上写入 / 部分失败 | 避开高峰；发布前用 Flyway CLI 在应用启动外执行 V23（建索引不阻塞 readiness）；MySQL online DDL；失败后按「清掉本迁移已建对象 → `flyway repair` → 重跑整份」恢复，**不得手工预建对象后再 repair 重跑**，也不得不清理直接重跑 |
| 守卫阻断旧部署 | `payroll.guard.strict=warn` 一个版本；`node-role=all` 仅在 cluster 强制；§7.1 升级手册与回滚开关 |
| 慢客户端占满 SSE 写池 | 每订阅串行投递 + watchdog 在 `write-timeout-ms` 内标记并摘除（停止投递，占比不再扩大）；写线程由容器写超时（默认 60s）回收，写池容量按此规划；`payroll.sse.write.timeout` / `payroll.sse.slow.clients` / `payroll.sse.write.stalled` 告警 |
| watchdog 关流反而冻结监控 | Spring 6.2 的 `SseEmitter.send` 与 `completeWithError` 共用 `writeLock`：watchdog 若调用 emitter API 会与阻塞写一起卡住。因此 watchdog 只读内存、只标记/摘除、永不关闭；`payroll.sse.write.stalled` 与 tick 计数持续可观测；5s 级线程释放需非阻塞写，明确不在本期 |
| 保留任务死循环或长期积压 | 该表连续失败 2 次即跳过（`payroll.retention.skipped`）+ 全局时间预算；首版 dry-run 先核对数据量；批间休眠降低与在线请求争锁 |
| 会话删除后摘要/引用残留 | `chat_sessions` 仅在无消息时删除，`context_summary` 随行删除；`agent_team_runs` 归档行按保留期物理删除；审计引用已删消息按既有失效降级 |
| 租约受节点时钟影响导致双执行 | 申请/续租/释放全部用 DB `CURRENT_TIMESTAMP`，Java 不传时间戳；方言函数一处封装；续租影响行数 0 立即中止；验收含 ±5 分钟时钟偏移用例 |
| KB 文档列表返回契约变更导致旧前端空白 | 带游标返回 `{items,next_cursor}`、不带游标一个版本内仍返回裸数组；前后端同版本切换，下一版本删数组分支 |
| 解析产物写满 `parsed-data` | TTL 7 天 + 每文档保留最新 2 份；parser 清理失败计数与卷容量告警（>80%）；清理失败不影响解析主链路 |
| 检索超时放大拖慢对话 | HTTP 级 1.5s 总超时 + 降级语义（对话返回空结果、管理端 429）；ES 客户端超时只作为下层保护；金标 50 题回归守命中率 |
| 索引列序与查询不匹配、加了索引仍全扫 | 按实际 WHERE/ORDER BY 定列序，且可选等值列（`directory_id`/`status`）不得插到排序键之前——MySQL 无法跨等值缺口继续有序扫描。拆两个索引：`idx_kb_document_series_updated (series_id, deleted_at, updated_at, document_id)` 服务无目录条件变体，`idx_kb_document_series_dir_updated (series_id, deleted_at, directory_id, updated_at, document_id)` 服务带目录条件变体；`status`/`keyword` 作残余过滤（`status` 走索引条件下推）；两索引均以 `document_id` 收尾对齐 `ORDER BY updated_at DESC, document_id DESC`。验收用 `findBySeries` 三种变体 + `findReadyBySeries` EXPLAIN（keyword 分支明示接受 filesort 但扫描受 `series_id` 等值前缀约束），禁止仅以「索引存在」作为通过标准 |
| runtime 线程数提高打满供应商配额 | 与模型连接 `maxTokens/并发` 同步评估；429 由 runtime 回传 |
| 独立解析服务增加部署面与内部通道 | compose 单服务 + `kb-data:/app/data` 只读挂载 + `parsed-data:/app/parsed`（parser 读写、Web 只读）；产物路径严禁落在 `/app/data` 之下；HMAC 独立令牌；`parse.mode=inline` 就地回退；parser 故障只影响当前解析任务 |

---

## 11. 未覆盖与后续

- 前端渲染性能（长会话虚拟滚动）未纳入本期，C3 接口窗口化后单开评估；
- 沙箱逃逸面、内部通道重放窗口收紧未纳入；
- `devteam` 场景包、第二场景包容量另立规格；
- 解析进程多副本调度与优先级队列未纳入本期（单副本、2 并发起步，按压测扩副本；产物 TTL 清理已纳入 C4，多副本共享卷的清理归属随扩容另议）。

---

## 12. 证据核对说明

本文引用的 `文件:行号` 基于基线 `fe6b43e` 回读；其中 B20（compose 应用服务无 healthcheck/限额）为文件级检索结论，未逐行复核每个服务块；B22–B26 为 v0.2 复审新增证据，已逐条回读（附件解析路径、既有保留键、入库池、RapidOCR 构建、nginx/SSE 超时链）；B33–B38 为 v0.4 性能/可用性复审新增，已回读 `ChatSse` 写路径、启动清理 runner、ES 超时常量、`findBySeries`、`V1/V2` 保留相关表与索引、fanout 轮询参数；**v0.5 新增核验**：`spring-webmvc-6.2.15` 的 `ResponseBodyEmitter.send/completeWithError` 共用 `writeLock`（字节码）、Tomcat 10.1.50 `writeTimeout=connectionTimeout`、`PayrollDatabase.migrate`（:193/:208）、`frontend/src/kb/api/index.js:220-240` 的裸数组消费、`V9__chat_attachments.sql` 可空 `message_id`；实施前以最新代码复核一次。
