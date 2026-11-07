# 会话记忆向量库 Spec（v2，方案已确认）

> 状态：已实现（P1–P3）
> 决策：Elasticsearch 7.17.28 + nomic-embed-text（768 维）+ 只索引问题与摘要 + 当前用户全部历史

## 1. 目标

1. 把**压缩摘要**与**用户问题**写入 Elasticsearch 向量索引，支持按语义检索历史会话。
2. 让智能体在长会话或新会话中召回相关历史线索，用于解析指代（“他”“上次那个部门”“之前问过的政策”）。
3. 向量库只做**索引与召回**，不作为事实源；金额、税额、考勤、政策仍必须重新调用工具获取。

## 2. 非目标与红线

- 不跨用户检索：所有查询强制带 `user_id` 过滤，跨租户召回视为 P0。
- 不把召回内容当作金额来源：注入前再做一次金额/比例/卡号清洗。
- 不替换 H2：`chat_messages` 与 `chat_sessions` 仍是事实源，ES 索引可随时重建。
- **不索引助手答复**：只索引用户问题与压缩摘要，避免历史金额进入向量库。
- 不阻塞对话：ES 或 Ollama 嵌入不可用时，聊天照常完成。

## 3. 选型结论

| 候选 | 结论 | 理由 |
| --- | --- | --- |
| **Elasticsearch 7.17.28** | **采用** | 本机已在 `127.0.0.1:9200` 运行且集群 green；支持 `dense_vector` + `user_id` 过滤；后续可加 BM25 做混合检索；Spring Boot 侧接入成本最低 |
| Milvus 2.4 | 不采用 | 本机没有 Docker，19530 端口未监听；需要 etcd/MinIO 等组件，单机部署成本高 |
| Lucene 嵌入式 | 备选 | 若后续要求去中间件化，可用同一 `VectorStore` 端口替换 |

**ES 7.17 的能力边界（必须知悉）**：

- 7.17 的 `dense_vector` 用 `script_score` + `cosineSimilarity` 做**精确检索**，不是 HNSW 近似检索。
- 按 `user_id` 过滤后单用户语料通常只有数千条，精确检索耗时在毫秒级，满足当前规模。
- 若未来需要 ANN，升级到 ES 8.x 后改用 `knn` 检索即可，索引结构不变。
- Elasticsearch 使用 Elastic License 2.0/SSPL，若对外分发产品需评估许可；内部管理台使用无影响。

## 4. 总体架构

```
对话完成（答复已落 H2 chat_messages）
  └─ 异步索引队列（单线程，容量 512）
       ├─ QUESTION：用户问题 → 脱敏 → EmbeddingClient
       └─ SUMMARY：压缩摘要 → EmbeddingClient
            └─ Elasticsearch: payroll-chat-memory（_doc id 幂等）

本轮提问
  └─ 同步检索（超时 3s，失败降级为空）
       ├─ EmbeddingClient → 当前问题向量
       ├─ ES script_score：cosineSimilarity + user_id 过滤，topK=5
       └─ 注入【相关历史片段｜仅作线索】
```

## 5. Elasticsearch 索引设计

索引名：`payroll-chat-memory`（可通过配置覆盖）。

