# RAG 准确率整改规格（v0.2）

- 状态：**草案，未改码**。P0 可开工（从 P0-0 起）；P1 起待 D1–D12 拍板
- 日期：2026-11-26（v0.1 初稿 → v0.2 按产品/架构评审重写，变更见附录 D）
- 关联：`docs/kb-routing-policy-spec.md`、`docs/kb-retrieval-as-context-spec.md`、`docs/citation-traceability-prd.md`、`docs/kb-chat-integration-design.md`、`docs/perf-baseline-2026-11.md`
- 来源：外部《企业级 RAG 系统准确率提升全景指南》（Word，5 页，2026-11-26 收到）。本规格不是该文档的转述，而是逐条裁定后的项目化整改方案。
- 证据等级：**[读码]** 2026-11-26 本机代码核实；**[实测]** 引用既有报告数据；**[外部]** 业界资料，未在本机复现；**[假设]** 待 P0 基线证伪/证实。正文只写 [读码] 与已引用的事实，运行期行为一律标 [假设] 并进 P0-0 验证清单。
- 硬约束：
  1. 薪酬核算链路的判定、路由、工具集、缓存行为一行不变（沿用路由规格的硬约束）。
  2. 库级权限（READER/EDITOR/ADMIN/OWNER）仍是唯一收窄来源，检索不越权。
  3. 不新增 Python 运行时依赖（是否例外见 D3）。
  4. 改分块策略必须走新的配置版本，不得就地重切已有库，除非 D9 批准。
  5. 本规格不覆盖合规与安全（PII、越权问答、审计），另立 PRD。

## 一页摘要

**给产品**：三个高频场景（制度直问、时效口径、边界拒答）决定用户是否信任知识库。技术指标不等于业务正确率，因此设一个北极星——**月度 30 条真实问题人工抽检的正确率**，技术指标（Hit@5/MRR@5 等）只是它的代理。P0 先花 11.5 人日把基线立起来并修掉两个"改了配置却没用"的缺陷，之后每项优化都必须用同一把尺子证明净收益。

**给架构**：三个实质缺陷（阈值在默认融合路径完全不生效、rerank 名不副实、融合分批次归一化导致 top1 恒为 1.0）本质是同一件事——**排序分与"够不够格作答"没有分离**。合并为一个「检索置信度模型」任务修复，而不是分散打补丁。评测必须分三层：CI 跑降级路径（fake store + HASH 向量）只能验证结构，不能验证质量，把两者混在一层会得出不可迁移的结论。

**明确不做**：换 Embedding 模型、RAGAS/Python 评测服务、Graph RAG、查询重写默认开启、LLM-as-Judge 自动打分。

## 一、产品目标

### 1.1 三个核心场景

| 场景 | 典型问法 | 用户期望 | 当前风险 | 对应缺陷 |
| --- | --- | --- | --- | --- |
| S1 制度直问 | 「年假天数按工龄怎么算」 | 一段话 + 可点开的原文 | 同义改写（年休假/带薪年假）漏召 | F4 |
| S2 时效口径 | 「2025 年专项附加扣除标准是多少」 | 明确区分新旧政策，标生效日期 | 新旧政策混检，答成旧标准 | F5 |
| S3 边界拒答 | 「Kafka 分区 ISR 收缩策略」「这首诗第几句提到 Kafka」 | 明确说"知识库里没有"，不硬编 | 低分噪声块被当成证据，生成错误答案 | F1/F3 |

S3 是当前最高危场景：错的自信答案比"不知道"伤害更大，且已有实证（会话 26 的诗词误引 Kafka，见 `docs/kb-routing-policy-spec.md` 背景段）。

### 1.2 北极星与护栏

| 项 | 定义 | 现状 | 目标 |
| --- | --- | --- | --- |
| 北极星 | 每月从真实日志抽 30 条 KB 问答，人工判「正确 / 部分正确 / 错误 / 该拒未拒」 | 未度量 | P0-5 出首份，之后月度跟踪，错误率逐月下降 |
| 护栏 G-1 | 薪酬链路回归金标 100% 一致 | [实测] 现有回归全绿 | 任何改动后保持 100% |
| 护栏 G-2 | 错误自信率（no_answer 类给出了答案的比例）不上升 | 未度量 | 打开置信度后下降；若上升则回滚 |
| 护栏 G-3 | 检索 P95 不显著恶化 | [假设] 未测 | ≤ 基线 + 50ms，且绝对值 ≤ 800ms（ES 本机） |

### 1.3 为什么量化口径要写死

