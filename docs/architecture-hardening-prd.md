# 架构加固 PRD：配置一致性、对话执行面与依赖降级

| 项目 | 内容 |
| --- | --- |
| 版本 | v0.6（批次 1–6 已实现；批次 3/5/6 补充落地说明） |
| 日期 | 2026-11-22 |
| 状态 | 批次 1–2（`5297c6c`）、批次 4（`3816d02`）已推送 `feature/dev-team`；批次 3（O1–O7）、批次 5（W1–W6）、批次 6（X1–X7）已编码，离线全量测试与前端构建通过，未提交 |
| 关联文档 | `docs/model-and-skill-scale-prd.md`、`docs/three-module-split-spec.md`、`docs/chat-resume.md`、`docs/vector-memory-spec.md`、`docs/multi-node-deploy.md` |
| 范围 | Nacos 配置面读写一致性、技能缓存可靠性、对话执行面并发与依赖降级、模型与路由缓存正确性、运维基线、KB 解析/检索与集群可用性 |
| 输入 | 2026-11-21 架构评审（P0/P1/P2，证据见 §2）；2026-11-22 性能、高并发与可用性复核（见 §14）；v0.3 补齐探针契约、Redis 持久化探测、指标告警与前端契约；v0.4 记录批次 4（V1–V5）实现状态与代码入口 |

> 本文把评审结论转成可执行的批次。批次 1 为正确性缺陷，必须先做；批次 2 为容量与依赖韧性；批次 3 为运维与安全基线。§14 是 v0.2 新增的专项复核，新增批次 4（可用性 P0）、批次 5（并发与 KB P1）、批次 6（效率 P2），不改变批次 1–3 的范围与顺序。所有改动默认向后兼容，旧前端在未传 `revision` 时按“跳过校验 + WARN”运行。

---

## 1. 结论先行

| 批次 | 解决的问题 | 用户/运维可见变化 | 估算 |
| --- | --- | --- | --- |
| 批次 1（P0） | 配置读取失败被当成“空结果”；写操作基于 TTL 旧缓存；设置文档可能被整份重写丢键；智能体/流程切换可双双失效 | 并发编辑返回 409 并提示刷新；Nacos 抖动不再清空技能文件缓存；设置项不再被覆盖；切换失败有明确 503 | 1–2 人日 |
| 批次 2（P1） | 共享 4 线程无界队列；嵌入超时每轮固定拖 3s；路由每轮一次 LLM 且无缓存；换密钥模型实例不重建；Nacos 列表 200 条截断 | 高峰返回 429 而不是无限排队；依赖故障快速降级；同问路由命中缓存；改密钥下一轮生效；技能/配置超过 200 条仍完整 | 2–3 人日 |
| 批次 3（P2） | 设置分散多文档、文件缓存无上限、健康检查是静态值、无备份恢复、无成本闸门、无首改密 | 提供可运维的备份/恢复与真实健康探测；新用户首次登录强制改密；成本可限额 | 随迭代 |
| 批次 4（P0，§14） | 集群模式 Redis 故障会双跑、轮次中途丢事件即被判死、readiness 被 ES 拖垮、配额非原子且 TTL 短于轮次 | 集群故障快速失败而不是脑裂；Redis 抖动不误杀轮次；ES/Nacos 故障不摘流；配额不泄漏 | 2–3 人日 |
| 批次 5（P1，§14） | SSE 流占用执行线程、每轮一个 poller 裸线程、KB 查询全量加载向量、ingest 逐行写 | 单节点可承载的并发订阅提升一个量级；KB 延迟与内存不随库线性增长 | 4–5 人日 |
| 批次 6（P2，§14） | ES 大 bulk、解析整文件入内存、任务列表 N+1、降级事件总线 O(n²)、维护单线程 | 大批量与瞬时依赖故障下不出现尖刺 | 随迭代 |

不做：

- 不引入 Nacos v2 gRPC 长连接或配置监听；仍用 v1 OpenAPI + TTL。
- 不改对话轮次状态机与断点续传协议。
- 不做配置项的可视化版本历史（fingerprint 只用于并发校验）。
- 不做 K8s/HPA、多地域、MySQL 读写分离或 ES 集群运维（§14.14）。

---

## 2. 现状证据

### 2.1 配置面

| 位置 | 现状 | 影响 |
| --- | --- | --- |
| `payroll-common/src/main/java/io/agentscope/examples/payroll/skill/NacosConfigClient.java:93-149` | `list()` 所有失败分支都 `return List.of()`（`:120`、`:141`、`:144`、`:147`），签名不表达失败 | 调用方无法区分“没有技能”和“Nacos 不可用” |
| 同文件 `:100-101`、`:28` | 固定 `pageNo=1`、`pageSize=200` | 超过 200 条的技能/配置被静默截断 |
| `payroll-common/.../skill/NacosSkillRepository.java:242-255` | `rebuildCatalog` 的 `catch (RuntimeException)` 依赖 `lister.list()` 抛异常，但 `ConfigLister` 绑定的是不抛异常的 `NacosConfigClient::list` | 该类文档承诺的“失败沿用上次快照”实际不可达，失败时只剩内置技能 |
| `payroll-common/.../skill/NacosSkillRegistry.java:485-511`、`:504` | 每次成功 `load()` 都 `fileCache.clear()` | Nacos 一次抖动后文件包缓存全清；同时 `:113` 的 `ConcurrentHashMap` 无上限 |
| `payroll-common/.../skill/NacosAgentRegistry.java:377-438` | `load(previous)` 以 `Optional.isEmpty()` 同时表示 404、IO 失败、空正文 | 读路径回退尚可，写路径会把“读取失败”当成“没有配置” |
| 同文件 `:254-286`、`:267-274` | `publishSettings` 在 `fetch` 为空时用内存值重写整份设置文档 | 设置文档新增的键（非本类字段）会被覆盖丢失 |
| 同文件 `:297-354`、`:300` | `save` 基于 `list()` 的 TTL 缓存构造新列表 | 两个管理员并发编辑时后写覆盖先写，无冲突提示 |
| `payroll-common/.../skill/NacosFlowRegistry.java:139-185` | `save` 同样基于 `list()` 缓存读写 | 与智能体相同 |
| `payroll-common/.../skill/ModelConnectionRegistry.java:319-348`、`:373-383` | 已有数值 `revision` + `requireRevision`，但读失败仍返回 `previous` | 写路径可能用旧 catalog 覆盖新配置；这是现成的正确模式，也是本次要复用的模式 |
| `payroll-backend/.../web/FlowAdminController.java:112-132` | `activate` 先清智能体再设流程 | 第二步失败时两者都不生效，用户被静默打回默认流水线 |

### 2.2 对话执行面

| 位置 | 现状 | 影响 |
| --- | --- | --- |
| `payroll-common/.../config/PayrollCoreConfiguration.java:735-745` | `chatStreamExecutor` 固定 4 线程、无界队列 | 高峰期任务堆积，内存与延迟持续上涨，没有背压 |
| 同文件 `:123-127` | 每用户并发配额默认 2 | 只限制单用户，不限制全局 |
| `payroll-backend/.../web/ChatSessionController.java:389-426` | 配额通过后 `chatStreamExecutor.execute`；仅同步抛错时释放配额 | 执行器拒绝时没有 429 语义，且需保证配额释放 |
| `payroll-agent/src/main/java/io/agentscope/payroll/runtime/InternalTurnController.java:112-160` | runtime 用同一个 `chatStreamExecutor` 执行轮次 | Web 与 runtime 共享同一池，互相挤占 |
| `payroll-common/.../service/AgentRuntimeClient.java:96-123` | 仅 409 有专门映射，其余状态折叠为通用错误 | 429/503 无法驱动 Web 侧正确的提示与配额释放 |
| `payroll-backend/.../web/PayrollWebController.java:293-319` | 旧版 `/chat/stream` 同样直接 `execute` | 修复必须覆盖两条入口 |

### 2.3 模型、路由与记忆

| 位置 | 现状 | 影响 |
| --- | --- | --- |
| `payroll-common/.../memory/VectorMemoryService.java:122-148`、`:129` | `recall` 在对话线程同步调用 `embeddings.embed` | Ollama 卡住时每轮固定等 `embedTimeout`（`VectorMemorySettings.java:55-61`，默认 3s） |
| 同文件 `:30`、`:378`、`:389` | 写入侧已有 512 有界队列 + DropOldest | 读侧没有熔断与预算，与写入侧治理不对等 |
| `payroll-agent/.../router/AgentRouter.java:325-385`、`:346` | 每轮调用一次轻量模型，无缓存 | 同问重复付费；实测延迟 0.7–10.3s 直接叠加到首字 |
| `payroll-agent/.../agent/PayrollAgentFactory.java:1402-1410` | `modelId()` 每次重新 `resolveControl` | 单轮内多次解析，管理台切换连接时同一轮可能报告不同模型 |
| 同文件 `:1521-1536` | 模型缓存键 = `plane` + `\|` + `connection.versionKey()` | 换页面密钥不会重建模型实例 |
| `payroll-common/.../model/ModelConnection.java:109-127` | `versionKey()` 不含 `credentialRef` / `apiKeyEnv` | 凭证轮换后旧实例继续用旧 key |
| `payroll-common/.../db/ModelCredentialStore.java:63-104`、`:72` | `upsert` 更新 `updated_at` | 已有可用的凭证版本来源，可直接复用 |

### 2.4 运维与安全

| 位置 | 现状 | 影响 |
| --- | --- | --- |
| `payroll-backend/.../web/PayrollWebController.java:105-126`、`:110` | `/health` 恒为 `UP`，且返回数据库 URL | 不能反映依赖状态，且泄露内部地址 |
| `payroll-common/.../db/PayrollDatabase.java:65-77` | `app_users` 无 `must_change_password` | 无法强制新用户首次改密 |
| `payroll-backend/.../web/AuthInterceptor.java:20-21` | 公开路径仅登录与健康检查 | 首改密拦截需新增受限路径白名单 |
| `frontend/src/views/admin/AgentsView.vue:151-186`、`frontend/src/views/admin/FlowsView.vue:140-168` | 保存请求不带 revision | 并发编辑无法被服务端识别 |
| 仓库无备份脚本 | 仅部署文档描述手工流程 | 恢复不可演练 |

---

## 3. 目标与非目标

### 3.1 目标

- G1 配置读取失败与“空配置”在所有注册表中语义分离，读路径可回退，写路径必须 fail-closed。
- G2 管理台对智能体/流程/设置的并发编辑可检测，冲突返回 409 且不覆盖他人修改。
- G3 对话执行面有界，过载返回 429 且轮次状态一致、配额正确释放。
- G4 外部依赖（Ollama、Nacos、ES）故障时，对话主链路在固定预算内降级，而不是每轮等待。
- G5 模型实例缓存对凭证变更敏感，凭证轮换后下一轮生效。
- G6 提供最小可用的运维基线：真实健康探测、备份/恢复、成本闸门、首改密。

### 3.2 非目标

- 不保证 Nacos v1 下的强一致：读-改-写窗口仍存在，见 §5.6。
- 不重做设置页信息架构；批次 3 只做存储单键化，接口形态保持。
- 不引入新的配置中心或数据库表来存 revision。

---

## 4. 需求总览

