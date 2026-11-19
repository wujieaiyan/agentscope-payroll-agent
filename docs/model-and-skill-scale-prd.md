# 模型配置中心与技能规模化 PRD

| 项目 | 内容 |
| --- | --- |
| 版本 | v0.6（评审修订；D1–D3、D5、D6、D8 已确认） |
| 日期 | 2026-11-20 |
| 状态 | 规格评审中，未开始编码 |
| 关联文档 | `docs/dev-team-prd.md`（研发域，编码面模型）、`docs/dynamic-agent-team-spec.md` |
| 范围 | 管理台可配置模型连接（Qwen / DeepSeek / OpenAI 兼容等）；单智能体绑定大量技能时的加载与选择效率 |

> **v0.6 评审修订**：① 技能侧改为自有扩展接口 `PayrollSkillCatalog`（外部 harness 接口不可改）；② 新增 `payroll-agents.json` 兼容发布顺序；③ 技能预算改为扣减式并取实际 `contextWindow`；④ 目录改为绑定范围 + 关键词 top-K 收敛（D8 定稿）；⑤ 新增出网 SSRF 策略；⑥ 预加载改贪心填充；⑦ 指纹与管理台列表改 manifest；⑧ Nacos 并发写明最后写入者胜；⑨ 轻量面默认复用控制面；⑩ 补环境变量连接收养与凭证元数据；⑪ 新增技能加载质量门槛。

---

## 1. 背景与问题

### 1.1 用户诉求

1. **模型可配置**：希望在管理台页面里配置模型接入地址与模型（「模型路径」），支持千问、DeepSeek 等多家模型，而不是只能改环境变量后重启。
2. **技能规模化**：一个智能体绑定几十、几百甚至上千个技能时，加载是否会出现效率问题。

### 1.2 结论先行

- 诉求 1 当前**不具备**：模型只由环境变量决定，管理台没有模型配置页，智能体定义也没有模型字段；换模型必须改环境变量并重启。
- 诉求 2 当前**确有线性膨胀风险**，且不是单一瓶颈，而是三处叠加：
  1. **冷启动按技能逐个拉取**：列清单只拿到 ID，随后每个技能单独 `fetch("<id>.md")`，有文件包还要再取 `<id>.files.json`。
  2. **「限定范围」的仓库仍然全量加载**：按智能体过滤的 `ScopedSkillRepository.getAllSkills()` 先 `delegate.getAllSkills()` 再过滤。
  3. **技能选择是 O(N) 且静默丢内容**：工具组激活中间件对未命中技能逐个全量匹配；系统提示词预注入按 8k/24k 字符硬截断，超出部分静默丢弃。

因此本 PRD 分两条线：**模型连接注册表（可页面配置、可热更新、密钥不落明文）** 与 **技能两级加载（元数据目录 + 按需正文）**。

---

## 2. 现状证据

### 2.1 模型配置

| 位置 | 现状 |
| --- | --- |
| `payroll-agent/src/main/java/io/agentscope/examples/payroll/agent/PayrollAgentFactory.java:1354-1367` | `buildModel` 只识别 `ollama:` 与 `dashscope:` 两个前缀，其余交给 `ModelRegistry.resolve`；没有统一连接对象 |
| 同文件 `:64`、`:1369-1376` | Ollama 地址与生成参数来自 `OLLAMA_BASE_URL`、`PAYROLL_OLLAMA_NUM_CTX`、`PAYROLL_OLLAMA_NUM_PREDICT`、`PAYROLL_OLLAMA_TEMPERATURE` |
| 同文件 `:1358-1364` | DashScope 只从 `DASHSCOPE_API_KEY` 读密钥，缺失直接抛错；没有页面入口 |
| 同文件 `:1260-1318` | 编码面独立模型 `PAYROLL_DEV_MODEL`，上下文下限 32768（`DevModelSettings.MIN_CONTEXT`），缺失时写任务直接失败 |
| 同文件 `:1010`、`:1249` | 控制面 `sharedModel()` 单例，缓存键只有模型串（`sharedModelId`） |
| 同文件 `:1442-1460`、`:1490-1520` | 仅对 Ollama 做 `/api/tags` 校验与 `/api/show` 能力探测 |
| `payroll-common/src/main/java/io/agentscope/examples/payroll/domain/AgentDefinition.java:11-27` | 字段为 id/name/avatar/description/tags/skillIds/visibility/status/createdBy/createdAt/updatedAt/capabilities，**无模型字段** |
| `payroll-common/src/main/java/io/agentscope/examples/payroll/skill/NacosAgentRegistry.java:27` | 智能体列表整体存 Nacos `payroll-agents.json` |
| `payroll-backend/src/main/java/io/agentscope/examples/payroll/web/AgentAdminController.java:83-216` | 只有列表/元数据/激活与 CRUD，无模型相关接口 |
| `frontend/src/views/admin/AgentsView.vue:39-103` | 表单只有 `skillIds` 等字段，无模型选择 |
| `payroll-agent/src/main/java/io/agentscope/examples/payroll/team/TeamLlmClient.java:37` | 轻量意图/计划调用复用控制面模型（`createUtilityAgent`），没有可单独配置的路由模型 |

### 2.2 技能加载

