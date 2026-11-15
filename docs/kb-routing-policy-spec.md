# 知识库路由策略规格（P0–P3）

- 状态：**P0 已实施（2026-11-15，默认 `enabled=false`，行为与旧关键词判定逐条一致）**；P0-0 已完成（第一轮现状 FLOW×50；清空默认流程后真实金标 PIPELINE 16 / AGENT 31，47/50 成功，3 条 3B 空回复，见 4.5.1）；**P1 已实施（2026-11-15，need_kb 进入路由与链路选择，见 5.7）**；**P2-3 语义路由已实施（2026-11-15，默认 `payroll.router.semantic.enabled=false`；qwen3-embedding:0.6b 已装，confident 口径门槛通过，见 6.1）**；P2 其余子项与 P2.5 起范围收窄
- 日期：2026-11-15（初稿）、2026-11-15（二轮评审修订）、2026-11-15（P0 实施）、2026-11-15（P1 实施）、2026-11-15（P2-3 实施 + 语义门槛实测）、2026-11-15（阈值定档 0.50 + 运行时实测）、2026-11-16（种子扩充 2026-11-16.1 + 门槛复测）
- 关联：`docs/kb-chat-integration-design.md`、`docs/three-module-split-spec.md`、`docs/chat-resume-design.md`
- 硬约束：**薪酬类问题的路由、链路、工具集与缓存行为保持现状，一行不变**

> 背景实证（2026-11-15）：会话 26 在「Kafka 的分区机制请按知识库文档回答」后追问
> 「Kafka 分区机制再简要说明一下」，因不含知识库关键词，`KbChatIntent.matches` 返回
> false，最终按会话默认流程「AI视频」执行（`path=FLOW`，19:40:33 日志），检索完全没有发生。
> 这说明关键词不是"检索判定的补充"，而是当前唯一的准入闸门。

> 二轮评审结论（2026-11-15）：方案方向成立，P0 可以落地；但初稿的 A2/A4/A6 三处规则
> 语义会与现有硬分支冲突（A2 误伤 KB 管理链路、A4 劫持引用后的闲聊、A6 把绑定会话的
> 所有兜底问句送进 KB 智能体）；P1 的 `route()` 调用位置使模型 `need_kb` 无法影响流程
> 选择，属结构性阻塞；P2.5 的"99.5% + path/agent 差异即阻断"等价于 100%，表述自相矛盾。
> 本次修订逐条改正，并新增第十四、十五节（实施前置与决策点、修订后的实施顺序）。

## 一、已确认决策

| 维度 | 决策 | 理由 |
| --- | --- | --- |
| 薪酬链路 | 完全不变：判定入口、路由 schema、instruction、链路选择、工具集、缓存键全部保持 | 薪酬核算有确定性要求，不允许任何概率性改道 |
| 检索定位 | 检索是**工具**，不是二选一的路由结果；路由只决定"要不要查、查哪个范围" | 业界主流（OpenAI file_search、Claude tool use、Bedrock KB retrieve-as-tool） |
| 规则定位 | 关键词只做 L0 覆盖（强制包含 / 强制排除），不做准入白名单 | 关键词无法覆盖同义、追问、跨语言 |
| 决策主体 | P1 起由 `AgentRouter` 统一决策 `target + need_kb + kb_kind` | 复用现有置信度与回退机制，不新建并行路由器 |
| 语义层 | P2 引入 embedding 意图分类，但**不得接管薪酬判定**，薪酬仍以确定性规则为准 | 3B 模型/向量误判会直接污染核算链路 |
| 薪酬语义 | 仅在影子/缓存两种模式运行，永不替代薪酬判定与路由决策；写操作桶禁用缓存 | 这是"功能不能改变"唯一可验证的形态 |
| 权限 | 路由只决定范围（session / all），库级 READER 仍由 Web 侧强制 | 路由不越权，`KbSearchScope` 继续作为唯一收窄来源 |

## 二、现状与差距

| 能力 | 现状 | 证据 |
| --- | --- | --- |
| KB 判定 | 纯关键词，先算先短路；薪酬词一票否决 | `payroll-common/.../agent/KbChatIntent.java:14-38` |
| 判定结果的使用 | 4 处硬分支：流程置空、绕过团队、直连 KB 智能体、团队门禁 | `payroll-agent/.../service/PayrollChatService.java:435`、`447-454`、`585-598`、`671-681` |
| 智能体路由 | 纯轻量模型，输出 `agent / team / pipeline` 三选一，无"是否检索"维度 | `payroll-agent/.../router/AgentRouter.java:28-45`、`100`、`143-192` |
| 追问延续 | 不支持：上一轮引用不参与下一轮判定；runtime 也未读取上一轮 citations | `ChatMessage.java:17`、`H2ChatStore.java:894` 已有数据，未使用 |
| 会话绑定 | 绑定只影响检索范围，不影响"要不要查" | `ChatContextStore.java:25`、`PayrollChatService.java:386` |
| 能力约束 | 用"流程不含 KB 节点就置空"替代，属关键词时代的补丁 | `PayrollChatService.java:447-454` |
| 评测 | 只有关键词单测，无路由准确率与薪酬回归金标 | `payroll-tests/.../agent/KbChatIntentTest.java` |

## 三、目标模型

### 3.1 意图分类

| 意图 | 定义 | 处理 |
| --- | --- | --- |
| `PAYROLL` | 命中 `PAYROLL_MARKERS`（词表不改） | 现状链路，KB 判定不参与 |
| `KB_READ` | 制度、流程、条款、产品/技术资料问答（含追问） | 检索链路 + 引用 |
| `KB_ADMIN` | 建库、改配置、成员、目录、重建索引等管理操作 | 现有"按 tag 选智能体"逻辑，不走检索 |
| `OTHER` | 视频、写作、闲聊、数据查询等 | 现有路由逻辑 |

`KB_READ` / `KB_ADMIN` / `OTHER` 的区分从 P1 起交给 `AgentRouter`，P0 用规则过渡。

### 3.2 决策分层

```
L0 确定性规则（KbRoutingPolicy）   ← 只产出 FORCE / SKIP / CANDIDATE / NONE
        │
L1 语义路由（P2，embedding）        ← 非薪酬参与决策；薪酬仅影子/缓存（P2.5）
        │
L2 模型路由（AgentRouter）          ← target + need_kb + kb_kind + confidence
        │
L3 工具决策（kb_search）            ← 回答智能体在链路内自行调用，作最后兜底
```

### 3.3 决策优先级（不可颠倒）

1. `PAYROLL`：直接使用今日 `AgentRouter` 原 instruction 与三选一 schema；`need_kb` 强制 false 且忽略模型输出。P2.5 允许对薪酬额外执行语义分类，但只用于影子记录或缓存复用既有决策，不改变本条结论。
2. `FORCE`（显式要求 / 追问延续 / 关键词）：无论 L1/L2 结论如何，本轮走检索链路；关键词白名单长期保留，不随 P2 删除。
3. `SKIP`（薪外显式回避，如"不要查资料，凭经验答"）：本轮不检索。
4. `CANDIDATE`（会话已绑定知识库）：不抢已有流程/智能体，只在兜底分支优先生效；P0 默认只观测不改道（见 4.2.1），且仅对"追问 / 短问题且无强 OTHER 特征"放开，避免把绑定会话里的闲聊全部送进 KB 智能体。
5. `NONE`：完全沿用今日静态链路。

## 四、P0：止血（预计 1.5 天）

目标：不引入模型决策，先把"该查却没查"的两类召回补上，薪酬行为零改动。

### 4.1 规则表

按顺序判定，但**不是全线"命中即停"**（初稿此处自相矛盾，是本轮修订重点）：

| 序 | 条件 | 结果 | 终止性 |
| --- | --- | --- | --- |
| A1 | 命中 `PAYROLL_MARKERS` | `SKIP_PAYROLL` | **终止**，后续规则全部跳过；词表与今天完全一致 |
| A2 | 命中行动类标记（执行/开始/生成/导出/取消/重跑/提交/确认/上传/下载…） | 不产出决策，置 `actionGuard=true` | **不终止**，只压制 A4；A3/A5/A6 照常判定 |
| A3 | 问题显式要求检索（"按知识库/资料/文档回答"） | `FORCE_EXPLICIT` | 首个 FORCE 命中即定结果 |
| A4 | 上一轮助手消息 `citations` 非空 **且本轮是追问**（见 4.1.1） | `FORCE_FOLLOW_UP` | 首个 FORCE 命中即定结果；A2 命中时不生效 |
| A5 | 命中 `KB_MARKERS` | `FORCE_KEYWORD` | 首个 FORCE 命中即定结果 |
| A6 | 会话绑定知识库（`kbScope.bound()`） | `CANDIDATE_BOUND` | 只在无 FORCE 时产出，且 P0 不改道（见 4.2.1） |
| A7 | 其余 | `NONE` | 与今日一致 |

规则语义要点：

- **A2 只压制 A4，永不终止链路**：初稿把 A2 写成"命中即停 → NONE"，会让"上传文档到知识库""提交重建索引"这类既含行动词、又含 KB 管理语义的问题被判成 `NONE`，直接打断知识库管理链路。
- **A2 与 A3 同时命中**：以 A3 为准（用户显式要求检索优先于行动词），日志记 `source=FORCE_EXPLICIT override=action_vs_explicit`。
- **A2 命中但 A3/A5 均未命中**：结果交给 A6 判定（未绑定即 `NONE`），日志记 `source=action_guard`，便于统计拦截量。
- A2 词表不必按"是否属于知识库管理"拆分：由"A2 不终止"这条保证管理链路安全，词表保持简单可测。