| ID | 批次 | 优先级 | 模块 | 交付物 | 估算 |
| --- | --- | --- | --- | --- | --- |
| C1 | 1 | P0 | `payroll-common/skill` | `FetchResult` / `ListResult`，全部调用点迁移 | 0.4 人日 |
| C2 | 1 | P0 | `payroll-common/skill` | 技能 `load(previous)`、`fileCache` 只裁剪不清空、删除死分支 | 0.4 人日 |
| C3 | 1 | P0 | `payroll-common/skill`、`web` | 内容指纹 revision、写前重读、409/503、前端回传 | 0.8 人日 |
| C4 | 1 | P0 | `payroll-backend/web` | 激活顺序与补偿，消除“双双失效” | 0.2 人日 |
| C5 | 1 | P0 | `payroll-common/skill` | 模型连接写路径同样 fail-closed | 0.2 人日 |
| R1 | 2 | P1 | `payroll-common/config`、`web`、`runtime` | 有界执行器 + 429 + 配额释放 + 轮次失败 | 0.8 人日 |
| R2 | 2 | P1 | `payroll-common/memory` | 嵌入熔断、召回预算、独立召回线程池 | 0.8 人日 |
| R3 | 2 | P1 | `payroll-agent/router`、`agent`、`service` | 路由 LRU、单轮模型标识、智能体重绑定 | 0.7 人日 |
| R4 | 2 | P1 | `payroll-common/model`、`agent` | 凭证版本进入模型缓存键 | 0.4 人日 |
| R5 | 2 | P1 | `payroll-common/skill` | Nacos 列表翻页与部分失败丢弃 | 0.3 人日 |
| O1–O7 | 3 | P2 | 全局 | 见 §7 | 随迭代 |
| V1–V5 | 4 | P0 | `payroll-common/service`、`db`、`config`、`web` | 部署模式、判活契约、readiness、配额、提交预算 | 2–3 人日 |
| W1–W6 | 5 | P1 | `payroll-common`、`payroll-backend/kb`、`payroll-agent/runtime` | SSE 与事件线程模型、KB 检索/ingest/OCR、runtime 排队 | 4–5 人日 |
| X1–X7 | 6 | P2 | 全局 | 见 §14.8 | 随迭代 |

---

## 5. 批次 1（P0）

### 5.1 C1：统一 Nacos 读取结果语义

#### 5.1.1 类型定义

在 `NacosConfigClient` 内新增两个不可变结果类型，旧方法保留为委托，避免一次性改完所有调用点：

```java
public record FetchResult(Status status, ConfigEntry entry, String message) {
    public enum Status { OK, ABSENT, ERROR }
    public boolean ok() { return status == Status.OK; }
    public Optional<String> content() {
        return status == Status.OK ? Optional.of(entry.content()) : Optional.empty();
    }
}

public record ListResult(boolean ok, List<ConfigEntry> entries, String warning) {
    public static ListResult ok(List<ConfigEntry> entries) { ... }
    public static ListResult failed(String warning) { ... }
}
```

签名：

```java
public FetchResult fetchResult(String dataId);
public ListResult listResult(String dataIdPattern);
public Optional<String> fetch(String dataId);        // 委托：仅 OK 返回内容
public List<ConfigEntry> list(String dataIdPattern); // 委托：!ok 时返回空列表
```

#### 5.1.2 状态判定矩阵

| 响应 | `FetchResult` | `ListResult` |
| --- | --- | --- |
| HTTP 200 + 非空正文 | `OK`，携带原始正文 | `OK`，携带 `pageItems` |
| HTTP 200 + 空正文 | `ABSENT` | `OK` 且空列表（列表接口不应出现空正文） |
| HTTP 404 | `ABSENT` | `OK` 且空列表 |
| HTTP 403 | `ERROR`，并清空 `accessToken` | `ERROR`，并清空 `accessToken` |
| 其它 4xx/5xx | `ERROR`，`message` 含状态码 | `ERROR` |
| IO / 超时 / 中断 | `ERROR`，中断时恢复中断位 | `ERROR` |
| 200 但 `pageItems` 不是数组 | 不适用 | `ERROR`（格式错误） |

关键约定：

1. `revision` 只对 `OK` 有意义；`ABSENT` 使用空文档指纹（见 §5.3.1）。
2. `fetch()` 的旧行为保持不变（`OK` 才有值），因此所有读路径不需要同步修改即可编译通过。
3. `list()` 的旧行为保持不变（失败返回空），但调用方必须在批次 1 内逐步迁移到 `listResult()`；迁移完成的判据是 `grep -rn "client.list(" payroll-common payroll-backend payroll-agent` 仅在委托实现中出现。

#### 5.1.3 调用点迁移

| 调用点 | 迁移后行为 |
| --- | --- |
| `NacosAgentRegistry.load` | `fetchResult`；`ABSENT` 走内置/上次快照，`ERROR` 走上次快照 |
| `NacosFlowRegistry.load` | 同上 |
| `NacosSkillRegistry.load` | 新增 `load(previous)`，见 §5.2 |
| `NacosSkillRepository.rebuildCatalog` | 改用 `ListResult`，见 §5.2.3 |
| `ModelConnectionRegistry.load` | `ABSENT` 空目录，`ERROR` 保留 `previous`（读路径） |
| `NacosAgentRegistry.publishSettings` | `ERROR` 必须抛 503，见 §5.3.4 |

验收：

- 新增 `NacosConfigClientTest` 用例：404 → `ABSENT`；500 → `ERROR`；IO → `ERROR`；403 后下次请求重新取 token；200 空正文 → `ABSENT`。
- `FakeNacosServer` 增加 `failNextList`、`failNextFetch`、`statusForDataId` 三个开关。

### 5.2 C2：技能加载保留上次成功快照

#### 5.2.1 `load(previous)` 算法

`NacosSkillRegistry` 当前的 `load()` 无参（`:485`），改为：

```java
private Snapshot load(Snapshot previous) {
    if (!settings.enabled()) {
        return new Snapshot(List.of(), Map.of(), now());
    }
    ListResult listed = client.listResult("*" + DATA_ID_SUFFIX);
    if (!listed.ok()) {
        if (previous != null && !previous.remotes().isEmpty()) {
            log.warn("[Skill] 列出 Nacos 技能失败，沿用上次快照（{} 个）：{}",
                    previous.remotes().size(), listed.warning());
            // 失败也计入 TTL，避免故障期间每次调用都重试
            return new Snapshot(previous.remotes(), previous.byId(), now());
        }
        log.warn("[Skill] 列出 Nacos 技能失败且无历史快照，仅保留内置技能：{}",
                listed.warning());
        return new Snapshot(List.of(), Map.of(), now());
    }
    // 解析成功分支保持现状（循环构建 byId）；文件缓存改为只裁剪
    fileCache.keySet().retainAll(byId.keySet());
    return new Snapshot(List.copyOf(byId.values()), Map.copyOf(byId), now());
}
```

要求：

1. `load()` 的调用点（`:476-483` 的双检锁刷新）改为 `load(snapshot)`，把当前快照传入。
2. 失败回退必须刷新 `fetchedAt`（负缓存），否则 Nacos 故障期间每次请求都会重新打 Nacos。
3. 成功路径不再 `fileCache.clear()`，只 `retainAll(byId.keySet())`；被删除技能的文件包随之淘汰。
4. 单条技能正文解析失败仍按现有逻辑跳过该条，不回退整份快照（与 `NacosAgentRegistry` 的单条容错一致）。

#### 5.2.2 文件缓存边界

批次 1 只做“不清空”。容量上限与 LRU 见批次 3 O2。

#### 5.2.3 `NacosSkillRepository` 删除死分支

`ConfigLister`（`:46`）从 `List<ConfigEntry>` 改为返回 `ListResult`，工厂方法 `nacosLister`（`:84-86`）同步改为 `client::listResult`：

```java
@FunctionalInterface
interface ConfigLister {
    NacosConfigClient.ListResult list(String dataIdPattern);
}
```

`rebuildCatalog` 改为显式分支：

```java
ListResult listed = lister.list("*" + DATA_ID_SUFFIX);
if (!listed.ok()) {
    Catalog previous = lastGood;
    if (previous != null) {
        log.warn("[Skill] 列出 Nacos 技能失败，沿用上次成功快照（{} 个技能）：{}",
                previous.ids().size(), listed.warning());
        Catalog retry = new Catalog(previous.ids(), previous.manifests(), now);
        catalog = retry;
        return retry;
    }
    log.warn("[Skill] 列出 Nacos 技能失败，仅保留内置技能：{}", listed.warning());
} else {
    for (NacosConfigClient.ConfigEntry entry : listed.entries()) {
        // 现有解析逻辑不变
    }
}
```

删除原 `try { ... } catch (RuntimeException e) { ... }` 整段；类文档同步改为“列表失败由 `ListResult.ok=false` 表达”。

#### 5.2.4 测试

- `NacosSkillRegistryTest`：先成功加载 2 个技能 → 下一次列表失败 → `snapshot()` 仍为 2 个；`fileCache` 中仍保留这 2 个键。
- `NacosSkillRepositoryTest`：改用已有的包级 `ConfigLister` 构造器（`NacosSkillRepository.java:89-108`，当前测试只用 `fetcher` 版本），覆盖 `ok=false` 有/无 `lastGood` 两条路径。
- `FakeNacosServer` 支持按顺序返回失败。

### 5.3 C3：revision + 写前重读 + 409

#### 5.3.1 revision 定义

采用**内容指纹**，不引入数字版本字段：

```java
static String revisionOf(String raw) {
    return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8)));
}
static final String ABSENT_REVISION = revisionOf("");
```

理由：

1. 不改变 `payroll-agents.json` / `payroll-flows.json` 的现有格式（裸数组），滚动发布期间新旧实例可共存。
2. 与 HTTP `ETag` 语义一致，客户端只需原样回传。
3. 数字 revision 需要额外写入一次才能引入，且多实例同时自增仍会冲突。

适用范围：

| 文档 | revision 来源 |
| --- | --- |
| `payroll-agents.json` | 原文 SHA-256 |
| `payroll-agent-settings.json` | 原文 SHA-256 |
| `payroll-flows.json` | 原文 SHA-256 |
| `payroll-flow-settings.json` | 原文 SHA-256 |
| `payroll-models.json` | 暂不切换，继续用现有数字 `revision`（已实现） |
| `<skillId>.md` / `<skillId>.files.json` | 本批次不接入，技能写入已有哈希校验（批次 3 再评估） |

#### 5.3.2 注册表接口

`NacosAgentRegistry` 与 `NacosFlowRegistry` 各新增：

```java
public record Snapshot<T>(List<T> items, String revision) {}

/** 绕过 TTL 重读；ERROR 抛 PayrollUnavailableException。 */
private Snapshot<T> reloadForWrite();

/** 保存；expectedRevision 为 null/空白时跳过校验并 WARN。 */
public T save(T input, String operator, String expectedRevision, boolean keepModelWhenEmpty);
public boolean delete(String id, String expectedRevision);
```

实现要点：

1. `reloadForWrite()` 使用 `fetchResult`：
   - `OK` → 解析成功才返回；解析失败视为 `ERROR`（禁止用旧数据写回）。
   - `ABSENT` → 返回 `ABSENT_REVISION` 与写入基线。智能体基线为 `builtinAgents()`，流程基线为 `List.of(builtinFlow())`。
   - `ERROR` → `throw new PayrollUnavailableException("读取 " + DATA_ID + " 失败：" + message)`。
2. `requireRevision(current, expected)`：
   - `expected == null || expected.isBlank()` → `log.warn` 后继续（滚动发布兼容）。
   - 不相等 → `throw new PayrollConflictException("配置已被他人修改，请刷新后重试")`，并携带 `currentRevision`。
3. `persist` 成功后，缓存写入“刚序列化的内容”的指纹，不重新拉取：
   ```java
   String published = json.writeValueAsString(agents);
   client.publish(DATA_ID, published);
   snapshot = new Snapshot<>(agents, revisionOf(published));
   ```
4. 删除接口同样先 `reloadForWrite()` 再校验。
5. 保留原有 `save(input, operator)` / `save(input, operator, keepModelWhenEmpty)` 重载，内部委托时 `expectedRevision=null`，避免 CLI 与既有测试被迫改动。
6. 读路径 `list()` / `find()` / `activeAgent()` 继续使用 TTL + 回退，**不抛 503**。

#### 5.3.3 设置文档写入

`NacosAgentRegistry.publishSettings`（`:254-286`）改为：

```java
FetchResult fetched = client.fetchResult(SETTINGS_DATA_ID);
ObjectNode root;
switch (fetched.status()) {
    case OK -> root = parseObject(fetched.entry().content()); // 解析失败 → 503
    case ABSENT -> root = json.createObjectNode();            // 真正的新文档
    case ERROR -> throw new PayrollUnavailableException(...); // 禁止用缓存重写
}
mutator.accept(root);
client.publish(SETTINGS_DATA_ID, json.writeValueAsString(root));
```

删除 `if (body.isEmpty()) { root.put("calculationAgentId", ...); ... }` 整段。`NacosFlowRegistry.publishSettings`（`:203-218`）同样处理。