| 位置 | 现状 | 规模影响 |
| --- | --- | --- |
| `payroll-common/.../skill/NacosSkillRepository.java:106` | `getSkill` 用 `knownIds().contains(name)`，`knownIds` 是 `List` | 每次取技能 O(S) 字符串扫描 |
| 同文件 `:124-133` | `getAllSkills` 对每个 ID 调 `cachedSkill` | 冷启动每次 TTL 到期后 **S 次 Nacos 拉取**（有文件包再 +S 次） |
| 同文件 `:191-230` | 每个技能独立 TTL 缓存、独立请求，无批量接口 | 1000 技能 ≈ 2000 次 HTTP，Nacos 与网络压力线性增长 |
| 同文件 `:51` | `cache` 为无界 `ConcurrentHashMap` | 全部技能正文常驻内存，S×平均正文大小 |
| `payroll-common/.../skill/SkillRepositories.java:136-140` | `ScopedSkillRepository.getAllSkills()` 先全量再过滤 | 即使只绑定 3 个技能，也触发全量加载 |
| `payroll-agent/.../agent/SkillToolGroupActivationMiddleware.java:98` | 解析技能名失败时遍历 `getAllSkills()` | 每次未命中调用 O(S)，且可能触发全量加载 |
| `payroll-agent/.../agent/PayrollAgentFactory.java:895-922` | 自定义智能体把技能正文拼进系统提示词；单技能 8000 字符、总 24000 字符上限 | 超过预算的技能**静默丢弃**（`:911-913 break`），模型不知道有这些技能 |
| 同文件 `:457-478` | 缓存指纹逐技能 `getSkill` | 每次智能体缓存重算 O(绑定数) 次仓库查询 |
| `payroll-common/.../skill/NacosSkillRegistry.java:133-148` | 管理台列表对所有技能生成摘要（含 `contentLength`） | 页面加载与技能总数线性相关，无分页 |
| `payroll-backend/.../web/SkillAdminController.java:88-102` | `GET /api/admin/skills` 返回全量列表 | 前端一次渲染全部技能 |

---

## 3. 需求一：模型配置中心

### 3.1 术语

- **模型连接（Model Connection）**：一条可复用的接入配置，包含提供方、地址、模型名、密钥引用与生成参数。
- **「模型路径」语义**（已确认，2026-11-20）：按「接入地址 + 模型名」（`baseUrl` + `model`）实现。不接受本地 GGUF/权重文件路径；模型由目标服务端预先加载（Ollama 需先 `pull`）。
- **三个使用面**：
  - 控制面（对话/团队/流程默认模型）
  - 编码面（研发域写代码，`PAYROLL_DEV_*` 等价物）
  - 轻量面（意图路由/计划 JSON，当前复用控制面）

### 3.2 提供方支持矩阵

| provider | 协议 | 默认 baseUrl | 典型模型 | 密钥来源 |
| --- | --- | --- | --- | --- |
| `ollama` | Ollama 原生 `/api/chat`、`/api/tags`、`/api/show` | `http://localhost:11434` | `qwen2.5:3b-instruct`、`qwen3:8b` | 无 |
| `dashscope` | DashScope 原生（框架 `ModelRegistry`） | 框架默认 | `qwen-plus`、`qwen-max` | `DASHSCOPE_API_KEY` |
| `openai-compatible` | OpenAI `/v1/chat/completions` | 由用户填写 | DeepSeek `deepseek-chat`、DashScope 兼容模式 `qwen-plus`、vLLM/LM Studio 任意模型 | 用户指定环境变量名 |

说明：

- DeepSeek、千问兼容模式、vLLM、LM Studio、Ollama 的 `/v1` 兼容层统一走 `openai-compatible`，不再为每家写分支。
- 所有 provider 的 `model` 都是服务端已加载的模型标识，不接受文件路径；Ollama 通过 `/api/tags` 校验是否已 `pull`，OpenAI 兼容端点以 `/models` 或最小对话确认可用。
- `provider` 决定连通性测试路径与能力探测方式；`openai-compatible` 用 `GET /models` 或最小一轮对话验证。
- 现有 `ollama:<model>` / `dashscope:<model>` 环境变量写法保留为**兼容映射**：解析时自动生成等价的内置连接。

### 3.3 连接字段

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `id` | 是 | 连接 ID，`[a-z0-9-]`，创建后不可改 |
| `name` | 是 | 展示名 |
| `provider` | 是 | 上表三类 |
| `baseUrl` | 视 provider | Ollama 可留空用默认；OpenAI 兼容必填 |
| `model` | 是 | 模型名；Ollama 需已 `pull` |
| `apiKeyEnv` | 二选一 | 环境变量名，如 `DEEPSEEK_API_KEY`；配置中不存密钥本体 |
| `credentialRef` | 二选一 | 页面录入密钥的凭证 ID，密文存数据库 `model_credential`（见 §5） |
| `contextWindow` | 否 | 声明值，用于校验（编码面要求 ≥32768） |
| `maxTokens` | 否 | 对应 `numPredict` |
| `temperature` | 否 | 默认 0.2（编码） / 0.3（控制面） |
| `timeoutSeconds` | 否 | 控制面默认 120，编码面默认 300 |
| `capabilities` | 否 | `tools`、`stream`、`thinking`、`vision`，由测试结果回填或手工声明 |
| `enabled` | 否 | 停用后不可被选用，但保留配置 |
| `updatedAt` / `updatedBy` | 自动 | 审计用 |

密钥来源（D2 已确认，2026-11-20）：一条连接二选一，**同时配置时保存被拒绝**。

1. **环境变量引用**：`apiKeyEnv` 只存变量名，适合运维托管密钥的环境。
2. **页面录入（加密入库）**：请求体提交一次明文密钥，服务端用 AES-256-GCM 加密后写入 `model_credential`，配置里只保留 `credentialRef`。

共同约束：

- 接口响应与日志**永不回显密钥**：列表/详情只返回 `credentialSource`（`env` / `stored`）与「已设置/未设置」。
- 凭证元数据：响应额外返回 `credentialCreatedAt`（录入/替换时间）与 `credentialLastUsedAt`（最后使用时间），不返回密文或明文，供页面展示与轮换判断。
- 加密密钥来自 `PAYROLL_SECRET_KEY`（Base64 的 32 字节）；缺失时禁止保存新密钥，已存凭证的连接标记不可用并 fail-closed，不回落其它密钥。
- 页面提交的 `apiKey` 为 write-only 字段；编辑时留空表示保持原密钥，显式「清除密钥」才删除记录。
- 环境变量派生的连接是**只读影子连接**：`PAYROLL_MODEL` / `PAYROLL_DEV_*` 生成的内置连接不可编辑、不可删除；页面提供「复制为可编辑连接」（复制 provider/baseUrl/model 生成新 id），避免用户改了不生效却无提示。

### 3.4 生效优先级（D6 已确认：按使用面分开）