#### 4.1.1 A4 的"追问"判定

初稿的 A4 只要求"上一轮 `citations` 非空"，等价于把引用后的**任意**下一问都强制改道检索。引用完制度后问"帮我生成一段视频讲解"或换话题问"今天天气"，都会被劫持。必须叠加追问特征：

| 条件 | 说明 |
| --- | --- |
| 含连续/指代特征 | 再、继续、还有、接着、它、其、这个、那个、上述、上面、刚才、该、前者、后者 |
| 或长度 ≤ 20 字且无强 OTHER 特征 | 强 OTHER：视频、写作、翻译、代码、天气、笑话、计算、导出、下载、生成… |
| 且 A2 未命中 | 行动指令优先回到原流程 |
| 不要求会话绑定 | A4 只依赖上一轮引用，绑定与否都成立（会话 26 的 `series_ids=[]` 不影响 A4） |

阈值（20 字、特征词表）按 P3 的 150 题集校准，先按此口径实现。

### 4.2 行为映射（关键：只加不拆）

| 决策 | 现有 `kbQuestion` 四分支 | 新行为 |
| --- | --- | --- |
| `SKIP_PAYROLL` / `NONE` | false | 完全不变 |
| `FORCE_*` | true | 沿用 `447-454`（流程置空）、`585-598`（绕过团队）、`671-681`（直连 KB 智能体） |
| `CANDIDATE_BOUND` | false | **P0 默认不改道**：`kbQuestion` 仍为 false，只在日志与 `routing_json` 记录 `candidate=1`；开关开启时按 4.2.1 的最小范围生效 |

#### 4.2.1 CANDIDATE 的最小生效范围（P1 起）

初稿"在 `671-681` 的 if 上加 `|| candidateBound`"会让**绑定会话里所有未命中关键词的轮次**（含闲聊、视频、写作）都落到 `PayrollKbAgent`，返回大量 NO_HIT。改为主张：

1. 候选只在"追问 / 短问题且无强 OTHER 特征"时参与（与 4.1.1 同一口径）；
2. 候选轮次**先执行一次 `kb_search`**，命中为 0 时回落原 pipeline（不改道、不输出 NO_HIT 文案）；
3. 候选改道结果计入 `kb_route_candidate_total{outcome=hit|miss|flow}`，命中率低于 50% 时自动降级为"仅记录"。

第 2、3 条要改"检索-回答"编排，P0 不实现；A6 在 P0 只落日志、不参与行为。

### 4.3 改动点

| 文件 | 改动 |
| --- | --- |
| 新增 `payroll-common/.../agent/KbRoutingPolicy.java` | 枚举 `Source` 与 `Decision`，纯函数 `decide(question, bound, lastCited, enabled)` 返回 `(Decision, Source, actionGuard)`；`enabled=false` 时退化为 `KbChatIntent.matches` |
| `ChatContextStore.java` | 新增 default 方法 `lastAssistantCited(long sessionId)`，默认 false |
| `H2ChatStore.java:894` 附近 | 实现单行查询：最近一条 ASSISTANT 消息的 `citations_json` 是否非空；确认 `(session_id, message_id)` 可用索引 |
| `PayrollChatService.java:435` | 计算 `KbRoutingPolicy.Decision`；金额/薪酬分支不读取新增字段 |
| `PayrollChatService.java:673` | `kbAgent` 条件由 `kbQuestion` 改为 `decision.forced()`；`candidate` 在本阶段不参与（见 4.2.1） |
| 配置 | 新增 `payroll.chat.kb-policy.*`；`enabled` 默认 **false**，待 50 题薪酬基线产出后再开 |
| 日志 | 每轮新增一行 `[知识库][路由] policy=..., source=..., bound=..., last_cited=..., candidate=...` |

不新增表、不改 `kb_search` 契约、不改前端、不改团队模板。

### 4.4 测试

| 用例 | 期望 |
| --- | --- |
| 「核算 EMP001 2026-10 的工资」/「工资按制度怎么算」 | `SKIP_PAYROLL`，路径与今日快照一致 |
| 「Kafka 如何保证消息不丢失？请按知识库说明回答」 | `FORCE_EXPLICIT` |
| 「上传文档到知识库」/「提交重建索引」 | 仍走 KB 管理链路（A2 不终止 A3/A5） |
| 上一轮带引用 +「再简要说明一下」 | `FORCE_FOLLOW_UP` |
| 上一轮带引用 +「开始生成视频」 | `NONE`，`source=action_guard`（A2 压制 A4） |
| 上一轮带引用 +「今天天气怎么样」 | `NONE`（有引用但非追问，A4 不命中） |
| 已绑定 KB +「生成一段视频」 | `CANDIDATE_BOUND`，仍走流程（P0 不改道） |
| 已绑定 KB + 未收录主题、无关键词 | `CANDIDATE_BOUND`，P0 不检索、只落日志 |
| 开关 `enabled=false` | 与今日 `KbChatIntent` 单测结果逐条一致 |

### 4.5 验收与回滚

- 前置：先在 `enabled=false` 下跑 50 题薪酬基线（P0-0），产出 `routing-baseline.json`；没有基线不得开启开关。
- 验收：19:40 的追问场景改为检索链路，`kb_search` 有调用且带引用；50 题薪酬金标路径与 P0 前完全一致。
- 回滚：`payroll.chat.kb-policy.enabled=false`，行为回到纯关键词，无需发版回滚数据。

#### 4.5.1 P0-0 基线结果（2026-11-15，隔离实例 web 8081 / runtime 8101）

数据：`output/p0-baseline/routing-baseline.json`（真实链路金标）、`routing-baseline-preview.json`（第一轮预览）、`legacy-check.tsv`（逐题旧判定）。`output/` 已 gitignore。

**第一轮：复现现状（默认流程存在）**

| 指标 | 结果 | 说明 |
| --- | --- | --- |
| 旧关键词判定 | 50/50 `KbChatIntent.matches=false` | 50 题全部命中薪酬词（`isPayroll=true`），按设计不进 KB |
| 新策略等价 | `enabled=false`：50/50 `NONE/DISABLED`，与旧判定 0 不一致；`enabled=true`：50/50 `SKIP_PAYROLL/PAYROLL` | 用编译后的 `KbChatIntent` 与 `KbRoutingPolicy` 逐题对照，非脚本复刻词表 |
| 实际对话路径 | **FLOW × 50** | 默认流程 `flow-bc12ba50`「AI视频」存在即短路（`PayrollChatService:763`）；薪酬题 `kbQuestion=false`，不触发"流程不含 KB 节点改道"，`route()` 不被调用 |
| 路由预览 p50/p95/max | 1099 / 1590 / 7956 ms | `POST /internal/route/preview` 连跑 50 次（模型热态） |
| 预览 JSON 失败率 | 0%（50/50 合法） | 冷启动首请求曾 10.1s 超时（`轻量模型不可用或超时`）；50 题正式批次未复现 |
| 预览 target 分布 | pipeline 19 / agent 31 | 只在"无默认流程"的假设下有意义 |

**第二轮：清空默认流程后的真实链路金标（开工前快照 `activeFlowId=flow-bc12ba50`，跑完已恢复）**

| 指标 | 结果 | 说明 |
| --- | --- | --- |
| 执行 | 50 题同步 turn（`POST /api/chat/sessions/{id}/messages`） | 每条问题独立新会话，无上下文干扰 |
| 成功/失败 | **47 / 3** | 3 条 HTTP 400「核算智能体未返回内容」：#3、#6、#40，均为 3B 模型空回复，preview 同时均成功 |
| 实际路径 | **PIPELINE 16 / AGENT 31**（失败 3 未落路由） | 与 preview 一致的题 46/47；#37 预览 agent、实际 PIPELINE（两次模型调用结果不同） |
| turn 耗时 p50/p95/max | 5443 / 34458 / 47164 ms | 成功子集 p95 20764、max 37933；长尾来自智能体多轮生成 |
| 智能体落点 | `payroll-accountant` 21、流水线 16、`办公文书专家` 3、`java工程师` 3、`情感专家` 2、`payroll-validator` 2、失败 3 | 31 条 AGENT 中 21 条进薪酬入账专家（无检索能力），8 条进非薪酬智能体 |
| 引用 | 0 | 50 题无一次 `kb_search` 调用 |
| 路由回退（fromPath） | 0 | 无回退链路 |
| 极短回复 | 1 条 | #33「年假未休补偿是否计入工资」仅 3 字 |

**结论**

1. 开关默认 `false` 可上线：行为等价已由真实类验证，薪酬链路代码未变；后续开开关后必须与第二轮逐题 `path + agent` 比对，**0 差异**（3 条空回复题标注"不可比"并单列）。
2. **薪酬链路的真实基线是 PIPELINE 16 / AGENT 31，不是 FLOW。** 默认流程存在时薪酬题根本进不到流水线/智能体；4.5 的"金标路径与 P0 前一致"只有在清空默认流程（或换成含核算节点的流程）时才有意义。
3. `POST /internal/route/preview` 直接调用 `router.decide`，不感知默认流程，且模型非确定性会导致预览与实际不一致（#37），**不能替代真实链路评测**。P1 应让它同时返回"是否存在默认流程/是否被短路"。
4. 50 题全部含薪酬词 → A1 终止 → **制度/政策类问题（社保/公积金/个税政策）在这份问题集下永久不可检索**。这是"薪酬冻结"的既定代价；若 P1/P2 希望政策题进 KB，需要单独评审收窄 `PAYROLL_MARKERS`（如限定"核算/计算/试算 + 工资/个税"组合），不在 P0 范围。
5. 质量观察（非 P0 范围，但会污染金标可读性）：流水线首题返回澄清问句而非计算；1 条 3 字回复；3B 模型空回复率 6%。稳定评测建议换 7B+ 模型。
6. 环境已复原：`activeFlowId` 恢复为 `flow-bc12ba50`，51 个测试会话已通过删除接口清理，8081/8101 已停止，临时令牌文件已删除。