#### 5.3.4 503 与 409 响应

新增异常：

```java
package io.agentscope.examples.payroll.domain;
/** 依赖不可用或读取失败，禁止基于旧数据写入（HTTP 503）。 */
public class PayrollUnavailableException extends RuntimeException { ... }
```

`ApiExceptionHandler` 新增处理器，放在通用 `Exception` 之前：

```java
@ExceptionHandler(PayrollUnavailableException.class)
public ResponseEntity<ApiResponse> handleUnavailable(PayrollUnavailableException e) {
    return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
}
```

`PayrollConflictException` 增加可选 `currentRevision`：

```java
public class PayrollConflictException extends RuntimeException {
    private final String currentRevision;
    public PayrollConflictException(String message) { this(message, null); }
    public PayrollConflictException(String message, String currentRevision) { ... }
    public String currentRevision() { return currentRevision; }
}
```

409 响应体在 `data` 中回传当前指纹，便于前端立即刷新而不必再猜：

```json
{ "status": "ERROR", "message": "配置已被他人修改，请刷新后重试",
  "data": { "currentRevision": "9f2c..." } }
```

实现方式：`ApiResponse` 保持现有三字段不变，`ApiExceptionHandler` 新增 `error(HttpStatus, String, Object)` 重载；`handleConflict` 在 `e.currentRevision() != null` 时使用它，其余情况沿用原 `error()`。

#### 5.3.5 模型连接写安全（C5）

`ModelConnectionRegistry` 已有数字 revision，但 `load(previous)` 在读取失败时返回 `previous`，`storedNow()` 会拿旧 catalog 做写入基线。

改动：

1. 新增 `reloadStrict()`：`fetchResult` 为 `ERROR` → `PayrollUnavailableException`；`ABSENT` → `ModelCatalog.empty()`。
2. `saveConnection` / `deleteConnection` / `setDefaults` 使用 `reloadStrict()` 替代 `storedNow()`。
3. 读路径 `stored()` 保持回退语义。
4. 复用现有 `requireRevision`（`:373-383`），其 `MODEL_REVISION_CONFLICT` 继续映射到 `PayrollConflictException`。

#### 5.3.6 API 契约

管理台接口（`/api/admin/agents`、`/api/admin/flows`）：

| 方法 | 路径 | 新增参数 | 成功 | 失败 |
| --- | --- | --- | --- | --- |
| GET | `/api/admin/agents` | — | `data.revision`、`data.settingsRevision` | 500 |
| GET | `/api/admin/agents/meta` | — | `data.revision`、`data.settingsRevision` | 500 |
| POST | `/api/admin/agents` | `?revision=`（agents 文档） | 200 | 409 / 503 |
| PUT | `/api/admin/agents/{id}` | `?revision=`（agents 文档，保留 `clearModel`） | 200 | 409 / 503 |
| DELETE | `/api/admin/agents/{id}` | `?revision=`（agents 文档） | 200 | 409 / 503 |
| PUT | `/api/admin/agents/{id}/active` | `?revision=`（agent settings 文档） | 200 | 409 / 503 |
| DELETE | `/api/admin/agents/active` | `?revision=`（agent settings 文档） | 200 | 409 / 503 |
| PUT | `/api/admin/agents/action-confirm` | `?revision=`（agent settings 文档） | 200 | 409 / 503 |
| GET | `/api/admin/flows` | — | `data.revision`、`data.settingsRevision` | 500 |
| GET | `/api/admin/flows/meta` | — | `data.revision`、`data.settingsRevision` | 500 |
| POST | `/api/admin/flows` | `?revision=`（flows 文档） | 200 | 409 / 503 |
| PUT | `/api/admin/flows/{id}` | `?revision=`（flows 文档） | 200 | 409 / 503 |
| DELETE | `/api/admin/flows/{id}` | `?revision=`（flows 文档） | 200 | 409 / 503 |
| PUT | `/api/admin/flows/{id}/active` | `?revision=`（flow settings 文档） | 200 | 409 / 503 |
| DELETE | `/api/admin/flows/active` | `?revision=`（flow settings 文档） | 200 | 409 / 503 |

约定：

- `revision` 的校验对象随接口：写配置定义时校验 agents / flows 文档；激活、取消激活、写确认开关时校验对应的 settings 文档。
- 跨文档写入（激活流程时顺手清智能体）在服务端对另一份文档做一次 `reloadForWrite`，不要求客户端回传第二份 revision。
- 缺少 `revision`：继续执行并记录 WARN；日志字段 `revision_missing=true`，用于灰度期统计。
- 409 必须携带 `data.currentRevision`；503 不回传内部地址。
- 审计日志新增 `revision`、写结果 `write_result`（OK/CONFLICT/UNAVAILABLE）两个字段；`write_result` 与既有表示请求成败的 `result`（SUCCESS/FAILURE）分开，避免语义冲突。

#### 5.3.7 前端

`frontend/src/api/http.js` 已抛出带 `status` 的 `ApiError`，无需改。

`AgentsView.vue`：

1. `load()` 把响应中的 `revision` 保存到新的 `revision` ref（与现有 `meta` / `agents` 同级）。
2. `save()` 请求 URL 追加 `?revision=${encodeURIComponent(revision)}`。
3. `catch (error)`：
   - `status === 409` → `toast('配置已被他人修改，已加载最新数据，请确认后重新保存')`，`await load()`，**保留弹窗与用户已填内容**，只刷新列表和 revision。
   - `status === 503` → `toast('配置中心暂时不可用，请稍后重试')`，不关闭弹窗。
4. 激活/取消激活、写确认开关同样带 `revision`。

`FlowsView.vue`：同上；`reload()` 保存 `revision`，`saveFlow()` 追加查询参数，409 时 `reload()` 但保留画布。

验收：

- 两个浏览器标签同时编辑同一智能体：后保存者收到 409，列表刷新为前者内容，且后者的表单内容未丢失。
- Nacos 停止后点击保存：503，页面不出现“保存成功”。

### 5.4 C4：激活顺序与补偿

当前 `FlowAdminController.activate`（`:112-132`）先 `clearActiveAgent()` 再 `setActiveFlow(id)`。改为“意图优先”：

| 操作 | 顺序 | 中途失败处理 |
| --- | --- | --- |
| 激活流程 F | ① 记录当前 agentId → ② 写 flow settings → ③ 清 agent settings | ② 失败：无副作用，返回 503。③ 失败：记录 WARN（两者同时存在，运行期以流程优先，用户意图仍生效），返回 200 并在 `data.warning` 中提示 |
| 激活智能体 A | ① 记录当前 flowId → ② 清 flow settings（若清失败则直接返回 503，不写 agent）→ ③ 写 agent settings | ③ 失败：尽力恢复 flow settings 为 ① 的值；恢复也失败则返回 503 并提示“当前无激活模式” |
| 取消激活 | 只清当前文档 | 失败返回 503，状态不变 |

`AgentAdminController.setActive`（`:190-210`）按上表改；`clearActive` 保持不变。

运行期优先级保持现状：`flowId` 非空则走流程（`PayrollWebController.java:112-121` 的 `chat_mode` 语义不变）。

### 5.5 批次 1 测试清单

| 测试 | 覆盖 |
| --- | --- |
| `NacosConfigClientTest` | 状态矩阵、403 清 token、翻页参数（批次 2 再扩展多页） |
| `NacosSkillRegistryTest` | 列表失败保留快照、文件缓存不清空、成功时裁剪 |
| `NacosSkillRepositoryTest` | `ConfigLister` 失败有/无 `lastGood`、死分支已删除 |
| `NacosAgentRegistryTest` | 写前重读、revision 冲突、缺省 revision、503、设置文档不丢未知键 |
| `NacosFlowRegistryTest` | 同上 |
| `AgentAdminWebTest` | 409 响应体含 `currentRevision`；503 映射；旧请求无 revision 仍成功 |
| `FlowAdminWebTest` | 同上 + 激活顺序与补偿 |
| `ModelConnectionTest` | 读取失败时保存返回 503，不用旧 catalog |
| `FakeNacosServer` | 新增 `failNextList` / `failNextFetch` / `statusForDataId` |

全量：`D:\tools\apache-maven-3.6.3\bin\mvn.bat -o test`（JDK 17）。

### 5.6 剩余竞态与验收边界

Nacos v1 没有 CAS。`reloadForWrite()` 与 `publish` 之间有一个 HTTP 往返窗口，两个实例在此窗口内仍可能后写覆盖。本批次的目标是把窗口从“TTL 最长 60s”缩小到“一次往返”，并在冲突时显式告知；不承诺强一致。

验收必须包含：

1. 单实例并发测试：两个线程用同一 revision 保存，恰好一个成功、一个 409。
2. 故障注入：列表/读取失败时，读路径仍可用，写路径 503。
3. 设置文档中预置一个未知键（如 `futureFlag`），保存 `actionConfirm` 后该键仍存在。

---

## 6. 批次 2（P1）

### 6.1 R1：有界执行器与 429

#### 6.1.1 线程池

`PayrollCoreConfiguration.chatStreamExecutor`（`:735-745`）改为有界：

```java
return new ThreadPoolExecutor(
        threads, threads,
        0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity),
        payrollThreadFactory("payroll-chat-stream"), // 把现有 lambda 提为私有方法
        new ThreadPoolExecutor.AbortPolicy());
```

配置：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `payroll.chat.stream-threads` | 4 | 执行线程数 |
| `payroll.chat.stream-queue-capacity` | 32 | 等待队列 |
| `payroll.chat.max-concurrent-per-user` | 2 | 现有配额，保持不变 |

不采用 `CallerRunsPolicy`：SSE 请求线程被占住会把 Tomcat 拖垮；拒绝必须是快速失败。

#### 6.1.2 Web 入口

`ChatSessionController.stream`（`:389-426`）与 `PayrollWebController` 旧入口（`:293-319`）：

```java
if (!chatQuotaService.tryAcquire(userId)) {
    throw new TooManyRequestsException("当前用户的对话并发达上限，请等待上一轮回答完成");
}
try {
    chatStreamExecutor.execute(task);
} catch (RejectedExecutionException e) {
    chatQuotaService.release(userId);
    throw new TooManyRequestsException("当前对话请求过多，请稍后重试");
}
```

同时：

- 429 响应带 `Retry-After: 5`（秒）。
- 指标：`chat_stream_rejected_total`，日志带队列长度。
- 已建立 SSE 但任务被拒时，先 `channel.send("error", ...)` 再 `complete()`，避免前端悬挂。

#### 6.1.3 runtime 入口

`InternalTurnController`（`:112-160`）在同一执行器上执行轮次。拒绝时：

1. 调用 `manager.failTurn(turnId, "执行队列已满")`（若现有 API 名称不同，按 `ChatSessionManager` 现有失败入口实现）。
2. 返回 HTTP 429（body 含 `message` 字段即可）。
3. 不释放 Web 侧配额——配额在 Web 侧持有，由 Web 在收到 429 后释放。

`AgentRuntimeClient.post`（`:96-123`）按状态码分支：

| 状态 | 映射 |
| --- | --- |
| 200–299 | 返回正文 |
| 409 | `IllegalStateException`（保持现状） |
| 429 | `TooManyRequestsException("运行时繁忙，请稍后重试")` |
| 503 | `PayrollUnavailableException("运行时不可用")` |
| 其它 | 现有通用错误 |

Web 的 `stream(...)` 捕获 `TooManyRequestsException` → `chatQuotaService.release(userId)` + SSE `error` + `complete()`；捕获 `PayrollUnavailableException` → 同样释放，但文案为“运行时暂不可用”。

#### 6.1.4 验收

- 队列 32、线程 4、单用户配额 2：并发 50 个请求，超出的返回 429，已接受请求全部完成。
- 每个被拒请求的配额计数回落为 0（Redis 与本地两种模式）。
- runtime 拒绝后，`chat_turns` 中该轮为 FAILED，断点续传不悬挂。

### 6.2 R2：嵌入熔断与召回预算

#### 6.2.1 熔断器

新增 `payroll-common/.../memory/EmbeddingCircuitBreaker`：