| 使用面 | 优先级（高 → 低） |
| --- | --- |
| 控制面（对话 / 团队 / 流程） | 智能体级 `model` > 页面 `control` 默认 > `PAYROLL_MODEL` 兼容映射 > 内置默认 |
| 轻量面（意图 / 计划 JSON） | 页面 `utility` 默认 > `PAYROLL_MODEL` 兼容映射 > 内置默认 |
| 编码面（写代码） | `PAYROLL_DEV_*`（运维持有）> 页面 `coding` 默认（仅 env 未设置时）> 不可用（写任务直接失败） |

- **编码面环境变量优先**：一旦设置 `PAYROLL_DEV_MODEL`，页面 `coding` 默认只读并标注「已被环境变量锁定」；修改请求返回 `MODEL_ENV_LOCKED`，不静默覆盖。
- 智能体级 `model` 只影响控制面；编码面不接受智能体覆盖，保持 `DevTeamSettings` 单一来源。
- 智能体可只覆盖部分字段（`connectionId` + 可选 `model`/`numCtx`/`numPredict`/`temperature`/`timeoutSeconds`）。
- 编码面连接必须满足 `contextWindow ≥ 32768` 且 `capabilities` 含 `tools`，否则保存即拒绝。
- 写代码任务**不允许**回落到控制面模型（保持现有 `selectDevModel` 语义）。
- 轻量面默认**复用控制面连接**；只有显式配置独立 `utility` 连接才拆分。保存时若 control/coding/utility 中有 ≥2 条连接指向同一 `ollama` baseUrl，提示显存压力（本机 4GB 显存下不建议并行加载多个模型）。

### 3.5 存储

P0 选定（D1 已确认，2026-11-20）：连接元数据存 Nacos 配置 `payroll-models.json`（与 `payroll-agents.json`、技能同源，天然支持多实例与热更新）；**不新增连接配置表**。

D2 已确认（2026-11-20）：页面录入的密钥加密后写入数据库 `model_credential`，Nacos 中只保留 `credentialRef`。

```json
{
  "version": 1,
  "revision": 7,
  "defaults": { "control": "ollama-local", "coding": "deepseek-dev", "utility": "ollama-local" },
  "connections": [
    {
      "id": "ollama-local",
      "name": "本地 Ollama",
      "provider": "ollama",
      "baseUrl": "http://localhost:11434",
      "model": "qwen2.5:3b-instruct",
      "contextWindow": 32768,
      "maxTokens": 2048,
      "temperature": 0.3,
      "timeoutSeconds": 120,
      "capabilities": ["tools", "stream"],
      "enabled": true
    },
    {
      "id": "deepseek-dev",
      "name": "DeepSeek 编码",
      "provider": "openai-compatible",
      "baseUrl": "https://api.deepseek.com/v1",
      "model": "deepseek-chat",
      "credentialRef": "cred-deepseek-dev",
      "contextWindow": 65536,
      "maxTokens": 4096,
      "temperature": 0.2,
      "timeoutSeconds": 300,
      "capabilities": ["tools", "stream"],
      "enabled": true
    }
  ]
}
```

写入约束：

- 每次保存读取当前 `revision`，请求携带同一 `revision`；写入成功后 `revision + 1`；不一致返回 409 并提示刷新。**注意：Nacos publish 不是 compare-and-swap，读-比-写之间存在竞态窗口，Revision 只能降低并发覆盖概率，语义为「最后写入者胜」。** 所有变更写入 `AuditService` 保留前后摘要，冲突后以审计为准；不承诺强一致。
- 读取侧按 TTL（默认 30s）刷新，与智能体注册表一致；保存方主动失效本实例缓存。
- 变更历史不建表，写入既有 `AuditService`（`model.connection.*`、`model.defaults.update`），记录变更前后摘要，不含密钥。
- Nacos 未启用时，模型配置页只读展示由环境变量生成的内置连接，不提供保存（与智能体页在 Nacos 缺失时的行为一致）。

后续不再需要「连接配置入库」；若未来要支持密钥轮换的多版本共存，只扩展 `model_credential` 表即可，Nacos 结构不变。

### 3.6 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/admin/model-connections` | 列表（脱敏） |
| POST | `/api/admin/model-connections` | 新建；可携带 write-only `apiKey` 或环境变量名，二者只能有一个 |
| PUT | `/api/admin/model-connections/{id}` | 更新；`apiKey` 留空表示保持原值 |
| PUT | `/api/admin/model-connections/{id}/credential` | 设置或替换页面密钥；加密后入库，返回 `credentialRef` |
| DELETE | `/api/admin/model-connections/{id}/credential` | 清除已存密钥（同时清空 `credentialRef`） |
| DELETE | `/api/admin/model-connections/{id}` | 删除；被引用时拒绝，返回引用方 |
| POST | `/api/admin/model-connections/{id}/test` | 连通性测试，返回耗时、能力探测、错误分类 |
| PUT | `/api/admin/model-connections/defaults` | 设置 control / coding / utility 默认 |
| PUT | `/api/admin/agents/{id}` | 扩展 `model` 字段（现有接口扩展，不新增路径） |

错误语义（禁止静默回落）：

- 连接不存在/停用 → `MODEL_CONNECTION_UNAVAILABLE`
- 密钥环境变量未设置 → `MODEL_CREDENTIAL_MISSING`（提示变量名，不回显值）
- 已存凭证无法解密（密钥缺失/不匹配）或记录缺失 → `MODEL_CREDENTIAL_UNREADABLE`（fail-closed，不回落环境变量）
- 未配置 `PAYROLL_SECRET_KEY` 时提交页面密钥 → `MODEL_SECRET_KEY_MISSING`
- 编码面已被 `PAYROLL_DEV_MODEL` 锁定仍尝试改页面默认 → `MODEL_ENV_LOCKED`
- 连通失败 → `MODEL_UNREACHABLE`（含分类：DNS/超时/HTTP 状态）
- 能力不足 → `MODEL_CAPABILITY_UNSUPPORTED`（如 tools/上下文）

### 3.7 连通性测试