## 五、P1：KB 进入路由决策（预计 3 天，含 0.5 天行为等价重构）

目标：把"要不要检索"并入 `AgentRouter`，关键词降级为 L0 覆盖，薪酬 prompt 保持原样。

### 5.1 路由 schema

```json
{"target":"agent|team|pipeline","agent_id":"","need_kb":true,"kb_kind":"read|admin|none","reason":"","confidence":0.9}
```

- `target` 三值不变，避免 persona、缓存键、前端 chip 语义连带变化；
- `need_kb=true && kb_kind=read` → 本轮必须走具备 `kb_search` 的链路；
- `kb_kind=admin` → 继续用今天的"候选 tags 含 kb"选智能体，不检索；
- 字段缺失（模型没输出）按 `need_kb=false, kb_kind=none` 处理，不判非法。

### 5.2 双 instruction（保证薪酬不变）

- 薪酬问题继续使用 `AgentRouter.INSTRUCTION` **原文**，prompt 字节级不变，决策可比；
- 非薪酬问题使用 `INSTRUCTION + KB_SECTION`，新增内容示例：

```
4. need_kb 表示本轮是否需要检索知识库：
   - 制度、流程、条款、手册、产品资料、技术文档类问题，或对上一轮已引用内容的追问 → true；
   - 视频制作、写作、闲聊、纯数据查询、执行类指令 → false；
5. kb_kind：需要检索填 "read"；知识库管理操作填 "admin"；其余填 "none"；
6. 工资/核算/个税/社保等计算类问题由固定规则处理，不在你的判断范围内。
```

### 5.3 决策接线

| 场景 | 行为 |
| --- | --- |
| `PAYROLL` | 不进入 L0/L1，调用原 instruction，`need_kb` 忽略；`447-454` 等分支不执行 |
| `FORCE_*` | 覆盖模型 `need_kb=false`，走检索链路；理由记为 `override:force` |
| 模型 `need_kb=true` | 先按 `flowKbCapable`（`1468-1479`）过滤流程/智能体/团队；团队门禁（`585-598`、`608`）必须把 `need_kb` 一并计入，否则会选中不含 KB 角色的团队。都不具备时由 `PayrollKbAgent` 作答（等同今日 `671-681`） |
| `need_kb=true` 但 `target=agent` 且该智能体无 `kb:read` | **显式改道**到 `PayrollKbAgent` 并在 `routing_json` 记 `kb.reroute=true`，不允许静默丢弃检索需求 |
| 模型 `need_kb=false` | 按 `target` 执行；`CANDIDATE_BOUND` 不覆盖该结论 |
| 模型失败/低置信度（<0.50） | 回退到 L0 决策；L0 为 `NONE` 时沿用静态链路，日志记录失败原因 |

### 5.4 改动点

| 文件 | 改动 |
| --- | --- |
| `AgentRouter.java:28-45` | 拆出 `PAYROLL_INSTRUCTION`（原文）与 `KB_SECTION`；`instruction(...)` 按问题类别选择 |
| `AgentRouter.java:143-192` | `parse` 增加 `need_kb` / `kb_kind` 解析与默认值；非法值不判失败 |
| `RouteDecision.java` | 增加 `needKb`、`kbKind`、`kbFrom`（router / force / fallback）。**仓库内 `new RouteDecision(` 共 4 处**，全部需补参 |
| `ChatRouting.java:11` | 新增可空字段 `kb`（`{"need":true,"kind":"read","source":"router","scope":"session"}`），旧数据为 null，前端隐藏。**`new ChatRouting(` 共 4 处**，另需前端解析、chip 与 Markdown 导出同步 |
| `PayrollChatService.java` | `435`、`447-454`、`585-598` + `608`（团队门禁与 `attempt`）、`671-681` 改为读取决策对象；`447-454` 的执行时机见 5.6 |
| `PayrollChatService.java:1468-1479` | `flowKbCapable` 从"流程健壮性补丁"改为能力查询入口，供 5.3 的过滤与改道共用 |
| `InternalTurnController.java:137` + `AgentRouteController.java:32` | 路由预览返回 `kb` 决策，供 P3 评测直接调用 |
| 前端 `ChatView.vue` 路由详情 | 增加一行"知识库判定：是/否（来源：模型/追问/绑定）" |

### 5.5 测试

- `AgentRouterTest` 增加：`need_kb` 正常/缺省/非法值、`kb_kind` 三类、低置信度回退保持；
- 薪酬 prompt 快照测试：同一问题在 P1 前后送出的 instruction 文本完全相等；
- `ChatRouting` JSON 兼容：无 `kb` 字段的旧消息反序列化不报错、导出不变（含 4 处构造点的编译期校验）；
- **顺序回归（关键）**：会话绑定一个不含 KB 节点的流程 + 不含关键词的追问 → 必须走检索，不得沿用绑定流程；
- **团队门禁回归**：`need_kb=true` 时不得选中不含 KB 角色的团队；选中后 `activeTeam.attempt` 的 `kbQuestion` 参数与决策一致；
- **改道回归**：`need_kb=true` + `target=agent`（该智能体无 `kb:read`）→ 落到 `PayrollKbAgent` 且 `routing_json.kb.reroute=true`；
- 行为等价：今日 12 条关键词命中用例在 P1 下仍全部走检索（`FORCE_KEYWORD` 长期保留，见 6.4）。

### 5.6 执行顺序（P1 的阻塞项，必须先做）

**现状**：`PayrollChatService` 中 `447-454` 的"流程置空"发生在 `465` 之前，而 `465` 的 `route()` 只在 `resolvedFlow == null` 时调用。两者都由 `435` 的关键词判定前置决定，因此**模型的 `need_kb` 永远无法影响流程选择**——事故场景（绑定"AI视频"流程 + 追问）在初稿 P1 下仍会复现。

**目标顺序**：

```
1. L0 规则（KbRoutingPolicy）        → payroll / force / candidate / none
2. 解析会话流程绑定                    → resolvedFlow
3. 非薪酬且 L0 非 FORCE 时调用 route() → need_kb / kb_kind
4. 能力过滤                            → need_kb=true 时按 flowKbCapable 过滤流程（替代 447-454 的事前置空）
5. 团队门禁                            → need_kb 同时进入 activeTeam.allowed 与 activeTeam.attempt
6. 兜底                                → 671-681
```

要点：

- 步骤 3 的副作用：非薪酬轮次的路由调用从"仅无流程时"变为"所有非薪酬轮次"，先测 p95 与 JSON 解析失败率（见 P1-4 门槛）。若超阈值，保留"已绑定非 KB 流程时不调用路由"的短路，并在 P3 评测中单列该类用例。
- 步骤 2→3 的顺序调整本身是行为变更，必须先用 `need-kb-enabled=false` 单独提交一版**行为等价**重构，再叠加 `need_kb`。
- `need_kb=false` 时步骤 4 不执行，`447-454` 的旧语义由能力过滤等价替代；P2 才删除旧代码（开关保留一个版本周期）。

### 5.7 实施记录（2026-11-15）

- `RouteDecision` 增加 `needKb / kbKind / kbSource`（`router / force / fallback`）；`kb_kind` 合法值为 `read / admin / none`，缺失或非法按 `read`（need=true）/ `none` 处理，不判路由失败。
- `AgentRouter` 对非薪酬问题追加 `KB_SECTION`，薪酬问题继续使用原 `INSTRUCTION`；快照测试锁定薪酬 prompt 字节不变。
- `PayrollChatService.run()` 按 5.6 重排：L0 → 解析流程 → 非薪酬轮次调用 `route()` → `planKb()` → 能力过滤 → 团队门禁 → `PayrollKbAgent` 兜底。非薪酬轮次包含 L0 `FORCE_*`，保留既有知识库问句的模型路由能力；`FORCE_*` 以 `source=force` 覆盖模型 `need_kb=false`。
- 能力过滤使用 `flowUsable()`：`need_kb=true` 且绑定流程不含知识库节点时放弃流程；团队门禁的开启条件与 `activeTeam.attempt(..., kbQuestion)` 均改用 `needKb`。
- 显式改道使用 `needsKbReroute()`：模型选中无 kb 标签的单智能体时落到 `PayrollKbAgent`，并在 `routing_json.kb.reroute=true` 留痕；`kb_kind=admin` 不触发检索。
- `ChatRouting` 新增可空 `kb {need, kind, source, scope, reroute}`；无 `kb` 的旧快照仍可反序列化。前端路由详情、路由预览与 Markdown 导出同步展示。
- 路由预览接口新增 `payroll`、`kb{need, kind, source}`、`flow_active / flow_id`；P2-3 起再返回 `l0{decision, source, action_guard}`，且预览按"未绑定、无引用上下文"计算 L0，语义层调用门禁与真实链路共用 `KbRoutePlanner.semanticEligible`（L0 强制时不跑语义），消除预览"总是跑语义、总是传 l0=null"的偏差。
- 验证：`AgentRouterTest` 18 项、`KbRouteOrderingTest` 8 项、全仓 556 项测试通过；前端 `npm run build` 通过。
- 金标对比：50 题全部命中薪酬词，薪酬轮次 `planKb` 恒不检索且 prompt/链路未变，预期路径与智能体 0 差异。本轮未重启本机共享服务复跑真实 turn，金标仍以 4.5.1 的第二轮结果为准。