- 状态：`CLOSED` / `OPEN` / `HALF_OPEN`。
- 失败判定：`embed` / `embedAll` 抛出异常，或召回预算超时（由 `recall` 主动调用 `recordFailure()` 上报，否则超时不会计入熔断）。
- `CLOSED` 下连续失败 `failures=3` 次（窗口 60s）→ `OPEN` 60s。
- `OPEN` 期间 `VectorMemoryService.recall` 直接返回 `""`，不发起 HTTP；`indexQuestion` / `indexSummary` 直接丢弃。
- `OPEN` 到期后放行 1 次探测；成功 → `CLOSED`，失败 → 再 `OPEN` 60s。
- `OllamaEmbeddingClient.available()` 在 `OPEN` 期间直接返回 `false`，避免管理台探测绕过熔断。

#### 6.2.2 召回预算

`VectorMemoryService.recall`（`:122-148`）改为：

1. 熔断 `OPEN` → 立即返回 `""`。
2. 否则提交到新增 `memoryRecallExecutor`（2 线程，有界队列 16，`AbortPolicy`）：
   - 拒绝 → 返回 `""`。
   - `Future.get(recallBudgetMs, MILLISECONDS)` 超时 → `future.cancel(true)`，返回 `""`。
3. 预算从 `recall` 调用开始计时；超时日志记录 `budget_exceeded=true`，并上报熔断器。
4. 超时后底层 HTTP 可能仍在执行，线程为 daemon，不阻塞请求；连续超时由熔断器兜底。

配置：

| 键 | 默认 |
| --- | --- |
| `payroll.memory.recall-budget-ms` | 600 |
| `payroll.memory.embed-breaker.failures` | 3 |
| `payroll.memory.embed-breaker.window-seconds` | 60 |
| `payroll.memory.embed-breaker.open-seconds` | 60 |

`VectorMemorySettings.embedTimeout` 保持 3s（单次 HTTP 上限），召回预算独立于它。

#### 6.2.3 验收

- 停掉 Ollama：第一轮最多等待 600ms，之后连续轮次 < 10ms（熔断生效）。
- 恢复 Ollama：60s 后第一轮自动恢复召回，无需重启。
- 正常召回耗时不因预算逻辑增加（对比日志 `耗时`）。

### 6.3 R3：路由 LRU 与单轮模型标识

#### 6.3.1 路由缓存

在 `AgentRouter` 内新增：

- 键：`SHA-256(instruction + "\n" + question + "\n" + 候选 id 有序列表 + "\n" + preferredId + "\n" + teamAvailable + "\n" + payroll)`。
- 值：`RouteDecision` + 插入时间。
- 容量 512，`LinkedHashMap` access-order，TTL 300s。
- 只缓存 `decision.routed() == true` 且 `agent != null` 的结果；回退决策不缓存。
- 命中后按 `agent.id()` 在当前 `candidates` 中重新绑定；找不到视为未命中并删除。
- 命中日志：`[智能体路由] 缓存命中：agent=...，耗时=...ms`。

配置：

| 键 | 默认 |
| --- | --- |
| `payroll.router.cache.enabled` | true |
| `payroll.router.cache.ttl-seconds` | 300 |
| `payroll.router.cache.max-entries` | 512 |

#### 6.3.2 单轮模型标识

`PayrollChatService` 在 `run()` 开始时解析一次：

```java
String turnModelId = PayrollAgentFactory.modelId();
```

替换以下调用点（全部改为使用 `turnModelId`）：

- `:658`（`onStart`）
- `:870`、`:909`、`:1017`、`:1132`、`:1222`
- `:1500`、`:2278`

对外方法 `modelId()`（`:2596-2597`）保留，供管理台与健康检查使用。

如果轮次进行中连接被管理员切换，本轮仍使用启动时的标识；日志在检测到变化时打印一次 `[模型] 轮次内连接变更，本轮沿用 turnModelId=...`，便于排查。

#### 6.3.3 验收

- 同一问题连续两次：第二次路由 LLM 调用次数为 0，首字延迟下降。
- 修改候选列表（增删智能体）后，相同问题重新调用路由（键变化）。
- 单轮 SSE `start` 事件与最终 `route` 事件中的模型标识一致。

### 6.4 R4：凭证版本进入模型缓存键

#### 6.4.1 版本来源

`ModelCredentialStore.Credential.updatedAt`（`ModelCredentialStore.java:23`）已由 `upsert` 更新（`:72`）。

`ModelCredentialService` 新增：

```java
public String versionOf(ModelConnection connection) {
    if (connection.credentialRef() == null || connection.credentialRef().isBlank()) {
        return connection.apiKeyEnv() == null ? "" : "env:" + connection.apiKeyEnv();
    }
    // 注意：凭证表主键是 connection.id()，不是展示用的 credentialRef
    return store.find(connection.id())
            .map(cred -> String.valueOf(cred.updatedAt().toEpochSecond(ZoneOffset.UTC)))
            .orElse("missing");
}
```

- 结果缓存 5s；`save` / `clear` 时主动失效。
- 不在缓存键中放入密钥本身，只放时间戳。

#### 6.4.2 缓存键

`ModelConnection.versionKey()`（`:109-127`）增加重载：

```java
public String versionKey(String credentialVersion) {
    return versionKey() + "|cred:" + (credentialVersion == null ? "" : credentialVersion);
}
```

`PayrollAgentFactory.cachedModel`（`:1521-1536`）：

```java
String key = plane + "|" + connection.versionKey(credentialVersion(connection));
```

`credentialVersion` 通过 `configureModels` 注入的 `ModelCredentialService` 获取；未注入时返回 `""`（测试与 CLI 兼容）。

#### 6.4.3 验收

- 保存新密钥 → 下一轮 `cachedModel` 返回新实例（日志连接构建一次），旧实例不再被复用。
- 仅修改 `temperature` 等参数仍会重建（现有行为不回退）。
- 环境变量模式：修改环境变量需重启，文档注明。

### 6.5 R5：Nacos 列表翻页

`NacosConfigClient.listResult` 改为循环：

```java
for (int pageNo = 1; pageNo <= maxPages; pageNo++) {
    ListResult page = fetchPage(pattern, pageNo, pageSize);
    if (!page.ok()) return ListResult.failed(page.warning()); // 丢弃部分结果
    entries.addAll(page.entries());
    if (page.entries().size() < pageSize) break;
}
```

规则：

1. 任一页失败 → 整次调用 `ok=false`，**不返回部分结果**（避免把截断列表当成完整列表写回）。
2. `maxPages` 默认 50（对应 10000 条），超过时返回 `ok=false`，日志要求人工清理。
3. 日志记录总条数与页数。
4. 批次 1 只引入 `ListResult`，仍固定第 1 页；本项单独提交，便于回滚。

验收：

- `FakeNacosServer` 造 450 条：返回 450，3 次请求。
- 第 2 页失败：`ok=false`，上层保留上次快照，不写回。
- 200 条整（首页满页）：继续请求第 2 页，得到 0 条后结束。

### 6.6 批次 2 测试清单

| 测试 | 覆盖 |
| --- | --- |
| `ChatStreamExecutorTest`（新增） | 拒绝策略、429、指标 |
| `ChatSessionControllerTest` | 执行器拒绝时释放配额、SSE error |
| `AgentRuntimeClientTest` | 429/503 映射 |
| `EmbeddingCircuitBreakerTest`（新增） | 三态迁移、半开探测 |
| `VectorMemoryServiceTest` | 预算超时、熔断短路 |
| `AgentRouterTest` | 命中/失效/重绑定、回退不缓存 |
| `ModelConnectionTest` | 凭证版本变化重建 |
| `NacosConfigClientTest` | 多页、部分失败、maxPages |

---

## 7. 批次 3（P2，随迭代）

| ID | 项 | 设计要点 | 验收 |
| --- | --- | --- | --- |
| O1 | 设置单键化 | 新增 `payroll-runtime-settings.json`：`{ mode: "FLOW" \| "AGENT" \| "PIPELINE", agentId, flowId, actionConfirm, revision }`；首次写入时从旧的两份设置文档迁移；读取优先新文档，缺失时回退旧文档。可彻底消除 §5.4 的双文档竞态 | 切换流程/智能体只需一次 publish；旧文档保留只读一个版本周期 |
| O2 | `fileCache` 上限 | 改为访问序 LRU：最大 128 条、正文总字节 32MB；键为 `skillId`，值含 `mdHash`；技能正文变化时失效 | 压测 1000 技能：内存不超过 32MB，冷读仍按需加载 |
| O3 | 健康探测 | `/health` 返回 `{status, components:{db, redis, nacos, es}}`；DB `SELECT 1` 1s 超时，Redis ping，Nacos 用最近一次成功列表时间，ES 可选；删除 `database.url`；核心依赖不可用时整体 503 | 停 Redis/Nacos 分别得到 DEGRADED/503；响应无地址、用户名 |
| O4 | 备份与恢复 | `scripts/backup.sh`：MySQL 用 `MYSQL_PWD` 环境变量（不出现在命令行与日志），Nacos 通过 v1 导出接口；保留 7 天；`docs/restore-runbook.md` 含演练步骤 | 在临时库完整恢复一次并跑冒烟查询 |
| O5 | 成本闸门 | 基于 `llm_usage` 聚合每用户每日 token；超限返回 429 并提示；默认关闭（预算 0 = 不限） | 设置小额预算后第 N+1 轮被拒；关闭后恢复 |
| O6 | 强制首改密 | `app_users` 增 `must_change_password`（H2：`BOOLEAN DEFAULT FALSE`；MySQL：`TINYINT(1) NOT NULL DEFAULT 0`，`V20__must_change_password.sql`）；登录与 `/api/auth/me` 响应带 `must_change_password`（全局 Jackson 为 snake_case，前端已按该键读取）；拦截器仅放行 `/api/auth/login`、`/api/auth/password`、`/api/auth/logout`、`/api/auth/me`、`/api/health`；管理台新建用户/重置密码置 TRUE，存量用户与引导管理员 FALSE | 新用户登录后只能改密；改密后恢复正常；存量账号不受影响 |
| O7 | backlog 校正 | 见下表 | 任务列表与代码状态一致 |

backlog 校正（对应任务 #142–#152）：

| 任务 | 结论 |
| --- | --- |
| #142 默认配置与成本开关 | 已由 O5 完成，关闭；默认配置项已在各 PRD 落地，不再单列 |
| #143 KB 任务启动恢复与重试上限 | 已完成（#371），关闭 |
| #144 对话并发、有界队列与 429 | 已由 R1 完成，关闭 |
| #145 安全基线 | 已闭环：登录限流（#154）、强制首改密（O6）、绑定地址写入部署文档；关闭 |
| #146 Actuator 与 Prometheus | 保留为可选；先用 O3 的 `/health` 与现有日志指标 |
| #147 HikariCP 与关键事务 | 已完成（#153），关闭 |
| #149 KB 提示注入防护 | 保留为独立 P1 安全项 |
| #150 备份任务与恢复手册 | 已由 O4 完成（`scripts/backup.sh` + `docs/restore-runbook.md`），关闭 |
| #151 效果回归集与脚本 | 保留，与 §9 的金标集合并 |
| #152 全量测试与端到端 | 由各批次验收承接 |
| #373 偏好回显示例文案 | 保留 P1，不受本 PRD 影响 |

### 7.1 实现状态（2026-11-22）