- Ollama：`GET /api/tags` 确认模型存在，`POST /api/show` 读能力。
- `openai-compatible`：`GET {baseUrl}/models`（不支持时回落为 1 token 的 `chat/completions` 探测）。
- `dashscope`：走框架模型构建后发最小一轮消息。
- 超时 5 秒；测试时按连接的密钥来源取值（环境变量或解密后的页面密钥），但**不把密钥写入测试结果**。测试结果写入审计 `model.connection.test`，只记录 provider/model/耗时/结果码。
- 页面显示「最近测试时间 + 结果」；连续失败不自动停用，只标红提示（避免把临时网络抖动变成配置丢失）。

### 3.8 热更新与缓存失效

- 连接配置按 TTL（默认 30s，可配）刷新，与智能体注册表一致。
- 缓存键必须包含连接版本：`connectionId|model|numCtx|numPredict|temperature|timeout`，任一变化即重建模型实例；沿用 `devModelKey` 的现有做法（`PayrollAgentFactory.java:1264-1292`）。
- 智能体缓存键增加模型引用（`PayrollAgentFactory.java:443-454` 的签名扩展）。
- 管理台保存后主动失效本实例缓存；其它实例靠 TTL 收敛（P1 可加 Nacos 变更监听）。

### 3.9 前端

新增「模型配置」页（路由 `/models`，侧边栏「系统」分组）：

- 顶部：三个使用面默认连接选择（控制/编码/轻量），带当前生效模型摘要；编码面在 `PAYROLL_DEV_MODEL` 存在时只读并标注「环境变量锁定」。
- 表格列：名称、提供方、模型、地址、密钥来源（环境变量 / 已加密保存 / 未设置）、能力标签、状态、最近测试、操作。
- 环境变量派生的连接显示「只读」徽标，操作列提供「复制为可编辑连接」；编辑只读连接直接提示由环境变量管理。
- 新建/编辑抽屉：提供方切换时联动字段；Ollama 提供「刷新模型列表」按钮（`/api/tags`）。
- 密钥区块二选一：
  - 「引用环境变量」：填写变量名，展示当前是否已设置（只显示存在性，不读值）。
  - 「在页面录入」：密码框 + 显示/隐藏切换；「显示」只作用于**本次新输入**的值，已存密钥永不回显；保存后显示「已加密保存」+ 录入时间，提供「替换密钥」「清除密钥」。
  - 未配置 `PAYROLL_SECRET_KEY` 时「在页面录入」置灰并提示。
- 测试按钮：行内触发，展示耗时与失败分类。
- 智能体页（`AgentsView.vue`）表单增加「模型」区块：
  - 默认「继承控制面默认」
  - 可选连接 + 覆盖模型名、上下文、最大输出、温度、超时
  - 展示「将用于：对话 / 团队 / 流程」；编码面模型不在此处配置

### 3.10 审计

新增动作：

- `model.connection.create|update|delete|test`
- `model.credential.set|clear`（只记录连接 ID 与操作人，绝不记录密钥）
- `model.defaults.update`
- `agent.model.change`

记录 id、变更前后摘要（不含密钥）、操作人、时间。对话请求的 trace 中记录实际使用的 `connectionId` 与 `model`，便于排障。

---

## 4. 需求二：技能规模化加载

### 4.1 目标态：两级加载

```text
第一级：技能目录（元数据）—— id / name / description / tags / version / contentHash / contentLength / hasResources
第二级：技能正文与文件包 —— 仅在模型调用 load_skill_through_path 或系统预注入选中时加载
```

原则：

1. **目录常驻、正文按需**：目录只存元数据并受条数与预算双重约束，正文不进入常驻内存。
2. **限定范围即真限定**：智能体只取自己绑定的技能，不做全量加载后过滤。
3. **预注入贪心填充**：按相关度排序后把正文装到预算为止，剩余进目录；不是「全预注入 / 全目录」二选一。
4. **目录必须收敛**：目录条目由「绑定范围 + 标签/关键词匹配」选出 top-K，规模上限与技能总数解耦，禁止全量穷举。
5. **截断必须可见**：任何因预算未注入的技能，在系统提示词与日志中显式列出，不静默丢弃。

### 4.2 仓库接入层（自有扩展接口，不改外部 harness）

`AgentSkillRepository` 来自外部 harness 包 `io.agentscope.core.skill.repository`，本仓库无权给第三方接口加方法。因此新增**本仓库自有**的扩展接口，由本地实现类同时实现：

```java
public interface PayrollSkillCatalog {
    List<SkillManifest> listManifests();                        // 只读元数据
    List<AgentSkill> getSkills(Collection<String> ids);         // 批量取正文
    Optional<SkillManifest> manifest(String id);                // 单条元数据
    Map<String, String> nameById();                             // id → name，供激活中间件 O(1)
}
```

调用侧用能力探测取用：`repository instanceof PayrollSkillCatalog`，取不到时回退现有逐条读取路径。**不得修改 `AgentSkillRepository` 本身，也不 fork harness。**

实现要点：

- `NacosSkillRepository` 增加 `implements PayrollSkillCatalog`
  - `knownIds` 改为 `Set`（修 `getSkill` 的 O(S) 扫描）。
  - `listManifests()` 一次性使用 `ConfigLister` 的返回条目（`NacosConfigClient.ConfigEntry` 已含 content），解析 frontmatter 生成 manifest，**不为 manifest 逐个 fetch 正文**。
  - 正文缓存改为有界 LRU（默认 256 条或 16MB，取先到者），未命中再单条 fetch。
  - 文件包元数据（`files.json` 大小/数量）在 manifest 中懒加载，不参与目录构建。
- `ScopedSkillRepository`（`SkillRepositories` 内部类）同样实现 `PayrollSkillCatalog`
  - `getAllSkills()` / `listManifests()` 只遍历 `allowed`，逐 id 直取。
  - `nameById()` 只包含允许集合，供激活中间件 O(1) 解析。
- `CompositeSkillRepository`
  - 合并时按 `id` 去重，保持首个来源优先（沿用现有语义），manifest 合并成本 O(S) 但只在 TTL 刷新时发生一次。