```json
{
  "mappings": {
    "properties": {
      "vector":      { "type": "dense_vector", "dims": 768, "index": true },
      "user_id":     { "type": "long" },
      "session_id":  { "type": "long" },
      "source_type": { "type": "keyword" },
      "source_id":   { "type": "long" },
      "turn_index":  { "type": "integer" },
      "content":     { "type": "text" },
      "created_at":  { "type": "date" }
    }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `vector` | nomic-embed-text 的 768 维向量 |
| `user_id` | 强制过滤字段，租户隔离 |
| `session_id` | 会话级删除 / 摘要替换 |
| `source_type` | `QUESTION` / `SUMMARY` |
| `source_id` | 问题时对应 `chat_messages.id`；摘要为空 |
| `turn_index` | 会话内轮次，用于幂等 |
| `content` | 已脱敏文本，长度上限 2000 字符 |
| `created_at` | 写入时间 |

幂等文档 ID：

```
sha256(userId + ":" + sessionId + ":" + sourceType + ":" + turnIndex + ":" + content)
```

同一内容重复写入会覆盖同一 `_doc`，不会产生重复记录。

## 6. 写入流程

触发时机：本轮答复写入 `chat_messages` 之后，提交异步任务。

1. `QUESTION`：用户原始问题 → `SensitiveTextSanitizer.redact` → 长度 ≥ 8 才入索引。
2. `SUMMARY`：压缩成功并落库后，摘要本身就是脱敏产物 → 入索引。
3. **助手答复不入索引。**

> 实现说明：索引阶段采用 `redact`（金额替换为 `[金额已省略]`、保留整句），
> 避免“月薪30000元上个月工资多少”这类单句问题被整句丢弃；摘要仍用 `sanitize` 整句剔除。
> 召回内容注入前再执行一次 `sanitize`，确保最终提示词不含金额、比例与证件信息。

摘要替换：

- 先 `delete_by_query` 删除 `session_id + source_type=SUMMARY` 的旧文档，再写入新摘要，保证一个会话只有一份有效摘要。
- 删除或写入失败只记 `[向量]` 警告，不影响 H2 中已保存的摘要。

提交与重试：

- 异步单线程队列，容量 512，满时丢最旧任务并告警。
- 单条失败重试 1 次；仍失败则丢弃，等待重建。
- ES 刷新策略：写入后不强制 `refresh`；检索允许最多 1 秒的可见延迟。

## 7. 检索与注入

触发时机：每轮在组装计算智能体输入之前。

1. 当前问题 → 查询向量（超时 3 秒，失败返回空）。
2. ES 查询：

```json
{
  "size": 5,
  "query": {
    "script_score": {
      "query": { "term": { "user_id": 1 } },
      "script": {
        "source": "cosineSimilarity(params.q, 'vector') + 1.0",
        "params": { "q": "<query_vector>" }
      }
    }
  }
}
```

3. 分数换算回余弦相似度，低于 `0.55` 的命中丢弃。
4. 拼接注入块，总字符上限 1200：

```
【相关历史片段｜仅作线索，不是数据来源】
- [2026-10-12] 用户询问过张伟 2026-09 的工资构成
- [2026-10-15] 用户关注过研发部的批量核算
片段中的任何金额、税额、考勤、政策都必须重新调用工具获取，禁止直接引用。
```

5. 本轮无命中或检索失败：不加任何块，对话照常进行。
6. 检索耗时目标 p95 ≤ 300ms。

## 8. 多租户与安全

- `VectorStore.search(userId, queryVector, topK)` 的签名强制携带 `userId`，从类型层面防止漏过滤。
- ES 默认无认证（本机）；生产环境通过 `PAYROLL_MEMORY_ES_USERNAME/PASSWORD` 注入。
- 索引内容不含金额、比例、银行卡号、身份证号，统一由 `ConversationContext.sanitize` 保证。
- 日志只打印 `user_id`、`session_id`、命中数和分数，不打印正文。

## 9. 生命周期

| 事件 | 行为 |
| --- | --- |
| 会话删除 | `delete_by_query(user_id, session_id)` |
| 会话重置 | 删除该会话 `SUMMARY` 文档；历史问题向量保留（属于跨会话记忆） |
| 应用启动 | 探测 ES 与嵌入模型；任一不可用则关闭向量能力并打印明确提示 |
| 索引缺失 | 自动创建索引与 mapping |
| 全量重建 | `POST /api/admin/vector/rebuild`（ADMIN）：清空索引后按 `chat_messages` + 摘要重灌 |

## 10. 配置

```yaml
payroll:
  memory:
    enabled: false                       # 灰度开关，默认关闭
    es-url: "${PAYROLL_MEMORY_ES_URL:http://localhost:9200}"
    es-username: "${PAYROLL_MEMORY_ES_USERNAME:}"
    es-password: "${PAYROLL_MEMORY_ES_PASSWORD:}"
    index: "payroll-chat-memory"
    embedding-base-url: "${OLLAMA_BASE_URL:http://localhost:11434}"
    embedding-model: "nomic-embed-text"
    embedding-dim: 768
    embed-timeout: 3s
    top-k: 5
    min-score: 0.55
    max-inject-chars: 1200
    async-index: true