60 条用例时，**单条 = 1.7pp**；no_answer 若只有 10 条，**单条 = 10pp**。这意味着"AbstainAccuracy 从 0.90 提到 0.95"在 10 条样本上只是多对 1 条，毫无统计意义。因此：no_answer 类扩到 15 条（单条 6.7pp，目标 0.87 ≈ 允许 2 条误答）；60 条规模**只做描述统计，不做显著性检验**；要谈"提升 X%"需 ≥200 条，属 P1-4 之后的事。

## 二、现状基线（[读码]，2026-11-26）

### 2.1 三个实质缺陷（P0）

| 编号 | 缺陷 | 证据 | 用户可见后果 |
| --- | --- | --- | --- |
| F1 | **融合路径不应用 `similarityThreshold`**：`fuse()` 形参收了 threshold 但函数体从未引用；全文件唯一过滤在 `legacy()` 内 | `KbRetrievalService.java:344-353`（签名）、`:336`（唯一使用点）、`:188`（fusion 默认开） | 默认配置下前端改"相似度阈值"**完全不生效** [读码] |
| F2 | **`rerank` 名不副实**：`rerankModel=local-hash-rerank` 无实现引用；实际是"查询词二元组覆盖率"线性加权 `min(1, 0.7×融合分 + 0.3×覆盖率 + 0.05×标题命中)`，非 Cross-Encoder | `KbConfigModels.java:68-69`、`KbRetrievalService.java:998-1026` | 语义相近但字面不同的命中被系统性压低；配置项误导运维 [读码] |
| F3 | **融合分按批次最大值归一化**，top1 恒为 1.0 | `KbRetrievalService.java:1283-1284`、`:488-491` | 绝对相关性不可用；"一池子烂结果"时仍显示满分 [读码] |

F1 与 F3 必须同一个任务修：F1 想用阈值截断，但 F3 让分数没有绝对语义——先接回阈值也只会得到一个无法解释的截断。合并方案见 §3.2。

### 2.2 其余差距

| 编号 | 差距 | 级别 | 证据 | 影响 |
| --- | --- | --- | --- | --- |
| F4 | BM25 只有 `standard + cjk_bigram`，无同义词/岗位薪级术语 | P1 | `KbElasticsearchService.java:411-428` | 同义提问召回不稳 |
| F5 | 无时效字段：`Document` 只有 `createdAt/updatedAt`，ES mapping 无 `effectiveFrom/To` | P1 | `KbModels.java:83-105`、`KbElasticsearchService.java:429-450` | 新旧政策混检 |
| F6 | 阈值一刀切，不分题型 | P1 | `KbConfigModels.java:73` | 松了误召、紧了漏召 |
| F7 | 无检索质量金标：只有路由金标 150 条 | P0（度量） | `payroll-tests/src/test/resources/routing/`、`scripts/route-eval.mjs` | 一切调参盲调 |
| F8 | ES 向量检索用 `script_score` 全量打分，非 kNN（mapping 已 `index:true`） | P2 | `KbElasticsearchService.java:289-314`、`:445` | 分块数上升后 P95 线性恶化 [假设，待压测] |
| F9 | `Chunk.metadata` 存在但未进 ES mapping | P2 | `KbModels.java:123`、`KbElasticsearchService.java:432-446` | 预留字段空转 |

### 2.3 已具备（外部指南主张但无需再做）

| 能力 | 现状 | 证据 |
| --- | --- | --- |
| 混合检索 | VECTOR / BM25 / TITLE_PATH 三路，可配开关 | `KbRetrievalSettings.java:15-22,38-49` |
| RRF 融合 | `weight/(k+rank)` 累加，k=60，权重 1.0/0.8/0.5 | `KbRetrievalService.java:1271-1272` |
| 父子索引 | 父 1024 / 子 256 / 子重叠 32 / 每父最多 8 子 | `KbChunkingService.java:61-64,176-200`、`KbConfigModels.java:44-50` |
| 父块回填 | 命中子块按 `parentChunkId` 回填，上限 1200 字符 | `KbRetrievalService.java:41-42,1028-1034` |
| 相邻合并/去重/单文档限流 | 字面重合 >0.6 合并（单条最多并 3 块）；指纹去重；单文档最多 3 块 | `KbRetrievalService.java:41,856-935` |
| 主题门禁 | 查询点名专有名词但语料无此词 → 返回空 | `KbRetrievalService.java:388-407` |
| ES 降级 | 进程内字面召回兜底 | `KbRetrievalService.java:332-335` |
| 引用溯源 | `[n] 文档名` + 父块内容 | `KbAnswerService.java:142` |
| 库级权限 | 用户成员 ∪ 部门成员取较高角色 | `KbPermissionService.java:33-68` |
| Embedding 降级 | Ollama `nomic-embed-text`(768d) 800ms 预算，超时落 HASH 并标 `vector_source` | `KbEmbeddingService.java:26-27,73-89` |