### 4.3 系统提示词策略（D5 已确认：按上下文扣减）

预算定义（必须使用**本次实际生效**的连接/智能体覆盖值，不读全局常量）：

```text
contextWindow     = 实际解析到的连接 contextWindow（智能体覆盖 > 页面默认 > env）
skillBudgetTokens = max(0, contextWindow − numPredict − reserved)
reserved          = max(4096, contextWindow × 30%)   // 系统提示 + 工具 schema + 历史窗口
token 估算：CJK 1 字 ≈ 1 token；ASCII 4 字符 ≈ 1 token
```

参考值：默认控制面 `contextWindow=8192`、`numPredict=1024` 时，`reserved=4096`，技能预算约 **3072 token**（约 3000 个中文字符）；`contextWindow=32768` 时预算约 **16.4k token**。

策略（贪心填充，不再是二选一）：

1. 对绑定技能按相关度排序：本轮用户消息关键词命中 > 标签命中 > 绑定顺序。
2. 依次把正文装入预算，装到下一个技能放不下为止；连一个都装不下时进入纯目录模式。
3. 未装入的技能全部进入目录，目录条数上限：
   - 纯目录模式：`max(50, contextWindow / 64)` 条（8192 → 128 条，32768 → 512 条）。
   - 预注入 + 目录混合：上限 100 条。
4. 目录仍超上限时，按「关键词命中 → 绑定顺序 → 名称排序」截断，末尾写明「另有 N 个技能未列出，可用 skill_search 查找」。

目录选择（P0 收敛策略，D8 最小形态已确认）：

- 技能来源限定为**当前智能体绑定的技能**（scoped），不做全局目录。
- 关键词匹配：用本轮用户消息与技能 `name/description/tags` 做轻量匹配（大小写不敏感的子串/分词命中），命中者优先进入预算与目录。
- 向量召回留待 P2，P0 不引入。

目录模式提示词示例（稳定模板，便于缓存）：

```text
## 可用技能目录（共 42 个，未加载正文）
- payroll-calc：工资核算规则与公式（v3, 12KB）
- tax-guide：个税专项附加扣除（v2, 8KB）
...
需要某个技能的完整步骤时，调用 load_skill_through_path(skillId, "SKILL.md")。
```

约束：

- 目录本身同样受 `skillBudgetTokens` 约束；放不下时按上述截断规则处理，并在末尾给出剩余条数。
- 8k 上下文下列不全目录属于预期行为；管理台对「绑定技能 > 20 且 `contextWindow < 16384`」的连接给出「建议提高上下文或减少绑定」提示（不再使用 8 个的硬阈值）。
- 被预算排除的技能写入日志 `[Skill] 未注入：<id列表>` 与 trace 字段 `skillsSkipped`，不再静默 `break`。
- 单技能正文先截断到 `min(8000 字符, 剩余预算)`，追加「已截断，完整内容请加载」。

### 4.4 工具组激活

`SkillToolGroupActivationMiddleware`：

- 注入 scoped 仓库的 `nameById` 映射，`resolveSkillName` 先查映射，未命中直接返回 null。
- 删除 `repository.getAllSkills()` 循环回退。
- 解析失败记录 `skill.activation.miss` 指标（技能 ID、智能体、次数），便于发现模型传错 ID。

### 4.5 缓存与失效

- 技能目录快照带版本号（所有 manifest 的 hash 合并），版本不变不重建提示词。
- 智能体缓存指纹（`PayrollAgentFactory.java:457-478`）改为使用 manifest 的 `contentHash`，避免每次 `getSkill` 拉正文；正文变化时 manifest hash 变化，指纹自然失效。
- 未绑定技能的变化**不应**导致智能体缓存失效。

### 4.6 管理台技能页

- 列表接口支持 `page`、`size`（默认 50）、`keyword`、`source`、`sort`。
- 页面顶部展示规模指标：技能总数、已绑定智能体数、正文总字节、目录构建耗时、缓存命中率。
- 智能体绑定选择器改为可分页搜索的穿梭框，不再一次渲染全部技能。

### 4.7 指标与基准

必须新增（Micrometer，沿用 Actuator 指标线）：

- `skill.catalog.build.duration`（按来源）
- `skill.content.load.count/summary`、`skill.content.cache.hit_ratio`
- `skill.prompt.injected.count`、`skill.prompt.skipped.count`
- `skill.activation.miss.count`
- `agent.cache.rebuild.count`（区分技能变化/模型变化）

基准脚本（`payroll-tests` 内 JUnit + 假仓库）：

- 规模点：10 / 100 / 1000 技能；单智能体绑定 3 / 50 / 200。
- 测量：目录构建耗时、单轮对话技能相关耗时、常驻内存增量、提示词字符数。

---

## 5. 数据模型与迁移

D1 已确认（2026-11-20）：**不新增连接配置表**；智能体 `model` 字段随 `payroll-agents.json` 一起存储（连接元数据在 `payroll-models.json`）。

```json
{
  "id": "payroll-expert",
  "skillIds": ["payroll-calc", "tax-guide"],
  "model": {
    "connectionId": "deepseek-dev",
    "model": "deepseek-chat",
    "numCtx": 32768,
    "numPredict": 4096,
    "temperature": 0.2,
    "timeoutSeconds": 300
  }
}
```

兼容性：

- 旧 JSON 无 `model` → 视为「继承默认」，行为不变。
- 旧环境变量仍生效：`PAYROLL_MODEL` / `OLLAMA_BASE_URL` / `PAYROLL_OLLAMA_*` / `PAYROLL_DEV_*` 自动映射为内置连接，优先级低于页面配置。
- 回滚：删除 `payroll-models.json` 即回到纯环境变量模式，无需回滚数据。

### 5.1 兼容发布顺序（强制）

`payroll-agents.json` 是跨版本共享状态，给 `AgentDefinition` 增加 `model` 字段存在「旧实例读到未知属性」风险：`NacosAgentRegistry` 当前使用默认 `JsonMapper`（`FAIL_ON_UNKNOWN_PROPERTIES` 开启），且 `load()` 解析失败会整份列表回退。