### 5.8 P2-3 实施记录（2026-11-15，默认关闭）

- 新增 `payroll-common/.../agent/SemanticRouteSettings`（独立模型/索引/超时）与 `RouteIntentSeeds`（解析并校验 3 标签种子，版本号参与 ES 幂等）。
- 新增 `RouteIntentStore`：ES 映射 `vector(dense_vector dims=1024)`、`label/label_version/model(keyword)`；按 `version+model` 计数，缺量时清理旧版本、批量重建（确定性 `_id`），查询用 `script_score + cosineSimilarity`（ES 7.17 无 kNN，不建 HNSW；`index:false` 在 7.17 是默认值且会触发弃用告警，故不显式声明）。
- 新增 `SemanticRouteClassifier`：启动时探测嵌入与 ES，同步写入 90 条种子；`classify()` 只返回标签、分数、margin 与是否高置信，任何异常/超时都返回 `unavailable`。判定规则集中在静态 `decide()`，便于单测。
- 新增 `KbRoutePlanner`，把原先散落在 `PayrollChatService.planKb()` 的优先级固化为一处：薪酬 → L0 强制 → L1 语义 → L2。`PayrollChatService` 与 `InternalTurnController` 均改用它，删除旧 `planKb/KbPlan`。
- 语义只在 L0 为 `NONE / CANDIDATE_BOUND / FORCE_KEYWORD` 且非薪酬时调用；`FORCE_EXPLICIT`/`FORCE_FOLLOW_UP` 不调用（必然强制检索，语义改不了）。
- 日志：`[知识库][语义] label=… score=… margin=… confident=… 耗时=…ms`；合并后 `[知识库][路由] 合并决策：need_kb=… kind=… source=…`。路由预览新增 `semantic{label,score,margin,confident,reason}` 与 `l0{decision,source,action_guard}`，前端来源标签增加"语义"。
- 验证：`RouteIntentSeedsTest`、`SemanticRouteClassifierTest`、`KbRoutePlannerTest`（含语义门禁 `semanticEligibilityFollowsL0`）、迁移后的 `KbRouteOrderingTest` 共 29 项通过；全仓 577 项测试与前端 `npm run build` 通过。本机已安装 `qwen3-embedding:0.6b`（`D:\ollma`），confident 口径离线门槛通过，见 6.1。
- 本机全链路实测（2026-11-15，临时索引 `payroll-route-intents-verify`，验证后已删除）：直接调用 `SemanticRouteClassifier.start()`，90 条种子 2.2s 写入并按 `version+model` 计数；3 条问句分别判为 `kb_read`(0.625) / `kb_admin`(0.733) / `other`(0.633)，均 `confident=true`，首条 216ms（连接预热）、后续 81–109ms。冷启动若嵌入超过 150ms 超时，该轮按 fail-open 回退 L2。

## 六、P2：能力约束与语义路由（预计 3.5 天）

### 6.1 语义路由（L1）

- 新增 ES 索引 `payroll-route-intents`，与知识库检索向量索引完全隔离（不共用索引、不共用模型配置）；
- 标签：`kb_read`、`kb_admin`、`other`（`payroll_probe` 不落地：薪酬在 L0 已一票终止，语义层根本不参与）；
- 每标签示例问句 30 条（3 标签共 90 条，见 `payroll-common/src/main/resources/routing/route-intents.json`，随内容版本号自举索引）；
- 判定：按标签取最高分后比较 top1/top2，`top1 ≥ min-score(0.50)` 且 `margin ≥ 0.05` 时直接采用，否则交给 L2（阈值定档见 D12）；
- 合并顺序（`KbRoutePlanner`）：薪酬 → L0 强制（`FORCE_EXPLICIT`/`FORCE_FOLLOW_UP` 不可被语义改写）→ L1 高置信语义 → L2 模型；高置信 `kb_admin` 可纠正 `FORCE_KEYWORD` 的误召回（如"删除这个知识库"），低置信或 `other` 不推翻关键词安全网；
- 语义层只决定 `need_kb`，不选目标智能体/流程；目标仍由 L2 或静态链路给出；
- 薪酬判定仍由 `PAYROLL_MARKERS` 先行拦截，语义层不参与、不可覆盖；
- 目标：在线判定 P50 < 100ms、P95 < 300ms；实测查询嵌入 qwen3 p50/p95=22/42ms（90 条确定性检索；nomic 为 23/28ms），超时上限 150ms，超时或异常一律交回 L2。

**离线门槛实测（2026-11-15，`scripts/route-intents-eval.mjs`，留一法）**

| 模型 / 处理 | kb_read | kb_admin | other | kb_read↔other 互相误判 | 高置信覆盖 / 准确率 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| `nomic-embed-text` 原始 | 66.7% | — | 26.7% | 23.3% | — | 未通过 |
| `nomic-embed-text` + `search_document:/search_query:` 前缀 | 56.7% | — | 36.7% | 25.0% | — | 未通过 |
| 同上 + 去均值（centering） | 60.0% | — | 40.0% | 20.0% | — | 未通过 |
| 去均值 + 原型分类（实验，未落地） | 100.0% | — | 36.7% | 13.3% | — | 未通过 |
| `qwen3-embedding:0.6b` 原始，min-score 0.55 | 93.3% | 100.0% | 83.3% | 1.7% | 50/90、100.0% | 整体口径未过 other，高置信口径通过 |
| `qwen3-embedding:0.6b` 原始，min-score 0.50（种子 2026-11-15.1） | 93.3% | 100.0% | 83.3% | 1.7% | 63/90、98.4% | 高置信口径通过（D12） |
| `qwen3-embedding:0.6b` 原始，min-score 0.50（种子 2026-11-16.1，当前默认） | 90.0% | 100.0% | 83.3% | 3.3% | 66/90、98.5% | 高置信口径通过（D13） |
| `qwen3-embedding:0.6b` + `Instruct:/Query:` 前缀 | 3.3% | 100.0% | 3.3% | — | 近乎全覆盖，但几乎全错 | 不采用（见下） |

**门槛口径修订（D11）**：整体口径把"运行时根本不会采纳"的低置信样本也计入分母，会低估语义层实际贡献；但只统计高置信样本又可能掩盖"只敢判简单样本"。因此 `--gate confident` 只对运行时采纳的样本（`top1 ≥ min-score` 且 `margin ≥ 0.05`）计门槛，同时要求各标签准确率 ≥ 90%、kb_read↔other 互误 ≤ 5%、整体覆盖率 ≥ 40%，整体指标继续打印留痕。阈值定档 0.50 后（D12）qwen3 高置信覆盖 63/90（70.0%），kb_read 20/30、kb_admin 27/30 全对，other 16/30 中 15 条正确（93.8%），高置信准确率 98.4%、互误 1 条（1.7%）；只有 1 条 `other` 样本跨过置信线后误判为 `kb_read`（0.521），其余 6 条误判仍在置信线以下，运行时直接回退 L2。

种子扩充到 2026-11-16.1（D13）后复测：高置信覆盖 66/90（73.3%），kb_read 23/30、kb_admin 27/30 全对，other 16/30 中 15 条正确（93.8%），高置信准确率 98.5%、互误 1 条（1.7%）。整体准确率由 92.2% 降到 91.1%，变化集中在被替换样本的邻近区域（如「员工体检安排在什么时候？」转为 `kb_read→other`，0.511 / margin 0.022，仍低于置信线，运行时回退 L2），不影响门槛。

**qwen3 前缀负结果**：按 Qwen3-Embedding 官方建议在查询侧加 `Instruct: 判断用户问题属于…Query:` 后，几乎所有问句被拉到 `kb_admin`（分数 0.56–0.66、margin > 0.05，属于"高置信的错误"）。原因是这段指令本身是祈使句，在 Ollama `/api/embed` 的语义场里压过了短问句；因此**查询侧不加任何指令**，脚本保留 `--prefix qwen3` 仅用于复现该对照，不得在生产配置使用。

**结论与开启条件**：`nomic-embed-text` 是 768 维英文检索模型，短中文问句的余弦被压在 0.8–1.0，无法区分三类意图，**不复用知识库检索模型**（D9）；语义路由使用独立模型 `qwen3-embedding:0.6b`（1024 维，本机已装于 `D:\ollma`）。按 confident 口径门槛通过（`--gate confident`，`min-score` 默认 0.50，见 D12），ES 建索引 + 检索往返校验与内存计算一致（种子 2026-11-16.1：82/90）：

```
node scripts/route-intents-eval.mjs --model qwen3-embedding:0.6b --gate confident
node scripts/route-intents-eval.mjs --model qwen3-embedding:0.6b --gate confident --es http://localhost:9200 --seed
```

`enabled` 仍默认 false；开启需显式设置 `PAYROLL_ROUTER_SEMANTIC_ENABLED=true`（或 `payroll.router.semantic.enabled=true`），并按上述两条命令复跑门槛。门槛未通过时不得开启语义层；关闭或初始化失败（种子缺失、嵌入不可用、ES 不可达、低置信）都回退 L0+L2，行为与 P1 一致。