> 口径更正（沿用 v0.1）：路由层语义门槛 0.50（`kb-routing-policy-spec` §6.1）与库内检索阈值 `similarityThreshold` 0.30（`KbConfigModels.java:73`）是两个指标，本规格只治理后者。

## 三、目标架构

### 3.1 改动边界

```
问题 ──▶ L0 路由（不动，薪酬链路完全不进本规格）
             │
             ▼
      KbRetrievalService
        ├─ 召回  VECTOR / BM25 / TITLE_PATH   ← 产出原始分 r_v / r_b / r_t；P1: 词典(F4)、时效过滤(F5)、kNN(F8)
        ├─ 融合  RRF(k=60)                     ← 只决定排序；不动
        ├─ 重排  rerank                        ← 只决定排序；P0-4 正名，P1-6 评估真模型
        ├─ 准入  relevance → τ(category)       ← 新增层；只决定"答或拒答"，不改排序（F1+F3）
        └─ 回填  parent / adjacent             ← 不动
             │
             ▼
      KbAnswerService（引用）                   ← 不动
             │
             ▼
      前端：abstained / lowConfidence 渲染      ← P1-5
```

核心设计原则：**排序（ranking）与准入（admission）解耦**。RRF 与 rerank 只为"谁排前面"负责；是否需要拒答只看绝对相关性。当前 F1 想用阈值做截断、F3 让分数丧失绝对语义，两者纠缠的根因就是这两件事没分开。

### 3.2 检索置信度模型（P0-3，修复 F1+F3）

**三个概念分离：**

| 概念 | 含义 | 用途 | 取值 |
| --- | --- | --- | --- |
| `score`（融合分） | 批次内相对排序质量 | 仅排序与展示 | [0,1]，top1 恒 1.0（保留现状语义，不再对外当"相关度"用） |
| `relevance`（绝对相关性） | 单条命中"与问题有多相关"的绝对读数 | 准入与拒答 | [0,1]，与批次无关 |
| `confidence`（置信度） | 校准后"可以据此作答"的概率 | 前端提示、监控 | [0,1]；P0 直接等于 relevance，P1-4 再校准 |

**relevance 计算：**

```
r_v = max(0, cosine)                              // 向量路；负余弦截零
r_b = bm25Raw / (bm25Raw + c)                     // BM25 饱和，c 为语料标定常数，首版 c=1.0
r_t = 1 if 查询主词命中 titlePath else 0           // 标题路
relevance = min(1, max(r_v, r_b, r_t))
```

取 `max` 而非加权和，理由：
- 薪资域大量精确查询（「生育津贴 158 天」）只在 BM25 强、向量一般；加权和会把强单路压到阈值下，制造 FalseAbstain。
- 准入判据要的是"最好证据强度"，不是"综合排序质量"——后者已由 RRF 负责。
- 多路互证的加成（如"两路都 ≥0.5 则 +0.1"）留到 P1-4 用 ≥200 条数据决定是否加，不先验设定。

已知弱点（必须在 P0 报告里写明）：`max` 会被单路偶然高分触发（如 BM25 因高频词虚高），因此 τ 必须用 no_answer 类实测标定，不能拍脑袋。

**两个阈值，语义拆开：**

| 配置 | 现状 | v0.2 语义 | 默认 | 作用点 |
| --- | --- | --- | --- | --- |
| `similarityThreshold` | 0.30，融合路径实际不生效 | **召回下限**：低于此值的向量命中不进入融合（成本与噪声控制） | 0.30 不变 | 召回后、融合前；F1 修复 = 融合路径也必须应用 |
| `confidence.abstainThreshold`（新增） | 无 | **拒答阈值**：`max(relevance) < τ` → 拒答 | 0.45 [假设] | 重排后、组装上下文前 |

分题型 τ（F6）在 P1-4 落地：`τ(category)` 按 direct / synonym / numeric / temporal 分别标定，no_answer 类不设（它本应被拒）。UI 上原"相似度阈值"文案必须同步改写，否则运维仍会误以为它是拒答阈值（D7）。

**契约变更（前后端同版本发布，不做双轨）：**

| 字段 | 现状 | v0.2 | 说明 |
| --- | --- | --- | --- |
| `score` | 批次归一化融合分 | 保留，语义明确为"排序分" | 前端不再当百分比展示 |
| `relevance` | 无 | 新增，绝对相关性 | 用于准入与展示 |
| `confidence` | 无 | 新增，校准置信（P0 = relevance） | P1-4 校准后启用 |
| `lowConfidence` | 无 | 新增，`relevance < τ` | 有结果但依据弱 |
| `abstained` | 无 | 新增，无结果或低置信 | 前端据此渲染拒答态 |
| `diagnostics.routes` | 有 | 保持，另加 `abstainReason` | 归因用 |