1. **第一步（先发布）**：`NacosAgentRegistry` 的 mapper 关闭 `FAIL_ON_UNKNOWN_PROPERTIES`（或给 `AgentDefinition` 加 `@JsonIgnoreProperties(ignoreUnknown = true)`）；`load()` 改为单条解析失败跳过该条并告警，不整份回退。发布并确认全量实例生效。
2. **第二步（后发布）**：发布能写 `model` 的版本。
3. 回滚安全：旧实例即使读到含 `model` 的 JSON 也不会失败；新实例读旧 JSON 因字段缺失走「继承默认」。

两步不得合并到一个版本发布。

D2 已确认（2026-11-20）：新增凭证表 `model_credential`，只存页面录入密钥的密文；连接元数据仍留在 Nacos。

```sql
CREATE TABLE IF NOT EXISTS model_credential (
  credential_id VARCHAR(64) PRIMARY KEY,             -- "cred-"+connection_id
  connection_id VARCHAR(64) NOT NULL,
  cipher_text   VARCHAR(2048) NOT NULL,              -- Base64(AES-256-GCM 密文+认证标签)
  nonce         VARCHAR(64) NOT NULL,                -- Base64(12 字节随机 IV)
  key_version   INT         NOT NULL DEFAULT 1,
  created_by    VARCHAR(64) NOT NULL DEFAULT '',
  created_at    TIMESTAMP   NOT NULL,
  updated_at    TIMESTAMP   NOT NULL,
  last_used_at  TIMESTAMP   NULL,
  CONSTRAINT uk_model_credential_connection UNIQUE (connection_id)
);
```

迁移：

- MySQL：`payroll-common/src/main/resources/db/migration/mysql/V15__model_credential.sql`（V10–V14 已被开发团队表占用）
- H2：`PayrollDatabase` 内联幂等 DDL（与其它表同风格）
- 新表无存量数据，不需要 `H2ToMysqlMigration` 迁移。
- 删除连接**不做跨存储事务**：先删数据库凭证，再删 Nacos 连接；Nacos 删除失败时凭证已删属可接受状态（连接无密钥即不可用）。启动时按「凭证存在但连接不存在」清理孤儿并告警。

加密参数：

- 算法 AES-256-GCM；每次加密生成 12 字节随机 nonce；认证标签 128 位；AAD 使用 `connection_id`，防止密文跨连接搬运。
- 主密钥来自 `PAYROLL_SECRET_KEY`（Base64 的 32 字节）；`key_version` 预留给轮换。
- 解密失败、主密钥缺失、记录缺失一律 fail-closed，返回 `MODEL_CREDENTIAL_UNREADABLE`，**不回落** `apiKeyEnv`。
- Web 与 runtime 两个进程都通过同一 `PayrollDatabase` 访问该表，且都必须注入同一 `PAYROLL_SECRET_KEY`；解密只发生在模型构建/连接测试时，明文密钥不写入任何缓存字段。

---

## 6. 安全

- 密钥有两种来源：环境变量名（`apiKeyEnv`）或数据库密文（`credentialRef` → `model_credential`），二者互斥。
- 页面密钥永不以明文落盘或回显；接口、日志、审计只出现「已设置/未设置」。
- `PAYROLL_SECRET_KEY` 不设默认值、不写日志；未配置时禁止保存新密钥。
- 日志与审计过滤 `apiKey`、`Authorization`。
- 连接测试与对话请求的异常消息统一截断，禁止把上游响应头（可能含 key 片段）写进用户可见错误。
- 技能目录模式不改变现有工具授权：`load_skill_through_path` 仍受智能体技能绑定范围限制，越界返回明确拒绝。

### 6.1 出网安全（SSRF）

`baseUrl` 由管理台自由填写，连接测试与实际推理都由服务端主动出网，必须约束：

- 仅允许 `http` / `https`，禁止 `file:`、`ftp:` 等协议。
- 默认禁止访问私网/回环地址（`127.0.0.0/8`、`::1`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`），但提供**可配置白名单**：
  - 本机 Ollama（`http://localhost:11434`、`http://127.0.0.1:11434`）默认放行；
  - 内网模型网关按部署显式加入白名单（如 `PAYROLL_MODEL_OUTBOUND_ALLOW`）。
- 禁止跟随跨主机重定向；`/test` 与推理共用同一出网策略，避免「测试通过、推理被拦」的割裂。
- 密钥只发往连接自身的 `baseUrl`，不随重定向流转；重定向目标与原始主机不一致时直接失败。
- 被拒绝的目标记录审计：目标地址、操作人、来源连接 ID。

---

## 7. 验收指标

### 7.1 模型配置

| 指标 | 目标 |
| --- | --- |
| 页面新建 Ollama 连接并测试成功 | ≤ 10 秒完成操作，测试结果可见 |
| 新建 DeepSeek（OpenAI 兼容）连接 | 环境变量或页面录入两种密钥均可测试通过，密钥不回显 |
| 页面录入密钥后落库 | 数据库仅存密文与 nonce，无明文；重启后仍可正常调用 |
| 未配置 `PAYROLL_SECRET_KEY` | 「页面录入」置灰；接口返回 `MODEL_SECRET_KEY_MISSING` |
| 密文被篡改或主密钥不匹配 | 返回 `MODEL_CREDENTIAL_UNREADABLE`，不静默换用其它密钥 |
| 编辑时留空密钥 | 保持原密钥不变；显式清除后才删除凭证记录 |
| 切换智能体模型 | 保存后下一次对话生效，无需重启 |
| 编码面模型不满足 32768 上下文 | 保存被拒绝，提示具体字段 |
| 配置错误 | 返回明确错误码，不静默使用其它模型 |
| 私网/回环地址（白名单外） | 保存或测试被拒绝并记录审计；本机 Ollama 默认放行 |
| 环境变量派生连接 | 页面只读不可改；「复制为可编辑连接」生成新连接并按优先级生效 |
| 凭证元数据 | 列表返回录入时间与最后使用时间，不含密文/明文 |
| 旧实例兼容 | 含 `model` 的 `payroll-agents.json` 被旧解析器读取不失败（按 §5.1 顺序发布） |
| 审计完整率 | 增删改测试与智能体改模型 100% 落审计 |