| ID | 代码入口 | 测试 | 与设计的偏差 |
| --- | --- | --- | --- |
| O1 | `payroll-common/.../skill/NacosRuntimeSettings.java`（单一设置文档与 revision 校验）、`NacosAgentRegistry` / `NacosFlowRegistry`（共用该 Bean）、`AgentAdminController` / `FlowAdminController`（每次切换一次 publish） | `NacosRuntimeSettingsTest`、`AgentAdminControllerTest`、`FlowAdminControllerTest` | 控制器返回 `{activeAgentId, clearedFlowId}` / `{activeFlowId, clearedAgentId}`，不再返回 `data.warning`；旧文档并发写入仅 WARN 跳过校验 |
| O2 | `NacosSkillRegistry.FileCache`（访问序 LRU）、`PayrollCoreConfiguration`（`payroll.skill.file-cache.*`） | `NacosSkillRegistryTest` | 按 `skillId` 计数与字节双上限；`mdHash` 变化即失效 |
| O3 | `payroll-common/.../health/RuntimeHealthService.java`、`payroll-backend/.../web/HealthEndpointsController.java` | `RuntimeHealthServiceTest`、`HealthEndpointsControllerTest` | `/health` 的 `components` 不返回地址与用户名；ES 缺失时该组件为 `SKIPPED` 而非 DOWN |
| O4 | `scripts/backup.sh`、`docs/restore-runbook.md` | 本机 H2 + Nacos v1 导出实跑通过 | Nacos 改为 v1 导出 zip（原设计未指定实现方式）；不可达时跳过并提示，不使备份整体失败 |
| O5 | `payroll-common/.../service/TokenBudgetService.java`、`PayrollCoreConfiguration`、`ChatSessionController` | `TokenBudgetServiceTest`、`ChatSessionControllerTest` | 预算超限复用 429 通道，响应体与既有配额拒绝一致 |
| O6 | `H2AdminRepository`（建号/重置置 TRUE、引导管理员 FALSE）、`AuthService`、`AuthInterceptor`、`AuthController`、`V20__must_change_password.sql` | `ForcedPasswordChangeWebTest`（端到端）、`PayrollAuthWebTest` | 迁移编号为 V20（V19 已被审计写入结果占用）；JSON 键为 `must_change_password`；拦截器额外放行 `/api/auth/me`，供前端首屏判断 |

---

## 8. 配置项汇总

| 键 | 默认 | 批次 | 作用 |
| --- | --- | --- | --- |
| `payroll.chat.stream-threads` | 4 | 2 | 对话执行线程数 |
| `payroll.chat.stream-queue-capacity` | 32 | 2 | 等待队列长度 |
| `payroll.chat.max-concurrent-per-user` | 2 | 现有 | 单用户并发配额 |
| `payroll.chat.turn-max-minutes` | 30 | 现有 | 单轮绝对上限；配额 TTL 与租约兜底都须大于它 |
| `payroll.chat.turn-heartbeat-seconds` | 10 | 现有 | 租约续约与 runtime 心跳间隔 |
| `payroll.memory.recall-budget-ms` | 600 | 2 | 单轮召回预算 |
| `payroll.memory.embed-breaker.failures` | 3 | 2 | 熔断失败阈值 |
| `payroll.memory.embed-breaker.window-seconds` | 60 | 2 | 失败统计窗口 |
| `payroll.memory.embed-breaker.open-seconds` | 60 | 2 | 熔断持续时长 |
| `payroll.router.cache.enabled` | true | 2 | 路由缓存开关 |
| `payroll.router.cache.ttl-seconds` | 300 | 2 | 路由缓存 TTL |
| `payroll.router.cache.max-entries` | 512 | 2 | 路由缓存容量 |
| `payroll.nacos.list.max-pages` | 50 | 2 | 列表翻页上限 |
| `payroll.skill.file-cache.max-entries` | 128 | 3 | 文件包缓存条数 |
| `payroll.skill.file-cache.max-bytes` | 33554432 | 3 | 文件包缓存字节上限 |
| `payroll.health.skills-stale-seconds` | 300 | 3 | `/health` 中技能目录新鲜度窗口，超过即 DOWN。原键 `payroll.health.nacos-stale-seconds`，2026-11-23 随技能单源开关改名（该组件两源同一算法，继续叫 nacos 会让 db 源的告警挂错名词），见 `skill-storage-mysql-oss-spec.md` §11.1 第 5 条 |
| `payroll.usage.daily-token-budget` | 0 | 3 | 每日 token 预算，0 为不限 |
| `payroll.auth.force-password-change` | true | 3 | 首改密开关 |
| `payroll.deployment.mode` | `single` | 4 | `single` 保留本地降级；`cluster` 禁用租约/配额/事件本地回退 |
| `payroll.chat.quota-ttl-minutes` | 35 | 4 | 配额键 TTL，必须大于 `turn-max-minutes` |
| `payroll.chat.quota-fail-mode` | `deny`（cluster）/`local`（single） | 4 | Redis 故障时配额策略 |
| `payroll.runtime.per-url-timeout-ms` | 5000 | 4 | runtime 单 URL 提交超时 |
| `payroll.runtime.submit-budget-ms` | 10000 | 4 | 整轮提交总预算，须小于租约 TTL |
| `payroll.health.readiness-deps` | `db,redis` | 4 | readiness 探测的依赖集合（不含 ES/Nacos） |
| `payroll.chat.sse-tail-threads` | 8 | 5 | SSE 尾随线程数，与 `chatStreamExecutor` 分离 |
| `payroll.chat.sse-tail-queue-capacity` | 64 | 5 | SSE 尾随等待队列 |
| `payroll.chat.event-poller-threads` | 4 | 5 | 轮次事件 poller 共享池大小 |
| `payroll.chat.sse-tail-model` | `shared` | 5 | `legacy` 保留旧订阅模型仅作灰度回退，下一版本删除 |
| `payroll.kb.query-embed-budget-ms` | 800 | 5 | KB 查询嵌入预算 |
| `payroll.kb.embed-breaker.failures` | 3 | 5 | KB 嵌入熔断失败阈值（独立于记忆熔断） |
| `payroll.kb.embed-breaker.window-seconds` | 60 | 5 | KB 嵌入失败统计窗口 |
| `payroll.kb.embed-breaker.open-seconds` | 30 | 5 | KB 嵌入熔断持续时长 |
| `payroll.kb.retrieval.max-candidates` | 2000 | 5 | 单次检索参与融合的候选上限 |
| `payroll.kb.task-threads` | 2 | 现有 | ingest 执行线程数（当前 1–8 夹取） |
| `payroll.kb.task-queue-capacity` | 64 | 5 | ingest 有界队列，满时拒绝并提示 |
| `payroll.kb.ingest.batch-size` | 100 | 5 | 行插入与向量回写批量 |
| `payroll.kb.ocr.max-pages` | 200 | 5 | 单文档 OCR 页数上限 |
| `payroll.kb.ocr.concurrency` | 2 | 5 | 单节点 OCR 并发 |
| `payroll.kb.ocr.dpi` | 200 | 5 | 渲染 DPI（原实现 300） |
| `payroll.kb.ocr.timeout-seconds` | 600 | 5 | 单文档 OCR 超时 |

---

## 9. 测试与验收

### 9.1 自动化

- 离线构建与测试：`D:\tools\apache-maven-3.6.3\bin\mvn.bat -o test`（JDK 17：`D:\tools\jdk\jdk-17.0.12`）。
- 新增测试必须覆盖失败分支，而不是只覆盖 happy path。
- `FakeNacosServer` 的失败开关必须支持“下一次请求失败”和“指定 dataId 返回状态码”两种粒度。

### 9.2 手工验收（Run 模式，不用 Debug）

1. 启动 Web 8080、runtime 8100、前端 Vite。
2. 并发编辑：两个浏览器标签同时改同一智能体 → 后者 409 → 列表刷新 → 重存成功。
3. Nacos 停止：管理台列表仍显示上次数据；保存返回 503 且不显示成功；技能文件面板不清空。
4. 对话过载：脚本并发 50 轮 → 部分 429 → 会话不悬挂，配额计数归零。
5. Ollama 停止：每轮首字不再固定多等 3s；恢复后自动召回。
6. 换密钥：管理台保存新密钥 → 下一轮日志显示模型实例重建。
7. 重启后：所有配置、激活模式、凭据与缓存行为不回归。

### 9.3 批次放行标准

| 批次 | 放行条件 |
| --- | --- |
| 1 | 全量测试通过；并发/故障注入手工验收 1–3 通过；无 500 新增 |
| 2 | 全量测试通过；压测与依赖故障验收 4–6 通过；P99 首字不劣化 |
| 3 | 备份恢复演练成功；健康探测能反映依赖；首改密端到端通过 |
| 4 | 全量测试通过；Redis/ES/runtime 故障注入通过；集群模式不双跑；readiness 语义正确 |
| 5 | 100 订阅与 50 并发压测通过；10 万 chunk 检索达标；OCR 限额与取消生效 |
| 6 | 大文档 ingest 与 ES 故障不产生尖刺；批次 1–5 无回归 |

批次 4–6 的手工验收见 §14.11。

---

## 10. 发布顺序与回滚

### 10.1 批次 1

1. 先发布 `payroll-common` + Web + runtime（`revision` 参数可选，旧前端继续工作）。
2. 再发布前端（开始携带 `revision`）。
3. 灰度期观察 `revision_missing` 日志，归零后可在下一版本改为强制必填。

回滚：

- 代码回滚即可；配置文档格式未变，无数据迁移。
- 新异常 503 不影响旧客户端（旧客户端只会看到错误提示）。

### 10.2 批次 2

- 执行器与缓存均为运行时行为，可通过配置回退（把队列改大、关闭路由缓存、关闭熔断）。
- 429 是新增状态码；前端已有的统一错误提示可显示消息，不需要同步发布。

### 10.3 批次 3

- O1 单键化保留旧文档一个版本周期，可回退到旧读取路径。
- O6 迁移为新增列，回滚代码后列保留，无破坏。

### 10.4 批次 4–6

见 §14.10：批次 4 默认 `deployment.mode=single`；探针新旧并存；批次 5 SSE 保留 legacy 模式；KB 检索与 ingest 参数均可回退。文档同步要求见 §14.10 最后一条。

---

## 11. 风险登记

| 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- |
| Nacos 读-改-写窗口仍有覆盖 | 中 | 配置丢失一次 | 窗口缩小到一次往返；409 提示；批次 3 单键化进一步收敛 |
| 旧前端不传 revision 被误判为冲突 | 低 | 保存失败 | 缺省跳过 + WARN，灰度后强制 |
| 执行器拒绝导致轮次悬挂 | 中 | 用户看到“进行中” | 拒绝即 `failTurn` + SSE error；验收覆盖 |
| 召回预算误伤慢但正常的 ES | 低 | 本轮无记忆注入 | 预算 600ms 可配置；日志可观测 |
| 路由缓存返回过期智能体 | 低 | 角色错误 | 只缓存成功决策；命中后按当前候选重绑定；TTL 5min |
| 凭证版本读取增加每轮 DB 查询 | 低 | 延迟 | 5s 缓存 + 保存时失效 |
| 首改密锁死存量账号 | 低 | 无法登录 | 存量用户 FALSE；仅新建/重置置 TRUE；提供管理台重置入口 |
| 集群模式快速失败让单 Redis 故障变成整站 503 | 中 | 可用性下降 | 部署前提写明 Redis 主从/哨兵；单机模式保留本地降级 |
| SSE 线程模型改造引入续传回归 | 中 | 断点续传失效 | legacy/shared 双模式灰度一个版本；§14.9 场景 1、3 回归 |
| KB 候选上限截断导致召回下降 | 中 | 回答质量 | 上限可配置；用 §9 金标集回归 |
| OCR 页数/超时限额误伤正常长文档 | 低 | 解析失败 | 上限可调；失败提示说明可申请提高 |
| readiness 不含 ES 时 KB 故障仍接流量 | 低 | 部分请求变慢 | KB 自身熔断与降级提示；详情页可见 ES 状态 |

---

## 12. 决策点（评审确认）