**校准路线**：P0 不做校准（样本不够，强行校准等于过拟合 60 条）；P1-4 在 ≥200 条后做 isotonic 回归 `confidence = f(relevance, category)`，要求单调不减；τ 用 T2 网格搜索，硬约束 `FalseAnswerRate ≤ 0.10`。

### 3.3 评测三层（P0 主体）

| 层 | 名称 | 环境 | 依赖 | 是否阻断 | 回答什么问题 |
| --- | --- | --- | --- | --- | --- |
| T1 | 结构回归 | CI | fake store + HASH 向量，无 ES/Ollama | **是**（D4 确认后） | 阈值是否生效、拒答标志是否正确、父块回填/去重/单文档限流是否被破坏 |
| T2 | 质量评测 | 本机/夜间 | 真实 ES + Ollama + 60 条金标 | 否，只报（P1 后期再议） | Hit@5/MRR@5/nDCG@5/拒答率是否真的变化 |
| T3 | 人工抽检 | 月度 | 真实日志 30 条 + 人工判读 | 否 | 业务上到底对不对（北极星） |

**为什么不能只做一层 JUnit**：CI 里 Ollama 不可用，`KbEmbeddingService` 会落 HASH 向量（`KbEmbeddingService.java:73-89`）；fake store 也没有真实 BM25。在这种环境下算出的 Hit@5 测的是降级路径，不能代表线上质量，拿它做上线决策会误导。反之，T2 依赖外部服务，不适合放 CI 卡合并。所以结构用 T1 卡、质量用 T2 报、业务用 T3 盯。

T1 复用现有形态：`KbRetrievalFusionTest.java:45-56` 证明 fake-store 直接构造 service 可行；开关沿用 `@Tag("eval")` + 系统属性（`KbRoutingEvalTest.java:28-45`）。

### 3.4 低置信 UX（P1-5）

| 状态 | 前端表现 | 后端字段 |
| --- | --- | --- |
| 拒答（无命中） | 「知识库中没有找到相关依据」，不生成推断答案 | `abstained=true, hits=[]` |
| 拒答（低置信） | 同上，附「可展开查看相近片段（最多 3 条，已标低置信）」 | `abstained=true, lowConfidenceHits=[...]` |
| 有结果但依据弱 | 正常引用，顶部标「依据较弱，建议核对原文」 | `lowConfidence=true` |
| 正常 | 现状渲染 | 现状 |

原则：拒答时**禁止**让模型基于低分片段自由发挥；相近片段只作为"供用户自行判断"的补充，不进推理上下文。该行为进 T3 抽检口径（区分"该拒未拒"与"不该拒却拒"）。

## 四、指标体系与金标

### 4.1 指标定义

全部确定性计算，不调模型。`gold` 为人工标注的相关 chunk 集合。

| 指标 | 定义 | P0 目标 [假设] | 用途 |
| --- | --- | --- | --- |
| Hit@5 | top5 含任一 gold 的用例比例 | ≥ 0.85 | 召回底线 |
| MRR@5 | 首个 gold 排名倒数均值（未命中计 0） | ≥ 0.70 | 首位质量 |
| nDCG@5 | 二值增益 DCG@5 / IDCG@5 | ≥ 0.75 | 排序质量 |
| AbstainAccuracy | no_answer 类返回空的比例 | ≥ 0.87 | 拒答能力（15 条，允许 2 错） |
| FalseAnswerRate | no_answer 类给出了答案的比例（= 1 − AbstainAccuracy） | ≤ 0.10 | 护栏 G-2 |
| FalseAbstainRate | 可答类返回空的比例 | ≤ 0.10 | 过度拒答 |
| ContextPrecision@5 | `Σ_{k≤5}(precision@k × rel_k) / |gold∩top5|`，RAGAS 口径 | ≥ 0.60 | 上下文噪声 |
| P95 检索耗时 | 召回→重排→准入全链路 | ≤ 800ms（ES） | 护栏 G-3 |

原 v0.1 的 `CitationPrecision` 更名 `ContextPrecision@5`：引用格式正确性与检索相关性是两件事，混名会导致"引用编号没乱"被误读为"上下文准"。

目标值全部是**假设**，P0-5 基线出来后据实修订（D2）；修订必须同时写明样本量与单条权重，防止用 1.7pp 的噪声宣称"提升"。

### 4.2 金标来源与配比（D1）

| 来源 | 占比 | 条数 | 获取方式 | 防偏措施 |
| --- | --- | --- | --- | --- |
| 真实日志抽样 | 60% | 36 | 从 `KB_READ` 路由的会话里抽，覆盖 ≥2 个库、≥30 条/库 | 去标识化；按问法类型分层抽 |
| 文档派生 | 30% | 18 | 人工按条款改写提问，答案锚到 chunk | **不得**由被测检索链路或 LLM 自动生成 |
| 对抗 | 10% | 6 | 诗词误引 Kafka 类、跨域专有名词、否定问法 | 固定不轮换，跨版本可比 |