**运行时实测（2026-11-15，8100 实例，仅设 `PAYROLL_ROUTER_SEMANTIC_ENABLED=true`，min-score 走默认 0.50，L0 走默认 `enabled=false`）**：启动日志 `minScore=0.5`，`/internal/route/preview`（已按 v10 传入 L0，并返回 `l0{decision,source,action_guard}`）——「年假可以跨年结转吗？」→ `l0=FORCE_KEYWORD(DISABLED)`、`kb_read` 0.526 / margin 0.133 / `need=true, source=force`（0.55 时语义漏检，但关键词仍兜住）；「今天天气不错，随便聊聊」→ `l0=NONE`、`other` 0.721 / `need=false, source=semantic`；「知识库怎么新增一个目录？」→ `l0=FORCE_KEYWORD`、`kb_admin` 0.715 / `need=false, kind=admin`；「怎么查看上个月的工资条？」含薪酬词，`payroll=true`、语义层"未执行"、`need=false`，确认薪酬不进入语义层。另以 `PAYROLL_CHAT_KB_POLICY_ENABLED=true` 起一次实例验证 L0 门禁：「请按知识库文档回答：报销流程是什么？」→ `l0=FORCE_EXPLICIT`、语义"未执行"、`need=true, source=force`；「删除这个知识库」→ `l0=FORCE_KEYWORD(action_guard=true)`、`kb_admin` 1.000、`need=false, kind=admin, source=semantic`，即 D10 的"管理操作纠偏"在预览中可复现。

**种子扩充复测（2026-11-16，8100 实例，种子 2026-11-16.1）**：启动日志确认 `版本 2026-11-16.1、样本 90、种子写入 2295ms`、`minScore=0.5`。预览结果——「报销制度在哪个知识库能查到？」→ `l0=FORCE_KEYWORD(DISABLED)`、`kb_read` 1.000 / margin 0.407 / `need=true, kind=read, source=force`（原为误判 `kb_admin`，边界已消除）；「差旅标准在哪查？应该看哪个库？」→ `kb_read` 1.000 / `need=true`；「员工体检安排在什么时候？」→ `l0=NONE`、`kb_read` 1.000 / `need=true, source=semantic`（在线为种子原句命中；留一法下该句被判 `other` 且低于置信线，运行时回退 L2）；「年假可以跨年结转吗？」0.526、「今天天气不错，随便聊聊」`other` 0.721、「知识库怎么新增一个目录？」`kb_admin` 0.715、「怎么查看上个月的工资条？」薪酬短路——四类行为与扩充前一致。

**已知边界（已消除）**：旧种子中 `kb_admin` 只有「帮我建一个知识库」「删除这个知识库」这类含"知识库"的样本，`kb_read` 侧缺少"在哪个知识库能查到"的措辞，导致「报销制度在哪个知识库能查到？」被判 `kb_admin`(0.593, margin 0.087)，叠加 D10 会取消 `FORCE_KEYWORD` 召回。v11 按 D13 在 `kb_read` 侧替换 3 条弱样本为定位型问句（哪个知识库/哪个库/在哪查），留一法下该句已判为 `kb_read` 且不再出现于误判明细；在线命中种子原句 1.000。管理操作句仍全判 `kb_admin`（27/30 高置信全对），D10 的纠偏规则未受影响。

### 6.2 能力声明替代"流程置空"

- `AgentDefinition` / 流程定义新增可选 `capabilities`，如 `["kb:read","calc","video"]`；
- 未声明时按节点推断：流程含 KB 节点即视作具备 `kb:read`，避免存量配置失效；
- 选择顺序（`need_kb=true`）：会话绑定且具备 `kb:read` 的流程 → 具备 `kb:read` 的智能体 → 含 KB 角色的团队模板 → `PayrollKbAgent`；
- 删除 `447-454` 的"流程置空"补丁，改由能力过滤完成同样效果。

### 6.3 检索下沉为工具（L3）

- `kb_search` 只对 `capabilities` 含 `kb:read` 的智能体/流程节点注册（写工具永不注册），叠加现有 `payroll.kb.chat.team-enabled`；
- 提示词保留"回答制度/流程/条款前先调用 kb_search"；
- 作用：当 L1/L2 都判"无需检索"但模型自查认为需要时，仍可在链路内补一次检索，形成 Self-RAG 式兜底；
- 额度仍受 `KbSearchBudget`（每轮上限 3 次、同参复用）约束，不新增成本路径。

### 6.4 删除的旧逻辑

| 旧逻辑 | 处理 |
| --- | --- |
| `KbChatIntent` 白名单准入（A5） | **保留**，长期作为确定性兜底。成本接近零，且是语义层/模型层都失效时唯一的召回保证；仅当 P3 中 `kb_synonym` ≥95% 且 `kb_negative` ≤2% 连续两个版本后再评估删除 |
| `KbRoutingPolicy` 的 `FORCE_KEYWORD` | **保留**，与语义层取"或"（6.1） |
| `PayrollChatService.java:447-454` 流程置空 | 删除，改由能力过滤完成同样效果（5.6 步骤 4） |
| `kbDirect` 的"制度类绕过团队"硬编码 | 改为"团队不具备 kb:read 时才绕过" |

`PAYROLL_MARKERS` 永不变更、永不删除。

## 七、P2.5：薪酬语义路由（影子 → 缓存，预计 2.5 天）

前提：P2 非薪酬语义路由稳定、P3 薪酬金标基线已产出。目标是在**不改变薪酬功能**的前提下，把 LLM 路由逐步替换为语义命中。语义层全程不产生新的决策，只复用既有决策。

**前置可行性（0.5 天，只读日志，不写代码）**：统计现有薪酬轮次的路由分布——若前 3 个桶覆盖不足 50% 的轮次，则"缓存命中率 ≥70%"不可达，必须先调桶粒度或下调目标值，再决定是否进入 P2.5。

实施顺序：P0 → P1 → P2 → P3 → P2.5；P2.5 依赖 P3 的薪酬金标与影子比对脚本，编号仅表示紧邻 P2。

### 7.1 三档开关

`payroll.router.semantic.payroll-mode: off | shadow | cache`，默认 `off`，启动期配置。

| 档位 | 行为 | 行为变化 |
| --- | --- | --- |
| `off` | 薪酬完全不进语义层 | 与今日完全一致 |
| `shadow` | 语义分类照常执行，结果只落日志/指标/`routing_json`，LLM 路由照常调用 | 零（构造上不参与决策） |
| `cache` | 语义命中高置信桶且缓存有效时复用既有决策，不再调用 LLM 路由；未命中仍走 LLM 路由 | 仅"同桶复用"，受准入一致率约束 |

### 7.2 薪酬语义桶

桶是缓存的复用单位，粒度必须与"决策可复用"匹配：

| 桶 | 典型问题 | 期望决策 | 允许缓存 |
| --- | --- | --- | --- |
| `payroll_calc` | 核算 EMP001 的工资、重跑 10 月批次 | pipeline / team | 是 |
| `payroll_query` | 张三上个月实发多少、批次进度 | 数据查询智能体 | 是 |
| `payroll_policy` | 个税专项附加怎么扣、社保基数规则（含薪酬词，仍走薪酬链路） | 政策智能体 / pipeline | 是 |
| `payroll_action` | 确认发放、审批通过、导入工资表 | 写操作链路 | **否**，每次必须真实决策 |

`payroll_action` 禁用缓存：写操作的确认、提案与审计必须来自当轮决策，避免复用陈旧授权上下文。

### 7.3 缓存键、介质与失效

决策键 = `bucket | catalog_signature | flow_binding | persona_binding | team_available | model_id`。

**介质必须先定**：本地 Caffeine 只适用于单实例开发环境；`payroll-backend` 与 agent-runtime 已支持多实例部署，生产必须用 Redis，键前缀 `payroll:router:cache:`，TTL 由 Redis 过期实现，避免各实例缓存不一致。Redis 不可用时 fail-open 回 LLM 路由，不回退本地缓存。

**不入缓存的问句**：含 4.1.1 追问指代特征、或长度 ≤ 20 字的问句，既不读也不写决策缓存——这类问句的语义依赖上一轮上下文，跨轮复用必然错误（`payroll_action` 桶另按 7.2 禁用）。

任一变化即失效，并计入 `router_cache_invalid_total{reason}`：

- 智能体目录变更（新增/删除/改描述/改状态）；
- 会话流程绑定、角色绑定变化；
- 动态团队可用性变化（`activeTeam.allowed` 结果）；
- 路由模型 id 或 instruction 版本变化；
- 桶样本重训（标签版本号变化）；
- TTL 到期，默认 7 天。

写入约束：同一桶连续 2 次由 LLM 路由给出**完全相同**的 `target + agent_id` 才允许写入缓存；
命中后仍每日抽检 N 条双跑（shadow 逻辑并行调用 LLM），不一致立即降级该桶。

### 7.4 命中与 fail-open

- 命中条件：`score ≥ min-score(0.50)`、`margin ≥ 0.05`、桶缓存存在且签名一致、未过期；
- 语义调用超时（`timeout-ms` 默认 150）或服务异常：直接回退 LLM 路由，`source=fallback`；
- 缓存命中时不调用 LLM，但复用缓存的 `target/agent_id/reason/confidence` 原值，保证
  `routing_json` 与 chip 展示与决策写入时一致；
- 若 LLM 路由本身处于回退态（如模型不可用），不允许写入缓存。

### 7.5 准入门槛（shadow → cache）

| 指标 | 门槛 |
| --- | --- |
| path / persona / agentIds / fallback 一致 | **100%，0 条差异**（见下方说明） |
| reason 文案、耗时等展示类差异 | 不计入不一致，但单独统计 |
| 薪酬金标 50 题 | 100% |
| "薪酬 → KB" 方向误判 | 0 |
| 样本量 | ≥ 500 条薪酬轮次或 14 天 |

初稿的"一致率 ≥99.5% + 出现 path/agent 差异即阻断"是自相矛盾的：500 条按 99.5% 允许 2 条不一致，而差异又要求 0 条，等价于 100%。本文统一为：**决策类差异（path/agentIds/persona/fallback）零容忍**；如需统计区间，用 Wilson 95% 下界 ≥99.5% 作为辅助表述，但准入判定仍以"0 差异"为准。

### 7.6 验收与回滚