| 编号 | 决策 | 选项 | 建议 | 理由 |
| --- | --- | --- | --- | --- |
| D1 | revision 形态 | A. 数字版本字段；B. 内容 SHA-256 指纹 | **B** | 不改裸数组格式，滚动发布安全，等价 ETag |
| D2 | 缺少 revision 的处理 | A. 直接 400；B. 跳过并 WARN，灰度后强制 | **B** | 避免前后端必须同时发布；用日志决定收紧时点 |
| D3 | 写前重读失败 | A. 用旧缓存继续；B. 503 拒绝写入 | **B** | 旧缓存写回会覆盖他人修改，属于数据破坏 |
| D4 | 激活失败策略 | A. 两者都清空；B. 意图优先 + 补偿 | **B** | 用户点了哪个就以哪个为准；最坏两者都开时流程优先且可见 |
| D5 | 执行器过载策略 | A. CallerRuns；B. 拒绝并 429 | **B** | 保护 Tomcat 线程；429 语义明确 |
| D6 | 召回预算 | A. 不限制；B. 600ms + 熔断 | **B** | 对话首字优先；依赖故障不应每轮惩罚 |
| D7 | 路由缓存范围 | A. 所有决策；B. 仅成功且有 agent 的决策 | **B** | 回退决策包含瞬时故障，缓存会放大错误 |
| D8 | 首改密存量用户 | A. 全部置 TRUE；B. 仅新建/重置置 TRUE | **B** | 避免升级当天锁死现有账号 |
| D9 | 集群模式的本地降级 | A. 集群快速失败；B. 所有模式都本地降级 | **A** | 租约降级到本地会让两个节点同时跑同一会话，模型重复计费且回答交错；单机模式保留降级体验 |
| D10 | SSE 线程模型 | A. 独立尾随池 + 事件驱动订阅；B. 维持每流占一个执行线程 | **A** | 当前 4 线程 + 32 队列就是单节点全部对话容量；尾随不该和执行抢线程 |
| D11 | Redis 故障中的轮次判活 | A. 用 DB 心跳判活 + 落库兜底并明确提示；B. 45s 无事件即 expire | **A** | 事件总线故障不等于模型停了；误 expire 会丢一个已经算完的回答 |
| D12 | readiness 的依赖范围 | A. 仅 DB + Redis；B. 含 ES/Nacos/模型 | **A** | ES 抖动只影响 KB 召回，不应把还能对话的节点摘掉 |
| D13 | runtime 容量与排队 | A. 并发可配置 + 返回排队位置/429；B. 维持固定 4 线程无排队信息 | **A** | CPU 推理 1–3 分钟/轮，用户需要知道“前面还有几个”而不是盲等 |
| D14 | 模型连接故障切换 | A. 不切换，快速失败；B. 同平面按优先级连接级切换一次 | **B（批次 6）**；批次 5 先落 A | 单连接故障会整轮失败；切换一次即可覆盖进程重启与单 IP 故障，避免放大成本 |

---

## 13. 里程碑

| 里程碑 | 内容 | 预估 |
| --- | --- | --- |
| M1 | C1 + C2 + C5（读取语义与缓存保留） | 1 人日 |
| M2 | C3 + C4 + 前端 + 测试（并发编辑闭环） | 1–1.5 人日 |
| M3 | R1 + R5（容量与列表完整性） | 1 人日 |
| M4 | R2 + R3 + R4（依赖降级与缓存正确性） | 1.5–2 人日 |
| M5 | O1–O6（运维与安全基线） | 随迭代 |
| M6 | 批次 4（V1–V5：集群降级、事件判活、readiness、配额、提交预算） | 2–3 人日 |
| M7 | 批次 5 并发面（W1–W2：SSE 尾随池、poller 共享池） | 2 人日 |
| M8 | 批次 5 依赖与 KB 面（W3–W6：检索瘦身、ingest、OCR、模型快速失败） | 2–3 人日 |

批次 1 合并前必须完成 M2；批次 2 可与批次 1 的灰度期并行开发，但发布顺序保持 1 → 2。批次 4 必须在任何“多 Web 节点 + 共享 Redis”的部署之前上线；批次 5 可与批次 4 并行开发，发布顺序 4 → 5。批次 6 不设里程碑，随迭代消化。

---

## 14. 复核：性能、高并发与可用性（2026-11-22）

> 本节来源两次独立评审：一次以产品经理 + 资深架构师视角看知识库解析与对话性能，一次专门看高并发与可用性。它不改变批次 1–3 的范围与顺序，只新增批次 4–6。批次 1–2 已按 §5/§6 实现并通过 §6.6 测试（1184 项、0 失败、3 跳过），已随 `5297c6c` 提交；本节结论不要求回滚这两批。

### 14.1 复核结论

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | 单节点对话容量被 SSE 尾随锁死在 36 路，且第 37 路直接 429；Web 与 runtime 同进程部署时还要对半分 | §14.4 H-1 |
| 2 | 集群模式在 Redis 故障时会脑裂：同一会话可被两个节点同时执行，fencing 只保护 DB 写入，不阻止重复模型调用与重复计费 | §14.5 A-1 |
| 3 | 轮次中途 Redis 抖动会让 Web 在 45s 后把仍在执行的轮次判死，已算完的回答可能不落库或不回放 | §14.5 A-2 |
| 4 | KB 检索成本与库大小线性相关，且每次查询把整系列 chunk 连同 768 维向量全部加载；10 万 chunk 单次可构造数百 MB 临时对象 | §14.3 P-1、§14.4 H-6 |
| 5 | 真正限制并发的是模型算力而非线程池：单 runtime 节点 4 并发、CPU 推理 1–3 分钟/轮，只有 1.3–4 轮/分钟 | §14.4 H-3、§14.9 |
| 6 | 健康检查与服务编排语义相反：`/health` 恒 UP，而按当前部署文档开启 ES 探测后，Actuator 会因 ES 抖动把还能对话的节点摘掉 | §14.5 A-5、A-7 |
| 7 | 建议顺序：批次 4（可用性 P0）→ 批次 5（并发与 KB）→ 批次 6（效率） | §14.6–14.8 |

### 14.2 容量基线（现有默认值）

| 资源 | 默认 | 证据 | 直接含义 |
| --- | --- | --- | --- |
| Web 对话执行器 | 4 线程 + 32 队列 | `PayrollCoreConfiguration.java:774-788` | 单节点最多 36 路进行中的 stream/resume，第 37 路 429 |
| runtime 执行器 | 同池（all-in-one 时与 Web 共用） | 同文件；`InternalTurnController.java:114-173` | 一台机器实际同时只有 4 个模型调用 |
| 每轮事件 poller | 1 个裸线程 | `TurnEventFanout.java:74` | 100 个活跃轮次 = 100 个阻塞线程 |
| Redis 连接池 | max-active 16 | `application-redis.yml:15-19` | 阻塞读与普通命令争抢同一批连接 |
| MySQL 连接池 | 16 | `application-mysql.yml:19` | 慢查询会同时拖住心跳与失联扫描 |
| KB ingest | 2 线程 + 无界队列 | `KbConfiguration.java:19-28` | 上传洪峰无限堆积，进度不可信 |
| KB 预取 | 4 线程 + 无界队列，超时不取消 | `KbContextPrefetcher.java:48`、`:126-145` | 慢检索仍占用线程与 Ollama |
| 轮次租约 | TTL 30s、心跳 10s | `TurnLeaseService.java:47-64` | 任何超过 30s 的同步动作都可能让轮次被判失联 |
| 单轮绝对上限 | 30 分钟 | `PayrollCoreConfiguration.java:155`、`:580` | 配额 TTL 15 分钟短于轮次上限（§14.4 H-4） |

### 14.3 性能复核（P-1–P-17）

| ID | 位置 | 现状 | 影响 | 处置 |
| --- | --- | --- | --- | --- |
| P-1 | `KbRetrievalService.java:239-245`、`:283-284`；`KbChunkStore.java:23-26` | 每次检索 `findActiveBySeries` 取整系列全部 chunk，SELECT 列含 `vector` | 每 chunk 向量 JSON 约 6KB，10 万 chunk 单次可构造数百 MB 字符串与浮点数组；延迟、GC 随库线性恶化 | 5（W3） |
| P-2 | `KbRetrievalService.java:465`、`:548`、`:709`、`:852` | 多路召回对同一候选反复分词 | CPU 随候选数 × 路由数增长 | 5（W3） |
| P-3 | `KbRetrievalService.java:142`；`KbSkillService.java:258-270`；`KbEmbeddingService.java:42-44` | 每个序列单独嵌入一次，单次 15s 超时 | 5 个序列 = 5 次串行模型调用，最坏 75s | 5（W3） |
| P-4 | `KbIngestService.java:224-252`、`:276` | chunk 逐行 INSERT，向量逐行 UPDATE | 1000 chunk ≈ 2000 次数据库往返 | 5（W4） |
| P-5 | `KbEmbeddingService.java:50-67` | 任一批嵌入异常即整批写 HASH 向量，并标记不可用 30s | 检索质量静默劣化，且无法从任务状态看出 | 5（W4） |
| P-6 | `KbElasticsearchService.java:127-177` | 全部向量拼成一次 `_bulk?refresh=true`，15s 超时 | 大文档单请求数百 KB–MB，强制刷新放大段合并 | 6（X1） |
| P-7 | `KbParserService.java:162` | `Files.readAllBytes` 计算 SHA-256；上传上限 600MB | 解析峰值约为文件大小 2 倍 | 6（X3） |
| P-8 | `KbOcrService.java:76-89`、`:111`、`:126`、`:144-149` | 首次探测结果永久缓存；300DPI 串行逐页 | 200 页扫描件 10 分钟以上；装好 OCR 也要重启才恢复 | 5（W5） |
| P-9 | `KbTaskService.java:444-471` | 任务列表逐条查文档名与操作人 | 一页 20 条约 40 次查询 | 6（X2） |
| P-10 | `ChatSessionManager.java:880-901`；`RedisTurnEventBus` 每事件 Lua | 事件先序列化测长、再序列化写流；每 token 一次 Redis 脚本 | 首字后每 token 两次 JSON 序列化 + 一次往返 | 5（W2） |
| P-11 | `PayrollChatService.java:697` | 整个 `run()` 持会话锁 | 同会话第二个问题静默等待 1–3 分钟 | 确认产品语义；批次 5 改为明确提示“上一条仍在回答” |
| P-12 | `PayrollAgentFactory.java:84`、`:90-96`、`:1522-1537` | 模型实例全局 LRU 上限 16，构建持类级锁 | 多平面并发重建串行；16 条易被挤出导致重复构建 | 6（X6） |
| P-13 | `PayrollChatService.java:735-736`、`:868-880`、`:944-945` | 路由（轻量 LLM）、KB 预取、上下文摘要、记忆召回在首字前串行 | 首字前最坏叠加数十秒（路由 0.7–10.3s + 预取 2s + 摘要 + 召回 600ms） | 5（W3：合并首字前预算 + 查询嵌入复用，不盲目并行 CPU 嵌入） |
| P-14 | `KbContextPrefetcher.java:48`、`:126-145` | 4 线程无界队列；`future.get(timeout)` 超时后不 `cancel` | 慢检索持续占用线程与 Ollama，队列可无限增长 | 5（W3） |
| P-15 | `KbElasticsearchService.java:255-278` | 已按 `seriesId` + `chunkLevel` 过滤，但仍在系列内对全部 chunk 做 `script_score + cosineSimilarity` | 单系列越大，ES 向量查询越慢；CPU 随 chunk 数线性上升 | 6（X1：评估 `knn` 检索或 topK 预筛） |
| P-16 | `InMemoryTurnEventBus.java:25-40` | `CopyOnWriteArrayList` + `remove(0)` 裁剪 | 降级路径下每次追加 O(n)，事件接近上限后明显抖动 | 6（X4） |
| P-17 | `KbIngestService.java:104-111`；取消仅在阶段边界生效 | 进度按解析阶段粗粒度更新，取消不中断当前阶段 | 长文档看似卡住；用户取消后仍继续消耗 OCR/嵌入 | 5（W4/W5） |

### 14.4 高并发复核（H-1–H-8）