其他硬性要求：
- 两个库（series）各 ≥30 条，避免单库指标冒充全局。
- 留 12 条（20%）做 holdout，调参期间不可见；T2 报告同时给"全体"与"holdout"两组数。
- fixture 语料 6–8 篇，必须含 ≥2 篇带表格、1 篇扫描件产物、1 篇 >2000 字长条款；全是干净政策文会高估解析与分块能力。
- 用例与语料版本号写进报告，语料一改必须升版本并说明原因。

用例 schema：

```json
{
  "version": "2026-11-26.2",
  "corpus": "kb-retrieval-corpus.json",
  "cases": [
    {
      "id": "ret-direct-01",
      "series": "hr-policy",
      "category": "direct",
      "question": "年假天数按工龄怎么算",
      "expect": { "gold_chunks": ["leave-policy#3-p2"], "should_abstain": false }
    },
    {
      "id": "ret-negative-01",
      "series": "hr-policy",
      "category": "no_answer",
      "question": "Kafka 分区副本的 ISR 收缩策略是什么",
      "expect": { "gold_chunks": [], "should_abstain": true }
    }
  ]
}
```

分类条数：direct 18 / synonym 10 / temporal 8 / numeric 5 / multi_hop 4 / no_answer 15，合计 60。每类必须至少预留 1 条失败锚点（synonym→F4、temporal→F5、no_answer→F1/F3），否则修完看不出效果。

报告 JSON：

```json
{
  "version": "2026-11-26.2",
  "env": { "engine": "es", "embedding": "ollama:nomic-embed-text", "fusion": true, "rerank": "lexical-coverage", "abstain": true },
  "summary": { "hit@5": 0, "mrr@5": 0, "ndcg@5": 0, "abstainAccuracy": 0, "falseAnswerRate": 0, "falseAbstainRate": 0, "contextPrecision@5": 0, "p95Ms": 0 },
  "holdout": { "hit@5": 0, "mrr@5": 0, "abstainAccuracy": 0 },
  "failures": [
    { "id": "ret-syn-03", "category": "synonym", "reason": "RETRIEVAL_MISS", "topIds": ["..."] }
  ]
}
```

`reason` 枚举：`RETRIEVAL_MISS`（gold 不在候选）/ `RANK_LOW`（在候选但掉出 top5）/ `FALSE_ANSWER`（应为空却有答案）/ `FALSE_ABSTAIN`（应为答案却为空）/ `INDEX_STALE`（语料与索引不一致）。归因必须逐条落表，否则报告只有均分、无法指导修复。

## 五、实施方案

### 5.1 P0：先度量、再修缺陷（11.5 人日）

| 任务 | 内容 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| P0-0 | **运行时验证 F1–F3**：在真实 ES + Ollama 下分别改 `similarityThreshold` 与 `rerankModel`，确认行为是否真如读码结论；同时采三组基线原始日志 | 1.5 | — | 写一份「缺陷复现/证伪」记录，F1–F3 各给出"复现"或"证伪 + 原因"，进附录 C |
| P0-1 | 建 60 条金标 + fixture 语料（按 4.2 配比） | 4.0 | D1 | schema 校验通过；类目条数、双库、holdout 满足 4.2 |
| P0-2 | T1 结构用例 + T2 报告 Runner + 指标计算 | 2.0 | P0-1 | `-Dpayroll.eval.retrieval=true` 可跑并产出 4.2 格式 JSON；默认跳过时全量测试时长不显著变化 |
| P0-3 | 置信度模型（§3.2）：`relevance` 计算 + 两个阈值语义拆分 + `abstained/lowConfidence` 契约 | 3.0 | P0-0、P0-2 | 开启后 T2 的 FalseAnswerRate 下降且 Hit@5 不降；T1 覆盖阈值生效与拒答 |
| P0-4 | rerank 正名（F2）：未实现即报错，或改名 `lexical-coverage` 并在 `diagnostics.rerank` 输出实际算法 | 0.5 | — | 配置项不再误导；前后端文案同步 |
| P0-5 | 三组基线（现网 / VECTOR-only / legacy）+ 北极星首份 30 条抽检 | 0.5 | P0-2 | 附录 C 填满；D2 据此定档 |

P0-0 必须排在 P0-3 之前：F1–F3 来自读码，未在真实环境确认"用户可见路径确实受影响"。若 P0-0 证伪（例如另有代码路径已应用阈值），P0-3 的范围要当场收缩，不得按读码结论硬做。

### 5.2 P1：按 T2 结果做单项提升（顺序不可换）