- cache 开启后：金标 100%、缓存命中率 ≥ 70%、路由 P95 < 300ms、每日双跑不一致为 0；
- 命中率长期低于 70% 说明桶粒度过细，回 `shadow` 调整桶，不允许放大误判风险换命中率；
- 回滚：`payroll-mode=shadow` 或 `off` 并重启 runtime（与现有开关一致）；缓存数据可直接丢弃，
  不参与任何落库业务字段。

### 7.7 实施记录（2026-11-16，默认 off）

| 项 | 落地位置 |
| --- | --- |
| 三档开关 | `PayrollRouteMode`（off/shadow/cache），`payroll.router.semantic.payroll-mode` |
| 桶定义 | `PayrollRouteBucket`（calc/query/policy/action；action 永不缓存） |
| 种子 | `routing/payroll-route-intents.json` 版本 2026-11-16.1，20/15/15/10 共 60 条 |
| 独立索引 | `SemanticRouteClassifier.start(settings, resource, index, requiredLabels)`；薪酬索引 `payroll-route-intents-payroll`，与 KB 语义索引互不影响启停 |
| 决策缓存 | `PayrollRouteCache`：键 `payroll:router:cache:<bucket>:<sha256(键元组)>`，Redis 唯一介质，TTL 7 天；暂存键 `payroll:router:cache:pending:*` 负责"连续两次一致"门槛；`peek` 只读不写 |
| 失效指标 | `payroll.router.cache.invalid{reason=catalog|flow|persona|team|model}`，按签名首个变化维度归因 |
| 顾问层 | `PayrollRouteAdvisor`：off 直调 LLM；shadow 调 LLM 并比对 `peek` 结果；cache 命中即复用（`kbSource=cache`），未命中调 LLM，`routed()` 后按写入门槛记录 |
| 接线 | `PayrollChatService.route()` 仅对薪酬轮次过顾问；`InternalTurnController` 预览同样走顾问并返回 `router_cache`/`router_shadow` |
| 问句门槛 | `KbRoutingPolicy.routeCacheUnsafe` 复用 4.1.1 追问特征与 `follow-up-max-chars`（默认 20） |
| 测试 | `PayrollRouteCacheTest`、`PayrollRouteAdvisorTest`、`PayrollRouteSeedsTest` |

上线顺序不变：先把 `payroll-mode=shadow` 观察 ≥500 轮次（或 14 天），决策类差异为 0 后切 `cache`；
`cache` 档命中率 <70% 时回 `shadow` 调桶，不放大误判风险。

## 八、P3：评测与回归门槛（预计 2.5 天）

### 8.1 评测集

文件：`payroll-tests/src/test/resources/routing/kb-routing-cases.json`，共 150 条：

| 类别 | 条数 | 期望 |
| --- | --- | --- |
| `payroll` | 50 | 路由 path / persona / agentIds / fallback 与金标快照逐条一致 |
| `kb_direct` | 30 | 走检索，`kb_search` ≥1 次，有引用 |
| `kb_followup` | 20 | 上一轮带引用，追问无关键词仍走检索 |
| `kb_synonym` | 20 | 换词/换语序/跨语言表达仍走检索（P1 只要求 ≥85%） |
| `kb_negative` | 15 | 视频、写作、闲聊、引用后换话题不检索 |
| `mixed` | 15 | 薪酬+制度混合按薪酬处理；管理操作不检索 |

每条字段：`id, question, fixture{bound, last_cited, flow_id}, expect{policy, target, need_kb}`。

### 8.2 运行方式

- 纯规则/模型层：`KbRoutingEvalTest`（JUnit tag `eval`），默认跳过，`-Dpayroll.eval.routing=true` 执行；
- 全链路：`scripts/route-eval.sh` 调用 `/api/agents/route-preview`（P1 起返回 `kb` 决策），对运行期实例逐题比对；
- 薪酬金标：P0-0 先产出 `routing-baseline.json`（可用只读查询或临时脚本，P3-2 再固化为 `route-eval.sh`），此后每次改动比对，任何差异都算失败（不设容忍度）。

**确定性措施（否则金标必然抖动）**：`qwen2.5:3b` 输出不确定，不能对模型结果直接做逐字快照。

| 层 | 做法 |
| --- | --- |
| L0 规则（P0） | 纯函数，可直接快照逐条比对 |
| L2 模型路由（P1） | 固定模型 id + `temperature=0` + 固定 `seed`（若 Ollama 支持）；仍不稳定时改为单题 3 次多数投票，或对 path/agent 分布设阈值（如单题一致率 ≥2/3） |
| L1 语义（P2） | 固定嵌入模型与索引版本，结果确定，可快照 |

报告必须记录模型 id、Ollama 版本、`temperature`、索引版本，缺一不可复现。

### 8.3 指标门槛

| 指标 | 门槛 |
| --- | --- |
| 薪酬决策一致率 | 100%（50/50） |
| `kb_direct` 召回转检索 | ≥ 95% |
| `kb_followup` 召回转检索 | 100% |
| `kb_negative` 误检索率（含"引用后闲聊"） | ≤ 5%，且「生成视频/写文案」类 0 次误检索 |
| `kb_synonym` 召回转检索 | ≥ 85% |
| 路由 P95 延迟 | P2 后 < 300ms；P1 阶段以实测 p95 为准，不得高于 P1 前同口径 p95 的 120% |
| 每轮平均 `kb_search` 次数 | ≤ 1.2（防检索放大） |
| 薪酬影子一致率（P2.5 准入） | 决策类差异 0 条（7.5） |
| 薪酬缓存命中率（P2.5 验收） | ≥ 70%；低于则回 `shadow` 调整桶，不放大风险 |

指标比上次基线下降超过 2 个百分点即阻断合入；薪酬项零容忍。

### 8.4 实施记录（2026-11-16）

| 项 | 落地方式 |
| --- | --- |
| 评测集 | `payroll-tests/src/test/resources/routing/kb-routing-cases.json`，150 条，六类结构与 8.1 一致 |
| 单测门控 | `KbRoutingEvalTest`（tag `eval`）：`-Dpayroll.eval.routing=true` 才执行，校验逐条期望与分类门槛 |
| 全链路脚本 | `scripts/route-eval.sh` → `scripts/route-eval.mjs`，支持 `--gate-only`（只校验评测集结构）、`--limit`、`--web`；逐题调用 `/api/agents/route-preview` 并比对 |
| 预览夹具 | 预览请求新增可选 `kb_bound` / `kb_last_cited`，覆盖"上一轮带引用"的追问场景；不传时按未绑定、无引用计算 |
| 薪酬基线 | 与 `routing-baseline.json` 逐条比对 `target/agent_id`，任何差异计失败 |
| P2.5 指标 | 预览返回 `router_cache{mode,enabled,bucket,hit}` 与 `router_shadow{mode,ran,diff}`；脚本据此校验命中率 ≥70%、shadow diff 为 0；off/shadow 档未启用时跳过对应门槛 |
| 平均 `kb_search` | 预览不触发检索，脚本只提示该项需用对话回归（turn 模式）统计 |

运行示例（runtime 已启动）：

```bash
scripts/route-eval.sh --gate-only
scripts/route-eval.sh --web http://localhost:8080 --limit 20
# 校验 cache 档命中率：先以 PAYROLL_ROUTER_SEMANTIC_PAYROLL_MODE=cache 重启 runtime，再跑
scripts/route-eval.sh --web http://localhost:8080
```

## 九、配置项

| 配置 | 默认 | 阶段 | 说明 |
| --- | --- | --- | --- |
| `payroll.chat.kb-policy.enabled` | **false** | P0 | 总开关；false 回退纯关键词。先产出 50 题薪酬基线再开 |
| `payroll.chat.kb-policy.follow-up` | true | P0 | 追问延续（A4，含 4.1.1 特征判定） |
| `payroll.chat.kb-policy.bound-candidate` | **false** | P0 观测 / P1 生效 | 绑定作为候选（A6）；P0 只记录日志 |
| `payroll.chat.kb-policy.action-guard` | true | P0 | 行动类压制（A2，只压制 A4） |
| `payroll.chat.kb-policy.follow-up-max-chars` | 20 | P0 | 4.1.1 短问句长度阈值，P3 校准 |
| `payroll.router.need-kb-enabled` | false | P1 | 灰度开关；开启后使用 KB_SECTION 与 `need_kb` |
| `payroll.router.semantic.enabled` | **false** | P2 | 语义路由总开关；qwen3 confident 门槛已通过（6.1），默认仍关闭，开启前复跑门槛 |
| `payroll.router.semantic.min-score` | 0.50 | P2 | top1 阈值（≤0 或 ≥1 回落默认）；定档依据见 D12 |
| `payroll.router.semantic.margin` | 0.05 | P2 | top1-top2 差距（≤0 或 ≥1 回落默认） |
| `payroll.router.semantic.index` | payroll-route-intents | P2 | 意图索引，按 `label_version + model` 过滤 |
| `payroll.router.semantic.timeout-ms` | 150 | P2 | 在线嵌入超时，超时 fail-open 交回 L2 |
| `payroll.router.semantic.seed-timeout-seconds` | 60 | P2 | 启动时种子写入超时（90 条分批，仅首次或版本变更） |
| `payroll.router.semantic.es-url` | 继承 `payroll.memory.es-url` | P2 | 与知识库共用集群、独立索引 |
| `payroll.router.semantic.es-username` / `-password` | 继承 `payroll.memory.*` | P2 | 凭据只从环境变量注入 |
| `payroll.router.semantic.embedding-base-url` | http://localhost:11434 | P2 | Ollama 地址 |
| `payroll.router.semantic.embedding-model` | qwen3-embedding:0.6b | P2 | 独立于知识库检索模型；nomic 已实测不达标 |
| `payroll.router.semantic.embedding-dim` | 1024 | P2 | 必须与模型输出维度一致，否则种子写入失败 |
| `payroll.router.semantic.payroll-mode` | off | P2.5 | 薪酬语义三档：off/shadow/cache |
| `payroll.router.semantic.payroll-index` | payroll-route-intents-payroll | P2.5 | 薪酬桶独立索引，与 KB 语义索引隔离 |
| `payroll.router.semantic.payroll-cache-ttl-days` | 7 | P2.5 | 薪酬决策缓存有效期（Redis） |
| `payroll.kb.chat.team-enabled` | true | 现状 | 团队节点检索开关，保持不变 |