| ID | 位置 | 现状 | 量化影响 | 处置 |
| --- | --- | --- | --- | --- |
| H-1 | `ChatSessionController.java:391-432`、`:583-594`；`PayrollCoreConfiguration.java:774-788` | `stream`/`resume` 在整个轮次内占用 `chatStreamExecutor` 线程 | 4 + 32 = 36 路上限；第 37 路 429；all-in-one 时 Web 与 runtime 互挤 | 5（W1/W2） |
| H-2 | `TurnEventFanout.java:74`；`application-redis.yml:15-19` | 每轮 1 个 daemon 线程阻塞读；每个订阅者在 `await` 中各自占线程 | 100 活跃轮次 = 100 poller + 100 SSE 线程；Redis 并发阻塞读受 16 连接限制 | 5（W2） |
| H-3 | `PayrollCoreConfiguration.java:774-788`；`multi-node-deploy.md:195` | runtime 同时 4 轮；CPU 推理 1–3 分钟/轮 | 单节点 1.3–4 轮/分钟；队列只有长度没有位置与 ETA | 5（W6） |
| H-4 | `ChatQuotaService.java:30`、`:36-45`、`:46-51`、`:54-71` | INCR 与 EXPIRE 非原子；TTL 15 分钟短于轮次上限 30 分钟；故障时按进程回退；acquire/release 可能落在不同后端 | EXPIRE 失败则键无 TTL；计数泄漏至少 15 分钟；N 节点集群上限放大 N 倍；同一用户可超过 2 路 | 4（V4） |
| H-5 | `KbRetrievalService.java:142`；`KbQueryTools.java:32`、`:134`；`KbContextPrefetcher.java:126-145` | KB 查询嵌入 15s 超时且无熔断；预取 `future.get` 超时后不 `cancel`，工具 8s 超时只结束等待 | 调用方已返回，后台仍可能占用 Ollama 与预取线程 | 5（W3） |
| H-6 | 同 P-1、P-2；ES 故障时转本地余弦 | 全量检索 + 重复分词；ES 不可用退化为进程内扫描 | 延迟与 CPU 随库大小增长；ES 故障直接表现为对话变慢 | 5（W3） |
| H-7 | `PayrollAgentFactory.java:1522-1537`；`ModelConnection` 无备用连接 | 每个平面单连接，未发现运行时故障切换 | 模型进程重启或单 IP 故障 = 整轮失败 | 6（X6，D14） |
| H-8 | `ChatSessionManager.java:284-305` | 心跳、失联扫描、清理共用一个调度线程 | 慢 SQL 同时延迟心跳与扫描，可能误判失联 | 6（X5） |

### 14.5 可用性复核（A-1–A-9）

| ID | 位置 | 故障场景 | 后果 | 处置 |
| --- | --- | --- | --- | --- |
| A-1 | `TurnLeaseService.java:72-93`、`:157-167`；`ChatQuotaService.java:46-51`；`RedisTurnEventBus` 降级分支 | Redis 不可用 | 租约回退到进程内 → 两个 Web 节点可同时执行同一会话；事件各写各的内存；配额按节点各算一份 | 4（V1） |
| A-2 | `ChatSessionController.java:730-737`；`TurnLeaseService.java:157-167` | 轮次中途 Redis 抖动或 runtime 事件写失败 | 45s 无事件且 `leaseAlive=false` → `expireTurn`；已算完的回答被判死，前端只看到“回答中断” | 4（V2） |
| A-3 | `multi-node-deploy.md:26`、`:35-36`；`docker/docker-compose.yml:87` | Redis 重启或 AOF 不可用 | compose 已开 AOF，但未显式设 `appendfsync`（默认 everysec，极端情况丢约 1s 事件）；裸机部署若未开 AOF、或磁盘满转为只读，Session/租约/事件流会同时丢 | 4（V3 改为启动时探测持久化与写入能力） |
| A-4 | 仓库无备份脚本；部署文档仅手工流程 | MySQL 单实例故障 | 用户、会话、任务全丢 | 3（O4） |
| A-5 | `multi-node-deploy.md:50` | ES 抖动 | `MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=true` 使 `/actuator/health`（及旧编排使用的 `/health`）503，编排摘掉仍能对话的节点 | 4（V3：`/readyz` 不含 ES） |
| A-6 | `multi-node-deploy.md:15-17`；`KbEmbeddingService.java:50-67`；`KbContextPrefetcher.java:126-145` | Ollama 不可用 | 若控制面模型也走 Ollama，整轮无法回答；若走外部 API，KB 预取按 `STATUS_TIMEOUT` 降级，但 `kb_search` 工具仍无熔断、每次等满超时，且缺少统一的“本次未用知识库”提示 | 5（W3/W6） |
| A-7 | `PayrollWebController.java:105-126` | 任何依赖故障 | `/health` 恒为 UP 且返回数据库地址：编排看不出故障，地址外泄 | 4（V3，合并 O3） |
| A-8 | `AgentRuntimeClient.java:35-50`、`:98-131`；`TurnLeaseService.java:47-64` | runtime 节点全挂 | 每 URL 15s 逐个尝试，3 个 URL 最坏 45s > 租约 30s：租约先过期，提交仍在重试 | 4（V5） |
| A-9 | 内部签名校验时间窗（默认 ±120s，见 `InternalTurnController` 鉴权分支） | 节点时钟漂移超过窗口 | runtime 全部 401，表现为“全部节点不可用” | 6（X7） |

### 14.6 批次 4（P0，可用性止血）

| ID | 项 | 设计要点 | 验收 |
| --- | --- | --- | --- |
| V1 | 部署模式显式化与故障语义 | 新增 `payroll.deployment.mode=single\|cluster`。`cluster` 下租约、配额、事件总线不再本地回退：Redis 不可用时租约返回 503、事件写入失败上抛，由 `/readyz` 503 让编排摘流；`single` 保留现有降级 | 双 Web + 停 Redis：新轮次 503，不出现两条模型调用；`single` 行为不变 |
| V2 | 轮次中断的判活契约 | 事件缺失时先读 `chat_turns.heartbeat_at`（runtime 10s 心跳，`H2ChatTurnStore.java:331-348`）；心跳新鲜则继续等；DB 心跳也超过 `staleMillis` 才 expire；终态已落库则回放最终消息；缺口提示“回答已保存，请刷新” | 轮次中 kill Redis 10s：不误 expire；恢复后按 `Last-Event-ID` 续传；已落库回答不丢 |
| V3 | liveness/readiness 拆分 | 统一对外契约为 `/livez`、`/readyz` 两个薄别名，内部映射 Actuator 的 `health/liveness`、`health/readiness` 分组；`/actuator/health` 与现有 `/health` 保留 200/503 兼容但删除地址等敏感字段。`/livez` 只看进程；`/readyz` 查 DB + Redis（`payroll.health.readiness-deps`）；ES/Nacos 只进详情不进 readiness。Redis 探测同时校验持久化与写入：启动时优先 `CONFIG GET appendonly`，被托管 Redis 禁用时退化为 `INFO persistence` 的 `aof_enabled`，两者都拿不到则告警；运行期每 30s 做 `SET`+`EX` 写探针。`single` 只 WARN，`cluster` 视为硬前提（见 A-3） | 停 ES：`/readyz` 200；停 Redis 或 AOF 关闭（cluster）：`/readyz` 503；`/health` 响应无地址与用户名 |
| V4 | 配额原子化 | 单条 Lua 完成判限 + 许可写入 + 过期（`HLEN` / `HSET` / `PEXPIRE`）；TTL 取 `quota-ttl-minutes`（35）> 轮次上限 30；键内记录后端标记，release 只回原后端；cluster 故障拒绝新请求 | 50 并发 + Redis 重启：无泄漏；单用户第 3 路 429；轮次跨 TTL 不被提前放行 |
| V5 | 提交预算 | 每 URL 5s、总预算 10s（`runtime.per-url-timeout-ms` / `runtime.submit-budget-ms`）；超预算立即 `failTurn` 并释放租约；保留 429/503 映射 | 3 个 runtime 全挂：10s 内失败；30s 内租约释放；用户看到“服务暂不可用” |

> 实现状态（2026-11-22）：V1–V5 已编码并通过离线全量测试（`mvn -o test`：1205 tests、0 failures、3 skipped；已提交 `3816d02` 并推送 `feature/dev-team`）。
> 代码入口与落地决策：
>
> - **V1**：`DeploymentMode` + `PayrollCoreConfiguration.deploymentMode()`（`payroll.deployment.mode`）；`TurnLeaseService` 在 cluster 下取租约失败直接抛不可用（503 语义），`RedisTurnEventBus` 写失败上抛，`ChatQuotaService` 不再回退进程内计数；`TurnMetrics` 所有指标统一带 `deployment.mode` 标签。
> - **V2**：`ChatSessionManager.heartbeatFresh()` 以 `chat_turns.heartbeat_at` 为准（`staleMillis = max(30s, 3×heartbeat)`），事件缺失先看心跳，心跳也过期才 expire；`H2ChatTurnStore` 心跳写入与失联扫描同源（`COALESCE(heartbeat_at, updated_at)`）。
> - **V3**：`HealthEndpointsController` 提供 `/livez`、`/readyz` 薄别名（分别映射 Actuator `health/liveness`、`health/readiness`），仅 `UP` 返回 200；`PayrollReadinessHealthIndicator` 只查 `db` + `redis`（`payroll.health.readiness-deps`），cluster 下 Redis 不可达/持久化未知/AOF 关闭判 DOWN，single 仅 DEGRADED；`RedisHealthProbe` 30s `SET`+`EX` 写探针，持久化优先 `CONFIG GET appendonly`、退化 `INFO persistence`；`PayrollWebController.health()` 状态跟随 readiness，只回 `db` 依赖，不再输出地址/用户名。
> - **V4**：`ChatQuotaService` 单条 Lua 完成 `HLEN` 判限 + `HSET` permit + `PEXPIRE`，TTL 取 `payroll.chat.quota-ttl-minutes`（默认 35，自动抬到轮次上限之上）；release 按 permit 前缀与 backend 标记匹配后 `HDEL`，键空再 `DEL`；cluster 下 `payroll.chat.quota-fail-mode` 强制 deny，Redis 缺失直接拒绝启动。
> - **V5**：`AgentRuntimeClient` 每 URL 超时 `payroll.runtime.per-url-timeout-ms`（5000）+ 总预算 `payroll.runtime.submit-budget-ms`（10000）；预算耗尽计数 `payroll.runtime.submit.budget.exhausted` 并抛 `PayrollUnavailableException`，由上层 `failTurn` 释放租约；409/429/503 映射保持不变。
>
> 部署侧同步：`docker/nginx.conf` 增加 `/livez`、`/readyz` 直转；`docs/multi-node-deploy.md` §2.1 与 §7/§8 给出模式能力矩阵、探针预期与 Redis AOF 前提。

### 14.7 批次 5（P1，并发与 KB）

| ID | 项 | 设计要点 | 验收 |
| --- | --- | --- | --- |
| W1 | SSE 尾随池 | `stream`/`resume` 只把首帧与订阅注册交给尾随池；`payroll.chat.sse-tail-threads=8` + `payroll.chat.sse-tail-queue-capacity=64`，满了返回 429（语义是“订阅过多”）；执行器只负责任务启动 | 100 订阅 + 20 活跃轮次：尾随线程 ≤ 8；纯续流不占执行线程；无 429 |
| W2 | 事件订阅去线程化 | 共享 poller 池（`payroll.chat.event-poller-threads=4`）按轮次轮询 Redis；事件直接写已注册的 `SseEmitter`；去掉每轮 `new Thread` 与每订阅者阻塞；结构性事件批量写 | 100 活跃轮次：poller ≤ 4 线程；单轮事件 Redis 调用从每 token 一次降为每批一次 |
| W3 | KB 检索瘦身 | 新增不含 `vector` 的元数据查询；候选按 `max-candidates` 截断后再取向量；查询嵌入一次（KB 预取与记忆召回共享同一向量）并加 800ms 预算 + 独立熔断；两条召回只并行非嵌入阶段，不并发压 CPU 嵌入；分词一次复用；超时取消底层调用；ES 故障降级为关键词并标注 | 10 万 chunk：p95 ≤ 300ms、堆增量 ≤ 200MB；停 Ollama：800ms 内返回关键词结果；预取超时不残留线程 |
| W4 | ingest 批量与背压 | 行插入与向量回写按 100/批；嵌入失败不落 HASH，任务标 FAILED 可重试；ES 失败不再 READY；队列满返回 429/503 | 1000 chunk：SQL 往返 ≤ 20；停 ES：任务 FAILED 而非 READY；队列满可见 |
| W5 | OCR 限额与取消 | 页数上限 200、DPI 200、并发 2、单文档 600s；取消时销毁子进程；探测失败只缓存 60s（按异常类型区分，不做永久缓存） | 200 页 < 15 分钟；取消后 5s 内无残留子进程；OCR 恢复无需重启 |
| W6 | runtime 容量与排队 | 并发/队列可配置；队列满返回 429 + `Retry-After` + 排队位置；模型连接建立失败 5s 内失败，不等满提交预算 | 50 并发问：超容量明确 429；模型拒绝连接 5s 内提示；前端可见排队位置 |