| 顺序 | 任务 | 内容 | 前置 | 验收 |
| --- | --- | --- | --- | --- |
| P1-1 | 元数据入索引 | `Chunk.metadata` 进 ES mapping + `Document` 增 `effectiveFrom/To`（Flyway 单文件迁移） | D5、D11 | metadata 可过滤；旧文档默认可见性不变 |
| P1-2 | 时效过滤 | 召回期按生效区间过滤 + as-of 查询参数 | P1-1 | temporal 类达标；跨期问法能区分新旧 |
| P1-3 | BM25 词典 | ES synonym filter（年假/年休假）+ 岗位/薪级术语表，重建索引 | P1-1（同一次重建） | synonym 类 Hit@5 提升且 direct 类不降 |
| P1-4 | τ(category) 标定 | 金标扩到 ≥200 条后做 isotonic 校准 + 分题型阈值网格搜索 | P1-1..3、T2 稳定 | FalseAnswerRate ≤0.10 下 FalseAbstain 最小；holdout 不变差 |
| P1-5 | 低置信 UX | §3.4 的前后端契约与渲染 | P0-3 | nginx 8880 实际页面走通四种状态 |
| P1-6 | Reranker spike（D6） | ONNX CPU（bge-reranker-v2-m3）或替代方案，Top20→5，记录 P95 增量 | P0-5 | 净收益为正且 P95 在预算内，否则不立项；产出 go/no-go |

顺序理由：元数据不先入索引，时效过滤无处落地；词典与 P1-1 共用一次重建，省一次全量索引；阈值标定必须等词典与时序都稳定，否则标出来的是旧召回分布的阈值；UX 依赖契约稳定；Reranker 放最后，因为它既有性能风险又有部署风险（Java 侧无 GPU、Ollama 不支持 cross-encoder [外部]，需 tokenizer + ONNX/DJL 自建）。

### 5.3 P2：结构性补强（须 T2 证明瓶颈后才立项）

- ES 向量改 `knn`（mapping 已 `index:true`）：压测前后 P95 对比，只在 P95 超预算时做。
- 数值/表格通道：结构化字段 + 精确/区间查询，作为第四路（D8）。
- Contextual Retrieval（Anthropic 2024 式块级语境前缀）：需重建索引 + 生成前缀，英文语料结论不可直接迁移，先小样本验证。
- Faithfulness：人工抽检 20 条留痕，不建自动 Judge。
- F9：`Chunk.metadata` 或删或入 mapping，不留空转字段。

### 5.4 收益/成本与证据强度

| 措施 | 预期收益 | 成本 | 证据强度 | 结论 |
| --- | --- | --- | --- | --- |
| 评测体系（T1/T2/T3） | 一切调参的前提，无此则后续全是盲调 | 6 人日 | 强（工程常识，且项目内路由评测已验证可行） | P0 |
| 置信度模型（F1+F3） | 消除"阈值不生效"与"满分假象"，拒答可解释 | 3 人日 | 强（代码缺陷，可证伪） | P0 |
| rerank 正名（F2） | 消除配置误导，诊断可读 | 0.5 人日 | 强 | P0 |
| 时效过滤 | S2 场景正确性 | 2 人日 | 强（规则确定性） | P1 |
| BM25 同义词/术语 | synonym 类召回 | 2 人日 | 中（BEIR 2021 显示 lexical 对术语稳健，但幅度因域而异） | P1 |
| Cross-Encoder 重排 | nDCG +3~10pp | 3 人日 + 运维 | 中（Nogueira & Cho 2019；CPU 延迟未本机验证） | P1 spike |
| kNN | P95 下降 | 2 人日 | 强（ES 官方能力） | P2，仅超预算时 |
| Contextual Retrieval | 召回失败率显著下降（原报告英文语料） | 5 人日+ | 弱-中（跨语言/跨域不可迁移） | P2 验证 |
| 查询重写 / Graph RAG / 换 Embedding | 不确定，有反例 | 高 | 弱 | 不做 |

### 5.5 明确不做（防止反复提议）

Graph RAG、长期记忆入证据列表、查询重写默认开、RAGAS/Python 服务、LLM Judge 自动评分、无基线换 Embedding、全局统一 chunk 尺寸、固定权重 60/30/10。

## 六、决策点