## 十、可观测性

- 日志：`[知识库][路由] policy=<source>，need_kb=<bool>，kind=<read|admin|none>，scope=<session|all>，candidate=<0|1>，question=<120 字>`；
- 指标：`kb_route_total{source}`、`kb_route_override_total{type}`、`kb_route_fallback_total`、`kb_route_action_guard_total`、`kb_route_candidate_total{outcome=hit|miss|flow}`、`router_semantic_seconds`；
- P2.5 指标：`router_semantic_total{label,mode}`、`router_semantic_agree_total` / `router_semantic_disagree_total`、`router_cache_hit_total{bucket}`、`router_cache_invalid_total{reason}`；
- 落库：`chat_messages.routing_json` 增加 `kb` 块（P1），导出 Markdown 同步展示判定来源；
- 追溯：`kb_search` 工具 trace 不变；路由判定与工具调用可按 messageId 关联排查。

## 十一、数据兼容与回滚

- `routing_json` 只增字段，旧消息 `kb=null`，读写双向兼容；
- `ChatContextStore.lastAssistantCited` 为 default 方法，其他实现（CLI/noop）无需改动；
- 每阶段独立开关，按 P3→P0 反向关闭即可回退，不需要数据迁移；
- P2 删除的代码（仅流程置空补丁）随开关保留一个版本周期后再移除；关键词白名单不删除。

## 十二、风险与非目标

风险：

| 风险 | 缓解 |
| --- | --- |
| qwen2.5:3b 不遵守 JSON/字段，或延迟波动（实测 8.2s 为**单次样本**） | 双 instruction、字段缺省视为 false、低置信度回退、P2 语义层前置；P1 先测 p50/p95 与解析失败率再决定是否放开非薪酬轮次 |
| 追问延续覆盖用户已绑定的流程 | A2 行动词压制 + 4.1.1 追问特征（指代词/长度）+ `follow-up` 开关 |
| 引用后的闲聊/换话题被强行改道 | A4 必须同时满足追问特征；`kb_negative` 评测集包含"引用后闲聊"用例 |
| 绑定被当作"强制检索"导致流程问题被劫持 | P0 `CANDIDATE` 只观测不改道；P1 先检索、0 命中回落（4.2.1） |
| 语义层误判污染薪酬 | 薪酬判定前置且语义层不参与；P3 薪酬金标零容忍 |
| 上一轮引用查询放大 | 仅非薪酬且通过追问判定时执行一次；确认 `(session_id, message_id)` 索引（H2 `PayrollDatabase.java:128`、MySQL `V1__payroll_core.sql:59` 均已存在） |
| 会话 26 的 `series_ids=[]` | 绑定写路径本身是通的（`ChatSessionController` 150-189 → `H2ChatStore:717`），更可能是该会话从未绑定；A4 不依赖绑定，A6 才有影响，属独立问题，需先确认再谈"跨角色丢失" |
| P1 执行顺序重组引入行为变化 | 先以 `need-kb-enabled=false` 提交一版行为等价重构，再用开关叠加 `need_kb`（5.6） |
| 非薪酬轮次路由调用增多导致延迟上升 | 路由调用前置后需实测；超阈值保留"已绑定非 KB 流程不调用"的短路 |
| 决策缓存多实例不一致 | 生产必须 Redis，键前缀 `payroll:router:cache:`；Redis 不可用 fail-open 回 LLM，不落本地缓存（7.3） |
| 薪酬桶粒度过粗导致复用错误决策 | 同桶连续 2 次一致才写入 + TTL + 每日双跑抽检；`payroll_action` 桶禁用缓存；追问/短问句不入缓存 |
| 目录/流程变更未使缓存失效 | 目录、流程、角色、团队可用性、模型、标签版本全部纳入键，变更即失效并计指标 |
| 影子采集带来额外嵌入调用 | 采样率可调，嵌入成本远低于 LLM 路由；不影响回答链路耗时 |

非目标：

- 不做模型微调或独立分类模型训练；
- 不做多租户自定义关键词；
- 不修改 `kb_search` 契约、引用采集与权限模型；
- 不修改薪酬核算、审批、写操作确认链路；
- 不把检索结果写入向量记忆。

## 十三、任务清单

| ID | 任务 | 阶段 | 预估 |
| --- | --- | --- | --- |
| P0-0 | 50 题薪酬基线与现有路由 p50/p95、JSON 失败率采集（`enabled=false`） | P0 | 0.3 天 |
| P0-1 | `KbRoutingPolicy`（A2 只压制 A4、A4 追问特征）+ 表驱动单测 | P0 | 0.4 天 |
| P0-2 | `lastAssistantCited` 存储方法与索引核查 | P0 | 0.2 天 |
| P0-3 | `PayrollChatService` 接线（仅 FORCE；`candidate` 只落日志） | P0 | 0.2 天 |
| P0-4 | 路由日志、开关与回滚验证 | P0 | 0.3 天 |
| P1-0 | **执行顺序重组（行为等价，先做）**：L0 → 流程解析 → route → 能力过滤 → 团队门禁 | P1 | 0.5 天 |
| P1-1 | 双 instruction 与 `need_kb/kb_kind` 解析 | P1 | 0.5 天 |
| P1-2 | `RouteDecision` / `ChatRouting.kb`：4+4 处构造点、前端解析与导出 | P1 | 0.8 天 |
| P1-3 | 四处分支改读决策对象 + 团队门禁 + 无 `kb:read` 时的显式改道 | P1 | 0.6 天 |
| P1-4 | 路由预览返回 KB 决策；顺序/门禁/改道回归与 p95、失败率门槛 | P1 | 0.4 天 |
| P2-1 | 能力字段、推断与 Nacos/流程模型兼容 | P2 | 1 天 |
| P2-2 | 选择器改造，删除流程置空补丁 | P2 | 0.5 天 |
| P2-3 | 语义标签 3×30 条 + 离线评估 + ES 索引与灰度（**已实施 2026-11-15，默认关闭；qwen3-embedding confident 门槛通过**） | P2 | 1.2 天 |
| P2-4 | `kb_search` 按能力注册 + 提示词 | P2 | 0.5 天 |
| P2.5-0 | 只读日志：薪酬轮次桶分布与可达命中率 | P2.5 | 0.3 天 |
| P2.5-1 | 薪酬语义桶、影子采集与 `routing_json` 落库（默认 off→shadow） | P2.5 | 0.5 天 |
| P2.5-2 | Redis 决策缓存：键、TTL、六类失效原因、同桶双写确认 | P2.5 | 0.8 天 |
| P2.5-3 | 三档开关、fail-open、回滚与 `payroll_action` 桶禁用 | P2.5 | 0.3 天 |
| P2.5-4 | 一致率比对脚本与准入校验（决策类 0 差异、金标 100%） | P2.5 | 0.4 天 |
| P3-1 | 150 条评测集与确定性金标（L0 快照 / L2 投票或阈值） | P3 | 1 天 |
| P3-2 | `KbRoutingEvalTest` 与 `route-eval.sh` | P3 | 0.7 天 |
| P3-3 | 门槛接入与评测报告 | P3 | 0.5 天 |

合计约 11.9 人日（初稿约 9.8 人日）。增量主要来自 P1-0 顺序重构、P1-2 的构造点/前端连带改动、P2-3 的标注与离线评估、P3-1 的确定性措施。

## 十四、实施前置与决策点