### 7.2 技能规模化

测试条件：1000 个技能，单智能体绑定 200 个。

| 指标 | 目标 |
| --- | --- |
| 技能目录构建（冷启动，含 Nacos 列表） | ≤ 1 次列表调用，P95 ≤ 500ms |
| 技能目录构建（TTL 命中） | P95 ≤ 20ms |
| 单轮对话技能相关额外开销 | P95 ≤ 50ms（不含模型生成） |
| 常驻技能正文内存 | 有界 ≤ 16MB（LRU 上限） |
| 系统提示词技能部分 | ≤ `contextWindow − numPredict − max(4096, contextWindow × 30%)`，超限可见 |
| 默认 8k 上下文模型（numPredict=1024） | 技能提示词不超过约 3072 token，不出现超窗 |
| 32k 编码面（numPredict=4096） | 技能预算约 16.4k token |
| 静默丢弃技能 | 0（必须显式列出） |
| 智能体只绑定 3 个技能 | 不触发全量加载（可用假仓库计数断言） |
| 技能管理页首屏（50/页） | ≤ 1s |
| 绑定 1000 技能时工具组激活 | 单次解析 O(1)，无 `getAllSkills` 调用 |

### 7.3 回归

- 现有 910 项测试全绿。
- Nacos 未启用时回落内置技能行为不变。
- 现有环境变量配置启动行为不变。

### 7.4 技能加载效果质量门槛

功能验收不够，需求二的效果必须在固定样本上量化。基准：`payroll-tests` 内 30 条真实对话样本 × 1000 技能目录 × 单智能体绑定 200 个。

| 指标 | 目标 |
| --- | --- |
| 本轮所需技能进入预注入或目录的比例 | ≥ 95% |
| 需要 `load_skill_through_path` 补加载的比例 | ≤ 30%（只统计确实用到技能的对话） |
| 技能误选（注入了无关技能正文）比例 | ≤ 10% |
| 目录模式下首 token 延迟相对纯预注入基线 | ≤ +20% |
| `skill.activation.miss`（模型传错 ID） | 每次运行 ≤ 1 次 |

未达标不得宣称需求二完成；向量召回（P2）作为不达标时的后续手段。

---

## 8. 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 目录模式下模型不主动加载技能 | 回答质量下降 | 目录条目带描述与大小；提示词明确「先加载再执行」；贪心预注入尽量多装技能，并以 §7.4 指标验收 |
| 模型能力声明失真（声称 tools 实际不支持） | 工具调用失败 | 连通性测试做一次真实工具调用探测（P1） |
| 页面配置覆盖生产默认 | 控制面误操作；编码面被覆盖后难以追责 | 控制面保存前显示「影响范围」；编码面 `PAYROLL_DEV_MODEL` 恒优先，页面修改返回 `MODEL_ENV_LOCKED` |
| 密钥经页面输入泄漏 | 安全事故 | 传输走 HTTPS；落库前 AES-256-GCM 加密；接口/日志/审计永不回显；页面只显示「已设置」 |
| `PAYROLL_SECRET_KEY` 丢失或未备份 | 已存密钥不可恢复，相关连接不可用 | 部署文档要求与数据库备份分开保存；连接不可用时 fail-closed 并提示重新录入 |
| 主密钥轮换导致旧密文无法解密 | 调用中断 | `key_version` + 双密钥解密窗口，后台重加密（P1） |
| Nacos 配置被并发覆盖 | 配置丢失 | 保存带 `revision` 校验（只能降概率）；语义为最后写入者胜，变更全量落审计可追溯，冲突后以审计为准 |
| 有界缓存导致频繁回源 | 延迟抖动 | LRU + 目录预取最近使用正文；指标监控命中率 |

---

## 9. 任务清单

### 9.1 P0（模型可用 + 技能不再线性膨胀，约 3 周）

**模型配置**

- M0-1 `ModelConnection` 记录、Nacos `payroll-models.json` 读写与 TTL 缓存、revision 校验（最后写入者胜）、环境变量兼容映射与只读影子连接。
- M0-2 `PayrollAgentFactory.buildModel` 改为按连接构建；控制面按「智能体 > 页面 > env」解析，编码面 `PAYROLL_DEV_*` 优先并锁定页面；缓存键含连接版本；`selectDevModel` 接入编码面默认连接与能力校验；轻量面默认复用控制面连接。
- M0-3a **兼容前置**：`NacosAgentRegistry` mapper 关闭 `FAIL_ON_UNKNOWN_PROPERTIES`（或 `AgentDefinition` 加 `@JsonIgnoreProperties`），`load()` 单条失败跳过而非整份回退；先于 M0-3b 发布。
- M0-3b `AgentDefinition` 增加 `model` 字段与校验；`NacosAgentRegistry` 序列化兼容。
- M0-4 `ModelConnectionAdminController` CRUD + defaults + test；审计与脱敏；凭证元数据（录入/最后使用时间）返回；环境变量连接「复制为可编辑连接」。
- M0-5 前端「模型配置」页 + `AgentsView` 模型区块 + 路由/菜单。
- M0-6 单测：连接解析、分面优先级、`MODEL_ENV_LOCKED`、密钥缺失、编码面上下文校验、热更新失效。
- M0-7 凭证加密存储：`ModelCredentialCipher`（AES-256-GCM + AAD）、`model_credential` 表、MySQL V15 迁移与 H2 内联 DDL、启动时主密钥校验、解密失败 fail-closed。
- M0-8 单测：加解密往返、篡改密文、缺失主密钥、凭证与 env 互斥、清除语义。
- M0-9 出网策略：协议白名单、私网/回环默认拒绝 + 可配置白名单（本机 Ollama 默认放行）、禁跨主机重定向；`/test` 与推理共用；拒绝记录审计。

**技能规模化**