| 编号 | 问题 | 选项 | 倾向 | 决策人 | 截止 |
| --- | --- | --- | --- | --- | --- |
| D1 | 金标语料载体 | A fixture 语料／B 固定 dev 库／C 两者 | A 为主 + 周度真实库抽检 | 检索 owner + QA | P0-1 前 |
| D2 | 指标目标值 | 采用 4.1 假设值／基线后定 | 基线后定，且写样本量 | 产品 + 检索 | P0-5 后 |
| D3 | 是否允许 Python 侧评测 | 允许／不允许 | 不允许（RAGAS 再被提出时才重议） | 架构 | 提出时 |
| D4 | T1 是否进 CI 阻断 | 阻断／只报 | 阻断：T1 无外部依赖、秒级；T2 只报 | 平台 | P1 启动前 |
| D5 | 时效字段 | 本期做／下期／不做 | 本期做（S2 是薪资域刚需） | 产品 + 架构 | P1-1 前 |
| D6 | Reranker 形态 | ONNX CPU／Ollama 替代／不上 | 先 spike 再定，P95 预算为硬约束 | 架构 | P1-6 前 |
| D7 | 两个阈值的 UI 文案与默认值 | 拆两项并改文案／沿用单值 | 拆「召回下限」「拒答阈值」 | 产品 + 前端 | P0-3 前 |
| D8 | 数值/表格通道 | 做／不做 | 看 P0 中 numeric 类得分再定 | 产品 | P2 前 |
| D9 | 已有库是否重切 | 全切／按库选择性／不切 | 按库选择性，新库默认父子 | 运维 + 产品 | P1-3 前 |
| D10 | 查询重写 | 默认关 + 净收益后开／不做 | 不做（与主题门禁冲突） | 检索 | 不设 |
| D11 | 元数据最小字段集 | 仅时效／加部门／加密级 | 仅时效 + 部门；密级属合规另议 | 产品 + 安全 | P1-1 前 |
| D12 | 低置信 UX 形态 | 完全拒答／拒答+可展开／仅提示 | 拒答 + 可展开相近片段 | 产品 | P1-5 前 |

## 七、风险、灰度与回滚

| 风险 | 触发条件 | 处置 |
| --- | --- | --- |
| 读码结论被运行期证伪 | P0-0 发现另有生效路径 | 收缩 P0-3 范围，先记录再改方案，不硬做 |
| 金标过拟合 | 调参只在 60 条上变好 | 12 条 holdout 不可见；P1-4 前不宣称"提升 X%" |
| fixture 与真实库分布不符 | fixture 全是干净政策文 | 强制 ≥2 表格、1 扫描件、1 长条款；T3 用真实日志兜底 |
| 阈值接回引发召回骤降 | 现网 0.30 本就无效，接回后行为突变 | `payroll.kb.confidence.enabled` 默认 false；按配置版本单库灰度，对比命中数 |
| 拒答过多伤体验 | τ 偏高 | FalseAbstainRate 硬指标 ≤0.10；按题型分阈值 |
| Reranker 延迟超预算 | CPU 推理慢 | `rerankEnabled` 按库可关；P95 增量超 50ms 即回滚 |
| 重切导致旧引用失效 | 引用硬绑 chunk id | 引用按文档+段落锚点解析，不绑 chunk id |

灰度：新行为全部由配置版本与系统属性控制，粒度到库；先单库 → 单部门 → 全量。回滚：F1/F3 类改动无数据迁移，代码级 revert 即可；涉及索引重建的（P1-1..3）保留旧索引版本直到新版本 T2 通过。

## 八、验收（DoD）

1. P0-0 记录：F1/F2/F3 各给"复现 / 证伪 + 原因"，进附录 C。
2. D7 在 P0-3 前有结论；其余 D 均有决策人与截止（即便结论是"暂不做"）。
3. `mvn -o -q -DskipTests package` 通过；`mvn -o test` 全量绿，且不显著变慢。
4. T1 用例至少覆盖：阈值生效、拒答标志、父块回填、相邻合并、单文档限流、主题门禁，各 ≥1 条。
5. T2 产出 4.2 格式报告，三组基线填满附录 C，含 holdout 分组。
6. 打开 `payroll.kb.confidence.enabled` 后：Hit@5 与 MRR@5 不低于基线，FalseAnswerRate 下降，P95 在 G-3 预算内；任一条不满足则默认维持 false。
7. 前端改动（D7 文案、P1-5 低置信态）：`npm run build`、`npm run test` 通过，nginx 8880 实际页面走通四种状态。
8. 薪酬回归金标 100% 一致（护栏 G-1）。

## 附录 A：业界标准对照