```

前置条件：

```bash
ollama pull nomic-embed-text    # 本机已拉取（274MB）
```

## 11. 降级与故障处理

| 故障 | 表现 | 处理 |
| --- | --- | --- |
| ES 不可用 | 连接拒绝/超时 | 启动时禁用向量能力；运行中检索返回空、索引任务丢弃并告警 |
| 嵌入模型缺失 | Ollama 404 | 启动探测失败，禁用向量能力并提示 `ollama pull` |
| 嵌入超时 | > 3s | 本轮不注入历史；索引任务丢弃 |
| ES 写入失败 | 4xx/5xx | 记录 `[向量]` 错误，不重试超过 1 次 |
| 索引 mapping 冲突 | 创建失败 | 打印索引名与期望 dims，要求人工处理或重建 |
| 队列满 | 丢弃最旧 | 计数告警，不阻塞对话 |

## 12. 测试与验收

单元测试（不依赖 ES/Ollama）：

1. 文档 ID 幂等：同轮同内容生成相同 ID。
2. 租户隔离：检索请求必须包含 `user_id` 过滤（断言请求体）。
3. 脱敏：索引内容与注入块不含金额/百分比/卡号。
4. 降级：嵌入或 ES 抛错时，`augment` 返回原文，对话正常。
5. 摘要替换：二次压缩触发先删后写。
6. 既有 52 个测试全部通过。

集成与实测（手动，需本机 ES + nomic-embed-text）：

7. 跨会话提问能召回相关历史，人工核对 5 组用例。
8. 检索 p95 ≤ 300ms，对话总耗时增幅 ≤ 5%。
9. 删除会话后，ES 中该会话文档数为 0。

## 13. 交付分期

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P1 | `VectorStore` 端口 + ES 实现 + `EmbeddingClient` + 索引初始化 + 假实现单测 | 开关关闭时行为与现在完全一致 |
| P2 | 异步写入问题/摘要 + 会话删除/重置联动 + 重建接口 | 索引幂等、可重建、删除干净 |
| P3 | 检索注入 + 提示词守卫 + 真实模型联调 | 召回有效、无金额泄漏、延迟达标 |

## 14. 风险

- **ES 7.17 精确检索**：单用户语料上万条后查询会变慢；届时升级 ES 8.x 使用 ANN。
- **双写一致性**：H2 成功、ES 失败会出现短暂不可召回，以 H2 为准可重建。
- **nomic 中文效果**：弱于 bge-m3；若实测召回质量不足，可仅替换 `embedding-model` 与 `dim` 并重建。
- **集群共用**：本机 ES 集群名 `kb-console-test`，可能有其他项目共用；使用独立索引名，避免写入他人索引。
- **磁盘占用**：768 维 float 约 3 KB/条，加索引开销约 5 KB/条。

## 15. 已确认决策

| 项 | 决策 |
| --- | --- |
| 存储引擎 | Elasticsearch 7.17.28（Milvus 因无 Docker 不采用） |
| 嵌入模型 | nomic-embed-text，768 维 |
| 索引内容 | 只索引用户问题与压缩摘要，助手答复不入索引 |
| 检索范围 | 当前用户全部历史，严格按 `user_id` 隔离 |

## 16. 实现与实测记录（2026-11-07）

实现位置：

| 类 | 职责 |
| --- | --- |
| `memory/VectorMemoryService` | 启动探测、异步索引队列（容量 512、丢最旧）、召回渲染、重建、会话生命周期 |
| `memory/ElasticsearchVectorStore` | 索引初始化、幂等写入、`script_score` 检索、按会话删除 |
| `memory/OllamaEmbeddingClient` | `/api/embed` 调用、维度校验、模型探测 |
| `memory/SensitiveTextSanitizer` | `sanitize`（整句剔除，摘要用）与 `redact`（占位替换，索引用） |
| `service/PayrollChatService` | 每轮召回并注入；压缩成功后索引新摘要 |
| `service/ChatSessionManager` | 问题落库后索引；删除/重置联动向量库；管理员重建 |
| `web/AdminController` | `POST /api/admin/vector/rebuild`（`ROLE_MANAGE`） |

依赖与配置注意：

- `co.elastic.clients:elasticsearch-java:7.17.28` 必须与 `elasticsearch-rest-client:7.17.28` 同版本；
  Spring Boot BOM 默认管理 8.x 传输层，已显式锁定 7.17.28。
- 已排除 `ElasticsearchClientAutoConfiguration`，ES 客户端由 `PayrollConfiguration` 手动创建，
  避免应用启动时无条件连接 ES。
- 启动探测失败（ES 不可达 / 模型未拉取）时只告警并禁用向量能力，聊天不受影响。

本机实测（ES 7.17.28 + nomic-embed-text + qwen2.5:3b-instruct）：

- 单元测试：64 个测试全部通过（含幂等 ID、脱敏、降级、摘要替换、重建、租户参数）。
- 启动：`[向量] 已启用：index=payroll-chat-memory，model=nomic-embed-text，dim=768`，索引自动创建。
- 召回：同一用户跨会话命中 1–3 条，耗时 238–291ms（含嵌入），满足 p95 ≤ 300ms。
- 租户隔离：同一问题 `user_id=2` 检索命中 0 条。
- 写入：问题落库后异步写入，`source_type=QUESTION`、`turn_index` 正确；台账可查。
- 删除：删除会话后该会话向量数 1 → 0。
- 重建：4 条历史输入写入 3 条，剩余 1 条为纯金额问题，脱敏后为空被跳过。
- 注入：日志 `[向量] 已注入历史线索：81 字`，随后三阶段对话正常完成。