| ID | 决策点 | 建议 | 影响 |
| --- | --- | --- | --- |
| D1 | P0 总开关默认值 | **false**；先出 50 题薪酬基线再开 | 初稿默认 true 与"先基线后灰度"矛盾 |
| D2 | `CANDIDATE_BOUND` 行为 | P0 只观测；P1 才实现"先检索、0 命中回落" | 避免绑定会话闲聊全部落到 KB 智能体的 NO_HIT |
| D3 | A4 追问口径 | 指代/连续特征 + 长度 ≤20 字 + 排除强 OTHER；P3 用 150 题校准 | 决定误检索率与追问召回率的平衡 |
| D4 | P1 是否对所有非薪酬轮次调用路由 | 先测 p95 与 JSON 失败率；超阈值保留"已绑定非 KB 流程不调用"短路 | 直接影响首字延迟 |
| D5 | 决策缓存介质 | 生产必须 Redis（多实例）；本地开发才用内存 | 决定 P2.5-2 的实现与失效语义 |
| D6 | 影子一致率表述 | 决策类 0 差异；99.5% 仅作 Wilson 下界辅助表述 | 消除初稿自相矛盾 |
| D7 | A5 关键词白名单是否删除 | **不删除**，长期保留为确定性兜底 | 语义层失效时仍有召回保证 |
| D8 | 会话 26 的 `series_ids=[]` | 先确认是否只是"从未绑定"；写路径已通 | 决定是否需要独立的绑定修复任务 |
| D9 | P2 语义层是否复用知识库嵌入模型 | **不复用**。`nomic-embed-text` 在 3×30 留一法上 kb_read 56.7% / other 36.7%，远低于门槛；改为独立模型 `qwen3-embedding:0.6b`（1024 维），索引与 KB 向量隔离 | 避免用一个英文检索模型决定中文检索准入；模型未就位时语义层保持关闭 |
| D10 | `kb_admin` 与 A5 关键词的优先级 | 高置信 `kb_admin` 可纠正 `FORCE_KEYWORD`；`FORCE_EXPLICIT`/`FORCE_FOLLOW_UP` 与 `other` 均不可推翻关键词 | "删除这个知识库""同步文件夹结构"这类管理指令含 KB 词但不该检索；显式要求与追问延续仍以用户意图为准 |
| D11 | 离线门槛按整体口径还是按运行时采纳口径 | **两种口径都算，门槛用 confident 口径**：各标签高置信准确率 ≥90%、kb_read↔other 高置信互误 ≤5%、覆盖率 ≥40%；整体指标继续打印但不作门槛（`--gate overall` 仍可复跑旧口径） | 运行时只采纳高于置信线的判定，低置信样本一律回退 L2；把它们计入分母会低估语义层实际质量。qwen3 整体 other 仅 83.3%，7 条误判中 6 条低于置信线，高置信准确率 98.4%。覆盖率下限防止"只敢判简单样本" |
| D12 | `min-score` 定档 0.55 还是 0.50 | **0.50**：高置信覆盖由 50/90（55.6%）升到 63/90（70.0%），kb_read 覆盖 20/30（原 11/30）、kb_admin 27/30、other 16/30；高置信准确率 98.4%（原 100%），互误 1 条（1.7%） | 0.55 把「年假可以跨年结转吗？」(0.526, margin 0.133) 这类同义改写挡在门外，L2 又判 need=false，造成真实制度问题漏检；0.50 换来 kb_read 覆盖 66.7%，代价是 1 条闲聊样本（「附近有什么好吃的餐厅？」0.521）被判 kb_read。该误判只多注册一次 `kb_search` 能力，闲聊轮次提示词不会触发检索，成本可接受；若线上观测到闲聊误检索率上升，优先回调 `min-score` 而不是改种子 |
| D13 | 边界误判靠调阈值还是补种子 | **补种子**：保持 3×30 总数不变，在 `kb_read` 侧替换 3 条弱样本为"哪个知识库/哪个库/在哪查"的定位问句，版本号递增触发索引重建；阈值维持 0.50 | 定位型问句与 `kb_admin` 的"增删改库"是不同意图，靠单条闲聊误判回调阈值（D12）会牺牲同类制度改写召回；补种子只增不减、留一法可验证泛化。若扩充后仍误判，再考虑把 `kb_admin` 的管理动作词（新建/删除/重命名）作为 L0 级纠偏条件，而不是继续调 `min-score` |

前置条件（缺一不可开工）：

1. 具备可回放的 50 题薪酬问题集，且能采集 `routing_json`（现有能力已具备）；
2. 确认 Ollama 模型 id、版本与 `temperature` 可固定（P3 复现性）；
3. 确认团队模板中 `kb` 角色标签的现状（`PayrollAgentFactory.kbSearchNode` 依赖 `KB_CONFIG` 或 tag 含 `kb`）；
4. 确认生产是否多实例（决定 D5）。

## 十五、修订后的实施顺序

```
1. 文档修订（本文）
2. P0-0 薪酬基线（开关 false）
3. P0-1/P0-2/P0-3/P0-4 → 灰度开启 enabled，验证 19:40 追问场景
4. P1-0 执行顺序重构（行为等价，单独提交）
5. P1-1…P1-4 → need_kb 生效，验证顺序/门禁/改道三条回归
6. P2-1…P2-4（A5 保留）
7. P3 评测集与门槛（可与 P2 并行准备）
8. P2.5-0 可行性 → P2.5-1…P2.5-4
```

与初稿的差异：

- 新增 P0-0 基线前置，P0 开关默认关闭；
- P1 拆出独立的 P1-0 顺序重构，先等价、后加能力，避免"改了接线但仍不生效"；
- P2 不再删除关键词白名单，仅在满足 6.4 的可量化条件后另行评估；
- P3 从"最后做"改为"与 P2 并行准备"，因为 P2.5 依赖其金标；
- P2.5 增加 0.3 天的只读可行性，避免按 70% 命中率硬做。

## 十六、修订记录

| 版本 | 日期 | 变更 |
| --- | --- | --- |
| v1 | 2026-11-15 | 初稿（P0–P3 方案） |
| v2 | 2026-11-15 | 二轮评审修订：A2 改为只压制 A4；A4 增加追问特征与长度阈值；CANDIDATE 改为 P0 只观测、P1 先检索后回落；P0 开关默认 false 并新增 P0-0 基线；新增 5.6 执行顺序阻塞与 teamGate/改道/构造点范围；P2 保留关键词白名单、标注改为 3×30 离线先行；P2.5 改为决策类 0 差异、缓存介质定 Redis、追问不入缓存、新增可行性前置；P3 增加确定性措施；重写任务清单并新增第十四、十五节 |
| v3 | 2026-11-15 | P0-1/P0-2/P0-3 实施：新增 `KbRoutingPolicy`（A1–A7，`enabled=false` 与 `KbChatIntent.matches` 逐条等价）；`ChatContextStore.lastAssistantCited` 默认方法与 `H2ChatStore` 实现（走 `(session_id, message_id)` 索引）；`PayrollChatService` 接入决策与路由日志（`candidate` 只观测）；配置 `payroll.chat.kb-policy.*` 默认关闭。P0-0 基线仍待在运行环境执行 |
| v4 | 2026-11-15 | P0-0 基线执行：50 题全部薪酬词命中，旧判定 50/50 false、新策略 disabled 50/50 等价、enabled 50/50 `SKIP_PAYROLL`；路由预览 p50/p95=1099/1590ms、JSON 失败 0、target pipeline 19/agent 31；发现默认流程「AI视频」使实际路径 FLOW×50，基线未覆盖薪酬链路，且预览接口不感知默认流程。结论与重跑条件记入 4.5.1 |
| v5 | 2026-11-15 | P0-0 第二轮（清空默认流程后真实 turn 金标）：47/50 成功、3 条 3B 空回复；路径 PIPELINE 16 / AGENT 31；turn p50/p95=5443/34458ms；预览与实际 46/47 一致（#37 不一致）；0 引用、0 回退。默认流程已恢复、测试会话已清理。结论记入 4.5.1 |
| v6 | 2026-11-15 | P1 实施：`need_kb / kb_kind` 进入路由 schema 与链路选择；5.6 顺序重构（非薪酬轮次一律先路由，L0 FORCE 覆盖 need_kb，能力过滤替代流程提前置空）；显式改道与 `ChatRouting.kb` 留痕；预览与前端展示同步；新增路由/顺序/JSON 兼容测试。实施细节与验证范围记入 5.7 |
| v7 | 2026-11-15 | P2-3 实施：3×30 种子、ES 意图索引、语义分类器、`KbRoutePlanner` 优先级合并与预览/前端展示，默认关闭；离线评估证明 `nomic-embed-text` 不达标（kb_read 56.7% / other 36.7% / 互误 25.0%，去均值后仍 60.0%/40.0%），改为独立模型 `qwen3-embedding:0.6b`（D9）；明确 `kb_admin` 对 `FORCE_KEYWORD` 的纠正边界（D10）；新增 5.8 实施记录与 6.1 证据表 |
| v8 | 2026-11-15 | qwen3 门槛实测：安装 `qwen3-embedding:0.6b`（1024 维）后整体 92.2%（kb_read 93.3 / kb_admin 100 / other 83.3，互误 1.7%），高置信覆盖 50/90 且 100% 正确，延迟 p50/p95=22/42ms，ES 往返校验 83/90；`Instruct:/Query:` 前缀造成"高置信全错"，不采用；新增 `--gate confident` 与覆盖率下限（D11），门槛判定为通过，`enabled` 仍默认 false；Java 侧临时索引全链路实测通过（2.2s 写 90 条，3/3 高置信），同时移除 ES 7.17 已弃用的 `dense_vector.index` 显式声明 |
| v9 | 2026-11-15 | `min-score` 由 0.55 定档 0.50（D12）：高置信覆盖 63/90（70.0%），kb_read 20/30、kb_admin 27/30、other 16/30（93.8%），高置信准确率 98.4%、互误 1.7%，confident 门槛仍通过；8100 实例按默认 0.50 实测四类问句（制度读、闲聊、KB 管理、薪酬短路）符合预期；记录"哪个知识库能查到"措辞落入 `kb_admin` 的已知边界，留待 P3 扩充种子 |
| v10 | 2026-11-15 | 路由预览补齐 L0：内部预览接口注入 `payroll.chat.kb-policy.*` 配置，按"未绑定、无引用"计算 L0 并随响应返回 `l0{decision,source,action_guard}`；语义调用门禁抽成 `KbRoutePlanner.semanticEligible`，预览与 `PayrollChatService` 共用，L0 强制时预览不再展示语义标签；新增门禁单测 |
| v11 | 2026-11-16 | 种子扩充至 2026-11-16.1（D13）：`kb_read` 侧替换 3 条弱样本为"哪个知识库/在哪查"定位问句，修复「报销制度在哪个知识库能查到？」被判 `kb_admin` 的已知边界；留一法高置信覆盖 66/90（73.3%）、准确率 98.5%、互误 1.7%，ES 往返校验 82/90 一致，门槛仍通过；8100 实例复测定位问句为 `kb_read` 1.000 且 `need=true`，其余四类行为不变 |