W6 的前后端契约（批次 5 一并落地）：

- SSE 新增 `queued` 事件：`{"type":"queued","position":3,"etaSeconds":45}`，`etaSeconds` 为按当前队列长度与平均轮次时长的估算值，仅用于展示。
- 容量拒绝统一 429 响应体：`{"code":"BUSY","retryAfterSeconds":5,"message":"当前排队已满，请稍后重试"}`，同时保留 `Retry-After` 头。
- 前端在发送被 429 时按 `retryAfterSeconds` 倒计时禁用发送按钮；收到 `queued` 时在气泡内显示排队位置，收到首个内容事件后清除。
- `queued` 纳入事件 schema 版本（P1-6）；旧前端忽略未知事件类型，不因新增事件报错。

### 14.8 批次 6（P2，效率）

| ID | 项 | 设计要点 |
| --- | --- | --- |
| X1 | ES 写入与查询 | bulk 按 5MB 分片，`refresh=false` + 末尾 `wait_for`；检索已按 `seriesId` + `chunkLevel` 过滤，但系列内仍是全量 `script_score`，评估 `knn` 检索或 topK 预筛后再精排 |
| X2 | 任务列表批量化 | 文档名、操作人改为 `IN` 一次查询 |
| X3 | 解析内存 | SHA-256 改流式摘要，去掉 `readAllBytes` |
| X4 | 降级事件总线 | `InMemoryTurnEventBus` 改环形缓冲，去掉 `remove(0)` 的 O(n²) |
| X5 | 维护线程拆分 | 心跳独立调度；失联扫描与清理各自单线程，互不阻塞 |
| X6 | 模型连接故障切换（D14） | 同平面按优先级连接级切换一次，总预算 5s；失败不重试第二次 |
| X7 | 时钟与签名 | 启动时校验 NTP 偏移，超阈值告警；签名窗口可配置 |

落地说明（批次 6，均已实现并有单测）：

- **X1**：`KbElasticsearchService` 按 UTF-8 字节累计，单批超过 5MB 先提交再继续；`_bulk?refresh=false`，全部写完追加一次 `_refresh`（超时 15s，失败只告警不影响入库结果），等价于原 `wait_for` 语义。检索保留 `script_score + cosineSimilarity`（带 `seriesId` + `chunkLevel` 过滤）：现有 mapping 未声明 `similarity: cosine`，切 `knn` 需要重建索引并改变排序，本次不采纳；留待有真实 10 万 chunk 基线后再评估 topK 预筛。
- **X2**：`KbTaskService` 先按系列聚合并集，文档名走 `KbDocumentStore.findByIds(seriesId, ids)`，操作人走新增的 `H2AdminRepository.findUsers(Collection<Long>)`（单条 `IN` 查询），缺失用户回退「用户#{id}」。
- **X3**：`KbParserService` 的 `sha256` 改为 64KB 流式摘要，不再 `Files.readAllBytes` 把整份 PDF 读进堆。
- **X4**：`InMemoryTurnEventBus` 用固定上限环形缓冲（容量按需 64→10000，写满覆盖最旧），追加与裁剪 O(1)，`earliestSeq` 直接读队头。
- **X5**：`ChatSessionManager` 拆成 `turn-heartbeat`、`turn-stale-scan`、`turn-cleanup` 三个 daemon 单线程调度器，心跳不会被失联扫描或清理阻塞；清理仅在开启保留天数时创建。
- **X6**：`FailoverModel` 包装主连接，仅在**尚未产出任何元素**且异常链含 `IOException`/`TimeoutException`、耗时 ≤5s 时切换一次；备连接由 `ModelConnectionRegistry.fallback(primary, filter)` 按注册优先级挑选，编码面只允许 `writeCapable` 连接；备连接失败直接上抛，不再发起第三次尝试；切换记 `payroll.model.failover{plane}`。
- **X7**：runtime `/internal/health` 增加 `epoch_millis`；Web 启动时 `AgentRuntimeClient.serverTimeMillis()` 取服务端时间，按请求往返中点估算偏移，超过 `payroll.internal.clock-skew-warning-seconds`（默认 60s）只记 WARN 不阻断启动；校验窗口沿用 `payroll.internal.max-skew-seconds`（默认 120s），两端需一致。

### 14.9 容量模型

| 维度 | 公式 | 现状代入 |
| --- | --- | --- |
| runtime 节点数 | `ceil(峰值并发轮次 / 4)` | 1 分钟/轮、100 并发 ≈ 25 节点；3 分钟/轮 ≈ 75 节点。这是模型算力约束，不是线程池约束 |
| Web 节点数 | `ceil(峰值同时订阅 / 100)`（批次 5 后） | 与 runtime 节点分开扩容；纯续流不消耗模型 |
| MySQL 连接 | 每节点 16 | 10 Web + 5 runtime = 240，需核对 `max_connections` 并下调单节点池或加代理 |
| Redis 连接 | 每节点 16，poller 4/节点 | 阻塞读与命令仍共享连接池，批次 5 后单轮调用次数下降 |
| ingest | 2 线程/节点，100/批 | 1000 chunk ≈ 10 次批量 SQL + 63 次嵌入调用（16/批）；批次 6 评估增大嵌入批量 |

关键验收目标：

| 场景 | 目标 |
| --- | --- |
| 单节点 100 订阅 / 20 活跃轮次 | 无 429；poller ≤ 4；SSE 尾随线程 ≤ 8；事件 p99 < 1s |
| 单节点 50 并发提问 | 容量内成功；超出 429 + `Retry-After`；无悬挂轮次；配额归零 |
| 轮次中 kill Redis | 不误 expire；回答落库或明确失败；无重复模型调用 |
| 双 Web + 停 Redis | cluster 新模式 503、single 保持本地可用；双节点不双跑 |
| runtime 全挂 | 10s 内失败，30s 内租约释放 |
| 停 ES | `/readyz` 200；KB 检索降级并提示 |
| 10 万 chunk | 查询 p95 ≤ 300ms（热缓存），堆增量 ≤ 200MB |
| 200 页扫描件 | < 15 分钟，可取消，无残留进程 |
| 配额 + Redis 重启 | 计数不泄漏，单用户 2 路上限恢复 |

### 14.10 发布与回滚

- 批次 4 默认 `deployment.mode=single`，未显式切 cluster 时行为完全不变；集群部署先设 cluster，再启用快速失败。
- readiness 拆分兼容旧编排：`/actuator/health` 与 `/health` 仍返回 200/503，新编排改用 `/livez`、`/readyz`（Actuator health 分组的薄别名）。
- 配额 TTL 变更无数据迁移，旧键自然过期；Lua 与旧实现可并存一个版本。
- 批次 5 SSE 改造保留 `payroll.chat.sse-tail-model=legacy|shared`，灰度一个版本后删除 legacy。
- KB 检索瘦身不改表结构；`payroll.kb.retrieval.max-candidates` 可调回大值；`payroll.kb.ingest.batch-size=1` 回退逐行写入。
- 批次 6 无表结构与接口变更：X1 的 5MB 分片与末尾刷新、X3 流式摘要、X4 环形缓冲、X5 线程拆分均为内部实现，回滚即回退代码；X6 只注册了备连接才会切换，删除备连接即关闭；X7 的偏移自检只告警，可用 `payroll.internal.clock-skew-warning-seconds` 调整灵敏度。
- 文档同步：更新 `docs/multi-node-deploy.md`——新增 cluster 模式章节（`payroll.deployment.mode`、Redis AOF/HA 硬前提、`/livez`/`/readyz` 探针配置）、nginx 的 SSE 配置（`proxy_buffering off`、`proxy_read_timeout` 不小于轮次上限、`X-Accel-Buffering: no`、续流禁用重试）、以及 `MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED` 只影响详情不影响 readiness 的说明。

### 14.11 手工验收（Run 模式，不用 Debug）

1. 双 Web + 1 runtime + Redis + MySQL：停 Redis 10s，观察新轮次 503、老轮次不中断；恢复后续传。
2. 轮次进行中 kill Redis 并立即恢复：答案最终落库，前端刷新可见；日志无重复模型调用。
3. 停 ES：`/readyz` 仍 200，对话可继续；KB 问题提示“知识库暂不可用”。
4. 停 Ollama：普通对话可答；KB 提问 800ms 内降级并标注未用知识库。
5. 50 并发提问：超容量返回 429 且带 `Retry-After`；DB 中无 RUNNING 悬挂轮次。
6. 100 个 SSE 订阅：`jstack` 中 poller 线程 ≤ 4、尾随线程 ≤ 8。
7. 上传 200 页扫描件：15 分钟内 READY；中途取消后无 OCR 子进程残留。
8. 单用户开 3 个标签提问：第 3 个被拒；关掉一个后可再发；TTL 内计数不漂移。

### 14.12 决策与待确认

决策 D9–D14 见 §12。仍需产品确认：

| 编号 | 待确认 | 建议 |
| --- | --- | --- |
| Q1 | cluster 模式是否在下一版本默认打开 | 否；先以显式配置在预发验证一个迭代 |
| Q2 | OCR 200 页上限是否按租户可调 | 先全局可配，出现真实需求再做租户级 |
| Q3 | ES 是否完全退出 readiness | 是；ES 只影响 KB 召回，不应让对话节点摘流 |
| Q4 | 同会话第二条提问的交互 | 明确提示“上一条仍在回答”，提供“取消并提问”，不静默排队 |

### 14.13 指标与告警

沿用现有 Actuator/Prometheus 通道，批次 4–6 需补齐以下指标；表内为 Micrometer meter ID，Prometheus 导出时 `.` 转 `_`、counter 追加 `_total`（如 `payroll.chat.quota.rejected` → `payroll_chat_quota_rejected_total`）。标注「待补」的随对应批次落地；阈值按单节点基线给出，集群按节点聚合。

| 指标（Micrometer ID） | 类型 | 告警阈值 | 对应 |
| --- | --- | --- | --- |
| `payroll.chat.quota.rejected` | counter | 5 分钟内 > 20 持续告警 | V4、W6 |
| `payroll.chat.stream.rejected`（reason=executor\|tail\|runtime） | counter | tail 占比 > 50% 时提示扩容 | W1、W6 |
| `payroll.chat.turn.expired`（reason=no-events\|deadline\|lease） | counter | 15 分钟内 > 0 且 reason=no-events 即告警 | V2 |
| `payroll.chat.lease.renew.failed` | counter | 1 分钟内 > 0 | V1、V2 |
| `payroll.chat.event.poll.lag`（待补） | gauge | p99 > 3s | W2 |
| `payroll.kb.query.embed.breaker.open`（待补） | gauge (0/1) | 1 持续 5 分钟 | W3 |
| `payroll.kb.retrieval.duration`（待补） | histogram | p95 > 300ms | W3、X1 |
| `payroll.kb.ingest.queue.depth`（待补） | gauge | 持续 > 80% 容量 5 分钟 | W4 |
| `payroll.kb.ocr.failures`（待补） | counter | 10 分钟内 > 5 | W5 |
| `payroll.readyz.status` / `payroll.readyz.failing`（dep=db\|redis） | gauge | 非 UP 持续 2 分钟 | V3 |
| `payroll.redis.persistence.ok` | gauge (0/1) | 0 时告警（cluster 为致命） | V3、A-3 |
| `payroll.runtime.submit.budget.exhausted` | counter | 5 分钟内 > 0 | V5 |
| `payroll.model.failover`（plane=控制面\|编码面） | counter | 持续增长时检查主连接 | X6 |

要求：所有指标带 `deployment.mode` 标签，便于区分单机与集群的降级行为。

### 14.14 不做

- 不引入 K8s/HPA、多地域部署或 MySQL 读写分离。
- 不做 ES 集群运维、分片规划与冷热分层。
- 不在 JDK 17 上引入虚拟线程重构 SSE。
- 不用消息队列替换 Redis Streams；批次 4–6 只收紧现有组件的故障语义。