- S0-1 新增自有扩展接口 `PayrollSkillCatalog`（**不改外部 `AgentSkillRepository`**），`NacosSkillRepository` / `ScopedSkillRepository` 实现，调用侧能力探测 + 回退；Set 查找、列表阶段生成 manifest、正文 LRU。
- S0-2 `ScopedSkillRepository` 只加载允许集合；`nameById` 只含允许集合。
- S0-3 `skillBlock` 改为贪心预注入 + 目录混合策略，按 `contextWindow − numPredict − reserved` 预算判定，移除静默 `break`，输出跳过清单。
- S0-4 `SkillToolGroupActivationMiddleware` 改为映射解析，删除 O(N) 回退。
- S0-5 智能体缓存指纹改用 manifest `contentHash`，不再逐技能 `getSkill`。
- S0-6 技能管理接口分页/搜索；前端分页与规模指标。
- S0-7 基准测试（10/100/1000 技能）与指标埋点。
- S0-8 `SkillPromptBudget` 计算器（CJK/ASCII token 估算、扣减式预算、贪心装填、目录条数上限）与阈值单测。
- S0-9 管理台技能列表改 manifest 数据源：`NacosSkillRegistry` 列表/详情不再全量解析正文与文件包，延迟到按需。
- S0-10 目录收敛：绑定范围 + 本轮关键词/标签 top-K 选择，禁止全量目录。

### 9.2 P1（体验与治理，约 2 周）

- M1-1 连接健康状态与最近测试结果展示；失败分类提示。
- M1-2 主密钥轮换（`key_version` + 双密钥解密窗口 + 后台重加密）。
- M1-3 配置版本号与并发写冲突提示；环境变量锁定开关。
- M1-4 智能体按技能标签/能力的绑定推荐。
- S1-1 `skill_search` 工具（名称/描述/标签检索目录），目录被预算/条数截断时作为兜底。
- S1-2 技能使用统计（加载次数、最近使用），驱动目录排序。
- S1-3 技能正文变化只失效对应智能体缓存，补回归测试。

### 9.3 P2（可选增强）

- P2-1 技能向量召回（描述嵌入 + 与对话意图匹配），取代纯目录。
- P2-2 本地 GGUF / llama.cpp 直接加载：**不在本期与 P1 范围**，仅作为远期独立评估（D3 已排除文件路径语义）。
- P2-3 按任务类型自动选模型（编码/审查/总结分别路由）。
- P2-4 技能依赖声明与版本兼容检查。

---

## 10. 待确认决策点

| 编号 | 决策 | 推荐 | 备选 |
| --- | --- | --- | --- |
| D1 | 模型连接存储位置 | **已确认（2026-11-20）：Nacos `payroll-models.json`**（与智能体/技能同源，零迁移，TTL 热更新） | 新增 `model_connection` 表（未采用） |
| D2 | 密钥形态 | **已确认（2026-11-20）：支持页面录入并加密入库**（`model_credential` + AES-256-GCM + `PAYROLL_SECRET_KEY`），与环境变量引用二选一 | 仅环境变量引用（未采用） |
| D3 | 「模型路径」语义 | **已确认（2026-11-20）：接入地址 + 模型名**（`baseUrl` + `model`） | 支持本地 GGUF 文件路径（未采用） |
| D4 | 提供方范围 | 首期 Ollama / DashScope / OpenAI 兼容三类 | 每家大厂单独适配 |
| D5 | 技能提示词预算 | **已确认（2026-11-20）：扣减式**，`contextWindow − numPredict − max(4096, 30%)`，贪心预注入 + 目录混合 | 固定 8 个 / 24000 字符（未采用） |
| D6 | 页面配置与环境变量优先级 | **已确认（2026-11-20）：按使用面分开**，控制面页面优先，编码面 `PAYROLL_DEV_*` 优先且页面只读 | 页面全局优先 / env 全局优先（未采用） |
| D7 | 技能目录真源 | 由 Nacos 技能配置派生缓存，不新增真源 | 独立 Nacos 目录 dataId |
| D8 | 技能目录收敛方式 | **已确认（2026-11-20）：P0 用绑定范围 + 标签/关键词 top-K；向量召回留 P2** | P0 直接引入向量召回（未采用） |

---

## 11. 附录：关键代码位置

- 模型构建与参数：`payroll-agent/src/main/java/io/agentscope/examples/payroll/agent/PayrollAgentFactory.java:64`、`:1354-1367`、`:1369-1376`、`:1456`、`:1496`、`:1010`、`:1249`
- 编码面模型：同文件 `:1260-1318`；`payroll-common/src/main/java/io/agentscope/examples/payroll/devteam/DevModelSettings.java:21-72`
- 智能体定义与注册：`payroll-common/src/main/java/io/agentscope/examples/payroll/domain/AgentDefinition.java:11`、`payroll-common/src/main/java/io/agentscope/examples/payroll/skill/NacosAgentRegistry.java:27`、`:34`（默认 mapper）、`:314`（整份列表解析）
- 智能体管理接口与前端：`payroll-backend/src/main/java/io/agentscope/examples/payroll/web/AgentAdminController.java:83-216`、`frontend/src/views/admin/AgentsView.vue:39-103`
- 技能仓库：`payroll-common/src/main/java/io/agentscope/examples/payroll/skill/NacosSkillRepository.java:51`、`:106`、`:124-133`、`:191-230`；`SkillRepositories.java:115-145`；`NacosSkillRegistry.java:40-49`、`:133-148`；`NacosConfigClient.java`（`ConfigEntry` 已含 content，manifest 无需逐条 fetch）
- 提示词预注入：`payroll-agent/src/main/java/io/agentscope/examples/payroll/agent/PayrollAgentFactory.java:119`、`:121`、`:457-478`、`:895-922`
- 工具组激活：`payroll-agent/src/main/java/io/agentscope/examples/payroll/agent/SkillToolGroupActivationMiddleware.java:90-104`
- 技能管理接口：`payroll-backend/src/main/java/io/agentscope/examples/payroll/web/SkillAdminController.java:88-102`
- 路由前端口：`frontend/src/router/index.js:16-31`、`frontend/src/views/admin/SkillsView.vue`