| 主张 | 出处 | 本项目落点 |
| --- | --- | --- |
| RRF 无需调参即接近最优，k=60 | Cormack, Clarke, Buettcher, SIGIR 2009 | 已用 k=60（`KbRetrievalService.java:1271-1272`），不调 |
| BM25 需语料统计与字段权重，是稳健基线 | Robertson & Zaragoza, 2009；Thakur et al., BEIR 2021 | 保留 BM25；`content^2 + titlePath` 已有权重，缺同义词（P1-3） |
| Cross-Encoder 重排显著提升排序 | Nogueira & Cho, 2019 | P1-6 spike；CPU 延迟需实测，不采信外部数字 |
| 块级语境前缀 + BM25 + 重排可降检索失败率 | Anthropic Contextual Retrieval, 2024 | P2 小样本验证；英文语料，不直接迁移 |
| nDCG 作为排序质量主指标 | Järvelin & Kekäläinen, 2002；TREC | T2 指标（4.1） |
| 长上下文中部信息利用率低 | Liu et al., "Lost in the Middle", 2023 | 父块上限 1200 字符、Top5 进上下文，方向一致 |
| 概率校准后再设阈值 | Zadrozny & Elkan, 2002（isotonic/Platt） | P1-4；P0 不做校准（60 条会过拟合） |
| Context Precision 的 rank-weighted 口径 | Es et al., RAGAS, 2023（定义） | 采用定义，不自建框架、不引 Python（D3） |
| 拒答应按题型分阈值 | 信息检索中按 query 类型分档的常规做法 | P1-4 的 τ(category) |

## 附录 B：外部指南的事实性错误（备查）

| 原文 | 问题 | 更正 |
| --- | --- | --- |
| "BGE-M3 或 *ES-large-zh" | 后者不存在 | `bge-large-zh-v1.5` |
| "LangChain4j 评测服务 / 方案 A 推荐" | LangChain4j 无官方评测模块 | 等价于自研 Judge；成熟框架在 Python（RAGAS/TruLens/DeepEval） |
| "RAGAS 是 FastAPI 服务" | RAGAS 是 Python 库 | 可嵌脚本，不需独立服务 |
| "父子索引 + 10–20% 重叠" | 两者叠加会重复 | 父子模式父块不重叠，只在子块层设重叠 |
| "Chunk 建议 200–500 tokens" | 与父块 1024 冲突 | 仅适用于单层检索 |
| "召回 Top50 过 Cross-Encoder" | 未给算力前提 | CPU 中文单次几十毫秒，需 GPU 或降到 Top20 |
| "权重 60/30/10" | 无出处 | 按项目实测分配，不设先验权重 |
| "准确率提升 40%" 类结论 | 未定义基线、指标与样本量 | 不作为立项依据 |

## 附录 C：基线记录（P0-5 填写）

| 配置 | 引擎 | Embedding | Hit@5 | MRR@5 | nDCG@5 | AbstainAcc | FalseAnswer | FalseAbstain | ContextPrec@5 | P95 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 现网（fusion + lexical rerank） | ES | ollama | — | — | — | — | — | — | — | — |
| VECTOR only | ES | ollama | — | — | — | — | — | — | — | — |
| legacy（fusion 关） | ES | ollama | — | — | — | — | — | — | — | — |
| 现网 + 置信度（P0-3 后） | ES | ollama | — | — | — | — | — | — | — | — |

P0-0 缺陷复现记录（每项填"复现 / 证伪 + 证据"）：

| 缺陷 | 结论 | 证据（日志/截图/请求样例） |
| --- | --- | --- |
| F1 阈值不生效 | 待测 | — |
| F2 rerank 名不副实 | 待测 | — |
| F3 top1 恒 1.0 | 待测 | — |

## 附录 D：v0.1 → v0.2 变更

| # | v0.1 | v0.2 | 原因 |
| --- | --- | --- | --- |
| 1 | 单层 JUnit 评测 | T1/T2/T3 三层 | CI 用 fake store + HASH 向量，测的是降级路径，结论不可迁移 |
| 2 | G1、G3 分列两个任务 | 合并为「检索置信度模型」 | 阈值语义依赖绝对分定义，分开做必返工 |
| 3 | 金标来源未定义 | 60/30/10 配比 + 双库 + 20% holdout | 防止只用自造干净语料过拟合 |
| 4 | 无产品视角 | 北极星（月度 30 条人工抽检）+ 3 条护栏 | 技术指标与业务正确率脱钩 |
| 5 | `CitationPrecision` | `ContextPrecision@5`（RAGAS 口径） | 原名是误称：引用格式 ≠ 检索相关性 |
| 6 | D 表仅列问题与倾向 | 增加决策人、截止 | 决策可追踪 |
| 7 | P1 中时效先于元数据 | 元数据（P1-1）先于时效（P1-2） | 时效字段依赖 metadata 入索引 |
| 8 | no_answer 10 条 | 15 条 | 10 条时单条 = 10pp，指标不可用 |
| 9 | 无证据分级 | [读码]/[实测]/[外部]/[假设] | 区分已验证与待验证，避免把读码结论当运行期事实 |
| 10 | 直接修 F1–F3 | 新增 P0-0 运行时验证 | 缺陷来自读码，未在真实 ES+Ollama 复现 |
| 11 | 收益评估凭直觉 | §5.4 加业界证据强度（强/中/弱） | 外部结论跨域不可直接采信 |
