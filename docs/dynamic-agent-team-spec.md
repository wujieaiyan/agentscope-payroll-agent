# 动态团队智能体规格（Dynamic Agent Team Spec）

- 状态：**P0–P2 已实现并验收（2026-11-10）**；P3 为后续增强。
- 日期：2026-11-09
- 适用系统：`agentscope-payroll-agent`
- 关联文档：[agent-orchestration.md](agent-orchestration.md)、[agent-management.md](agent-management.md)、[agent-runtime-cache-spec.md](agent-runtime-cache-spec.md)、[multi-agent-handoff.md](multi-agent-handoff.md)

---

## 1. 背景

当前薪酬智能体有三条执行路径：

1. 固定三阶段流水线：计算 → 验证 → 入账（`PayrollChatService.legacyReply`）；
2. Nacos 中人工编排的静态流程：由 `AgentFlowExecutor` 执行 DAG；
3. 单个全局核算智能体：`NacosAgentRegistry.activeAgent()`。

智能体目录、技能、流程都已经可配置，但“用哪个智能体、按什么拓扑协作”仍由人预先编排。
本需求希望：**用户用自然语言提出诉求后，系统结合智能体目录、历史执行轨迹与评分，自动组队并执行**，
同时保持薪酬场景最关键的确定性、可解释与可回退。

核心原则：

> **大模型负责理解与提议，确定性引擎负责选角、校验与执行。**
> 模型不直接决定分数，不直接计算金额，也不能绕过强制复核角色。

---

## 2. 目标与非目标

### 2.1 目标

1. 从自然语言中抽取团队任务意图（任务类型、员工/部门、期间、风险级别、是否需要复核）。
2. 基于智能体目录（Nacos）与历史轨迹，自动生成一个可执行的团队计划（角色 + 智能体 + DAG）。
3. 选角依据“能力匹配 + 客观运行统计 + 用户反馈评分”，并支持冷启动。
4. 动态计划编译为临时 `FlowDefinition`，复用现有 `AgentFlowExecutor` 执行，不引入第二套编排引擎。
5. 每次组队、每个节点执行、每条反馈都可追溯、可回放、可对比。
6. 任何异常都能回退到现有静态链路，且用户无感。
7. 先影子运行（shadow）收集证据，再逐步放开。

### 2.2 非目标（本规格不做）

1. 不让模型生成金额、税率或任何薪酬计算结果；金额仍只来自确定性工具。
2. 不允许模型创建不存在的智能体、技能或工具权限。
3. 不允许动态团队执行“发薪、审批、批量入账”等写操作，v1 仅覆盖查询/核算/解释类只读诉求。
4. 不替换人工流程编排；动态团队是“自动生成候选流程”，不是流程管理器的替代品。
5. 不引入向量数据库做选角评分；v1 用 SQL 聚合统计，ES 仅作为可选的相似轨迹召回增强。
6. 不做跨租户/跨用户的个性化评分。

---

## 3. 现状盘点（可复用资产与缺口）

| 能力 | 现有实现 | 复用/缺口 |
| --- | --- | --- |
| 智能体目录 | `NacosAgentRegistry`，`AgentDefinition`：id/name/tags/skillIds/visibility/status | 直接复用；需补“能力标签” |
| 技能目录 | `NacosSkillRegistry`、`RoleSkills`、`SkillRepositories` | 用于能力画像与技能可用性校验 |
| 流程模型 | `FlowDefinition` / `FlowNode` / `FlowEdge` | 动态计划编译目标 |
| 流程校验 | `FlowValidator` | 复用并扩展团队级规则 |
| DAG 执行 | `AgentFlowExecutor.execute(...)`，并行就绪节点、条件分支、上游记录注入 | 直接复用 |
| 节点运行 | `PayrollChatService.executeFlowNode`：独立 `RuntimeContext` + `ToolTraceRecorder` | 需从私有方法抽成可复用执行入口 |
| 智能体运行时 | `PayrollAgentFactory` + `HarnessAgent`，按定义缓存 | 直接复用 |
| 工具追溯 | `chat_tool_traces`：tool/agent(stageKey)/args/result/state/duration/amounts | 已有数据基础；缺少 agentId 与 runId 关联 |
| 历史评分 | **不存在**（仅有员工绩效考核 `performance_reviews`） | 全新建设 |
| 用户反馈 | **不存在** | 全新建设 |
| SSE 事件 | `ChatStreamListener` + `ChatSse`：agent_start/agent_text/phase/tool_*/text/done | 需新增团队计划事件 |
| 审计 | `AuditService` + `audit_log` | 新增团队相关事件 |
| 权限 | `AuthInterceptor` + `@RequirePermission` | 新增统计/团队管理权限 |

关键约束（来自现有实现）：

- `chat_tool_traces.agent` 目前存的是阶段 key（`calculator` / `validator` / `accountant` / 流程节点 id），
  不是 `AgentDefinition.id`。动态团队必须建立 `runId + stageKey → agentId` 的映射，否则无法归因评分。
- `HarnessAgent` 单次生成受 `numPredict`、阶段超时（150s）与重复工具调用保护约束；
  组队阶段也必须设置独立超时与修复次数上限，避免把“理解意图”变成新的死循环点。
- 现有模型（本机 qwen2.5:3b）对复杂结构化输出不稳定，因此计划生成必须“模板约束 + 确定性兜底”，
  不能把拓扑完全交给模型自由发挥。

---

## 4. 端到端流程

```
用户消息
  │
  ├─ 0. 会话/权限/记忆上下文准备（现有）
  │
  ├─ 1. TeamIntent 抽取（LLM，结构化，失败可回退规则）
  │      taskType / 对象 / 期间 / 风险 / 是否需要复核 / 写操作标记
  │
  ├─ 2. 能力索引 + 候选人过滤（确定性）
  │      仅 ONLINE + PUBLIC + 技能可用；按能力命中过滤
  │
  ├─ 3. 候选人排序（确定性）
  │      finalScore = 能力匹配 + 客观轨迹分 + 用户反馈分（带冷启动先验）
  │
  ├─ 4. 团队计划生成（模板约束 + LLM 在候选集内选角）
  │      输出 TeamPlan（角色、agentId、DAG、条件、理由）
  │
  ├─ 5. 计划校验（确定性 PlanValidator）
  │      不通过 → 一次修复 → 仍失败 → 回退
  │
  ├─ 6. 编译为临时 FlowDefinition
  │
  ├─ 7. AgentFlowExecutor 执行（现有 DAG 引擎）
  │
  ├─ 8. 落库：TeamRun + AgentRunRecord + trace 关联
  │
  ├─ 9. 流式返回 + 团队卡片 + 反馈入口
  │
  └─ 10. 反馈/轨迹聚合 → 更新评分 → 影响下次选角
```

模式：

| 模式 | 行为 | 用途 |
| --- | --- | --- |
| `OFF` | 完全走现有静态链路 | 默认/紧急关闭 |
| `STATIC` | 只记录静态链路运行（P0 数据地基），不组队 | 积累历史轨迹与评分 |
| `SHADOW` | 正常返回静态结果；后台额外生成并执行动态计划（按采样率），只记录不展示 | 收集对比数据 |
| `ACTIVE` | 动态计划成功则返回动态结果，失败回退静态 | 灰度放量 |

建议默认 `OFF`；P0 开启 `STATIC`；P1 开启 `SHADOW`；P2 按白名单切 `ACTIVE`。

---

## 5. 数据模型

### 5.1 TeamIntent

```java
public record TeamIntent(
        String taskType,              // CALCULATE / PAYSLIP_QUERY / REVIEW / POLICY_EXPLAIN / BATCH_QUERY / OTHER
        List<String> employeeIds,
        List<String> employeeNames,
        List<String> departments,
        String period,                // yyyy-MM
        String riskLevel,             // LOW / MEDIUM / HIGH
        boolean needsValidation,      // 涉及金额时必须为 true
        boolean writeAction,          // 发薪/审批/入账等，v1 直接回退
        List<String> requiredCapabilities, // PAYROLL_CALC / PAYROLL_VALIDATE / ACCOUNTING / PAYROLL_QUERY / POLICY_KNOWLEDGE
        double confidence,
        String rawText) {
}
```

与现有 `PayrollIntent` 的关系：**新增 `TeamIntent`，内部保留 `PayrollIntent` 作为薪酬字段子集**，
避免把“团队编排”字段塞进已经稳定的意图结构。见决策点 D3。

### 5.2 AgentCapabilityProfile

```java
public record AgentCapabilityProfile(
        String agentId,
        Set<String> capabilities,     // 显式声明 + 内置角色映射
        Set<String> supportedTaskTypes,
        String latencyClass,          // FAST / NORMAL / SLOW
        boolean canBeMandatoryRole,   // 是否可担任强制角色
        String source) {              // BUILTIN / DECLARED / INFERRED
}
```

能力来源优先级：

1. 内置角色映射：`calculator → PAYROLL_CALC`，`validator → PAYROLL_VALIDATE`，`accountant → ACCOUNTING`；
2. 管理台显式声明（`AgentDefinition` 新增可选字段 `capabilities`，缺省空数组，兼容旧 Nacos JSON）；
3. 技能 id / tags / description 的关键词推断（仅兜底，推断结果标记 `INFERRED`，不参与强制角色）。

### 5.3 AgentStat（评分聚合结果）

```java
public record AgentStat(
        String agentId,
        String window,                // 7d / 30d / all
        int samples,
        double successRate,
        double toolSuccessRate,
        double validationPassRate,
        double timeoutRate,
        double emptyOutputRate,
        long p50LatencyMs,
        long p95LatencyMs,
        int upVotes,
        int downVotes,
        double objectiveScore,        // 0-100
        double feedbackScore,         // 0-100
        double finalScore,            // 0-100，含冷启动收缩
        String scoreVersion) {
}
```

### 5.4 TeamPlan

```java
public record TeamPlan(
        String planId,
        String taskType,
        String templateId,
        List<PlanNode> nodes,
        List<PlanEdge> edges,
        Map<String, String> roleToAgent,
        String rationale,
        List<String> warnings) {

    public record PlanNode(
            String id,
            String type,          // START / AGENT / CONDITION / END
            String role,          // CALCULATOR / VALIDATOR / ACCOUNTANT / QUERY / KNOWLEDGE / ...
            String agentId,       // START/CONDITION/END 为空
            String name,
            String condition,
            String conditionValue) {
    }

    public record PlanEdge(String id, String source, String target, String sourceHandle) {
    }
}
```

### 5.5 持久化表（H2，`PayrollDatabase` 增量建表）

```sql
CREATE TABLE IF NOT EXISTS agent_team_runs (
    run_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id VARCHAR(64) NOT NULL,
    session_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    mode VARCHAR(16) NOT NULL,              -- STATIC / SHADOW / ACTIVE / OFF
    task_type VARCHAR(32),
    template_id VARCHAR(64),
    plan_json CLOB NOT NULL,
    catalog_snapshot_json CLOB,             -- agent 定义 + 技能版本 + updatedAt
    score_snapshot_json CLOB,               -- 选角时的评分快照与版本
    status VARCHAR(24) NOT NULL,            -- PLANNED / RUNNING / SUCCESS / FAILED / CANCELLED / INTERRUPTED / FALLBACK / REJECTED / CLOSED
    fallback_reason VARCHAR(256),
    baseline_run_id BIGINT,                 -- shadow 对比用
    fallback_of_run_id BIGINT,              -- 回退后新起的静态运行
    retry_of_run_id BIGINT,                 -- 重试关联
    is_primary BOOLEAN NOT NULL DEFAULT FALSE, -- 同一消息在聊天页展示的正式运行
    total_ms BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP,
    archived_at TIMESTAMP,
    deleted_at TIMESTAMP                    -- 软删除，不物理清除审计元数据
);

CREATE TABLE IF NOT EXISTS agent_run_records (
    record_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    stage_key VARCHAR(64),                  -- 与 chat_tool_traces.agent 兼容的阶段 key
    agent_id VARCHAR(64),
    role VARCHAR(32),
    status VARCHAR(24) NOT NULL,            -- SUCCESS / FAILED / TIMEOUT / EMPTY / SKIPPED
    tool_calls INT NOT NULL DEFAULT 0,
    tool_errors INT NOT NULL DEFAULT 0,
    validation_passed BOOLEAN,
    output_chars INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    trace_ids_json CLOB,                    -- chat_tool_traces.trace_id 列表
    input_digest VARCHAR(64),               -- 消息快照摘要，默认不存全文
    message_json CLOB,                      -- 可选：TeamMessage 输入/输出快照（审计模式）
    error_message VARCHAR(512),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_feedback (
    feedback_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    rating VARCHAR(8) NOT NULL,             -- UP / DOWN
    tags_json CLOB,                         -- WRONG_AMOUNT / INCOMPLETE / SLOW / UNCLEAR
    comment VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 追溯增强：保留 agent 列兼容旧数据，新增 run_id / node_id 消除多 run 碰撞
ALTER TABLE chat_tool_traces ADD COLUMN IF NOT EXISTS run_id BIGINT;
ALTER TABLE chat_tool_traces ADD COLUMN IF NOT EXISTS node_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_team_runs_message ON agent_team_runs (message_id);
CREATE INDEX IF NOT EXISTS idx_agent_team_runs_session ON agent_team_runs (session_id);
CREATE INDEX IF NOT EXISTS idx_agent_team_runs_primary ON agent_team_runs (message_id, is_primary);
CREATE INDEX IF NOT EXISTS idx_agent_run_records_run ON agent_run_records (run_id, node_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_records_agent ON agent_run_records (agent_id, created_at);
CREATE INDEX IF NOT EXISTS idx_chat_feedback_message ON chat_feedback (message_id);
CREATE INDEX IF NOT EXISTS idx_chat_tool_traces_run ON chat_tool_traces (run_id, node_id);
```

说明：

- `chat_tool_traces` 只做增量加列：`run_id + node_id` 精确定位一次运行中的节点；
  旧数据 `run_id` 为空，仍可按 `agent` 阶段 key 查询。
- 静态链路也要写 `agent_run_records`（`run_id` 可置为 0 或使用独立 baseline run），
  这样上线动态团队前就已积累历史轨迹。
- 同一消息可能存在多条运行：影子、正式、回退、重试。聊天页只读 `is_primary=true`，
  管理端按 `message_id` 列出全部并支持对比。

---

## 6. 意图抽取

### 6.1 方式

- 使用一次独立的轻量模型调用（可复用共享模型实例），输出 JSON `TeamIntent`；
- 提示词只做分类与槽位抽取，不做金额计算、不选智能体；
- 输出做严格 JSON Schema 校验；解析失败时使用规则兜底：
  - 命中“工资/薪资/核算/个税/社保/公积金” → `PAYROLL_CALC`；
  - 命中“多少钱/明细/工资条” → `PAYSLIP_QUERY`；
  - 命中“政策/怎么算/为什么” → `POLICY_EXPLAIN`；
  - 无法判断 → `OTHER`，走静态链路。
- `period` 缺省为当前月；员工/部门缺失不阻断，交给下游澄清或查询工具。
- 置信度低于阈值（建议 0.6）或 `writeAction=true` → 不进入动态团队，走静态链路。

### 6.2 预算

- 意图调用超时 10s，`numPredict ≤ 512`，失败不重试；
- 与计划生成合计不超过 2 次额外 LLM 调用。

---

## 7. 选角与评分

### 7.1 候选过滤（硬条件）

必须同时满足：

1. `status = ONLINE`；
2. `visibility = PUBLIC`（v1 不让私有智能体进入自动团队，避免数据越权）；
3. 至少一个可用技能（Nacos 技能或内置技能可解析）；
4. 能力画像命中当前任务所需的至少一个能力；
5. 不与计划中已有智能体重复。

### 7.2 能力匹配度 `C`

- `C = 1.0`：显式声明具备所需能力；
- `C = 0.7`：技能/标签推断具备；
- `C = 0.3`：能力相邻（如具备 `ACCOUNTING` 参与 `PAYROLL_CALC` 的辅助位）；
- `C = 0`：不具备。

### 7.3 客观轨迹分 `O`

按最近 30 天、最多 200 条节点记录聚合（可按任务类型分组）：

```
successLB      = Wilson 下界(成功次数, 样本数, 95%)
toolOkLB       = Wilson 下界(无工具错误节点数, 有工具调用的节点数, 95%)
validationLB   = Wilson 下界(复核通过次数, 有复核的核算节点数, 95%)
latencyScore   = clamp(targetMs / max(p50Ms, 1), 0, 1)
stabilityScore = clamp(1 - timeoutRate - emptyOutputRate, 0, 1)

O = 100 × (0.30×successLB + 0.20×toolOkLB + 0.20×validationLB
           + 0.15×latencyScore + 0.15×stabilityScore)
```

- 使用 Wilson 下界而不是原始成功率，避免“1 次成功 = 100 分”淹没样本量；
- 目标时延按角色配置：计算 20s、复核 15s、入账 20s、查询 8s；
- 时间衰减：每条记录权重 `0.95^(ageDays/7)`，在 SQL/内存聚合时计算。

### 7.4 反馈分 `F`

```
F = 100 × (up + α) / (up + down + α + β)
```

- 先验 `α = 3, β = 1`（略偏正向，避免首条差评直接归零）；
- 差评标签权重：`WRONG_AMOUNT × 3`、`INCOMPLETE × 2`、其余 × 1；
- 只统计当前智能体被真正执行的轮次，未参与本轮的消息反馈不计入。

### 7.5 冷启动收缩

样本不足时向角色先验收缩：

```
finalScore = (n × empirical + k × prior) / (n + k)
```

- `k = 20`；
- 内置角色先验：计算 78、复核 80、入账 75、查询 70、知识 70；
- 自定义智能体先验 65；
- `n < 5` 时只用于展示，不参与排序的强淘汰。

### 7.6 综合排序

```
rankScore = 0.50×C + 0.30×(O/100) + 0.20×(F/100)
```

排序只用于生成 **Top-K 候选短名单**（每角色 Top 3），不直接把“第一名”写死：
最终由计划生成在短名单内选择，便于兼顾拓扑与角色搭配。

### 7.7 历史轨迹的使用边界

- v1：只使用聚合统计（成功率、工具错误、复核通过、时延、反馈）；
- v2：增加“相似任务轨迹召回”（同 taskType + 部门 + 期间 + 员工规模），
  可用 ES 检索历史 `agent_team_runs` 摘要，作为 `O` 的加权子项；
- 不使用模型对历史文本的主观打分。

---

## 8. 团队模板与计划生成

### 8.1 模板（确定性拓扑）

| 模板 | 适用任务 | 拓扑 | 强制角色 |
| --- | --- | --- | --- |
| `CALC_VALIDATE` | 单人/单部门核算 | 计算 → 复核 | CALCULATOR, VALIDATOR |
| `CALC_VALIDATE_POST` | 核算并生成入账建议 | 计算 → 复核 → 入账 | CALCULATOR, VALIDATOR, ACCOUNTANT |
| `QUERY_ONLY` | 工资条/明细查询 | 查询 | QUERY |
| `KNOWLEDGE_ONLY` | 政策/规则解释 | 知识问答 | KNOWLEDGE |
| `QUERY_THEN_CALC` | 先查历史再核算 | 查询 ┐ → 复核；计算 ┘ | CALCULATOR, VALIDATOR |
| `FALLBACK` | 无法归类/写操作/低置信度 | 现有静态链路 | 不适用 |

规则：

- 涉及金额（`requiredCapabilities` 含 `PAYROLL_CALC`）时，`VALIDATOR` 必须存在且位于计算下游；
- 模板决定节点数量上限（建议 5 个智能体节点、深度 4）；
- 条件节点只允许现有三种：`HAS_CALCULATION_TARGETS`、`HAS_TOOL_ERROR`、`OUTPUT_CONTAINS`；
- v1 不使用 `OUTPUT_CONTAINS` 做金额相关判断（避免文本匹配脆弱）。

### 8.2 计划生成

输入给模型的内容：

- 任务类型与模板（只给该模板的槽位）；
- 每个槽位的 Top-3 候选（id、名称、能力、排名、样本量、综合分）；
- 不允许出现候选之外的 agentId。

输出：`TeamPlan` JSON（槽位绑定 + 可选分支 + 不超过 200 字的理由）。

模型只做两件事：**在短名单内选人、选择模板允许的可选节点**。
若模型输出无法解析或越界：

1. 用校验错误做一次修复重试（`numPredict ≤ 512`，超时 10s）；
2. 仍失败则使用确定性兜底：每个槽位取排名第一，拓扑直接用模板默认值；
3. 兜底仍不可执行 → `FALLBACK` 静态链路。

### 8.3 计划校验（PlanValidator，确定性）

必须全部通过：

1. DAG 无环、START 可达、至少一条路径到 END；
2. 节点类型合法，条件节点必须有 TRUE/FALSE 两条出边；
3. 所有 `agentId` 存在、`ONLINE`、`PUBLIC`、技能可解析；
4. 强制角色齐全，且 `VALIDATOR` 在 `CALCULATOR` 下游；
5. 智能体节点数 ≤ 5，深度 ≤ 4，无重复智能体；
6. `writeAction=true` 的计划直接拒绝；
7. 计划中的工具使用不超出角色技能允许范围（沿用 `role-tools` 激活机制）；
8. 计划引用的模板/条件版本与当前代码一致。

校验失败必须返回**结构化错误列表**，用于修复重试与审计，而不是只返回一句“非法流程”。

---

## 9. 编译与执行

### 9.1 编译

`TeamPlan` → `FlowDefinition`：

- `flow.id = "dynamic-" + runId`；
- 节点 id 使用 `n1..n5`，`name` 使用“① 计算智能体”这类展示名；
- `agentId` 来自计划；
- 条件节点与边按 `sourceHandle=TRUE/FALSE` 生成；
- START 的全部出边标记为选中（与现有执行器一致）。

### 9.2 执行

复用 `AgentFlowExecutor.execute(flow, baseInput, listener, runner)`：

- `runner` 复用 `PayrollChatService.executeFlowNode` 的逻辑；
- 需要一次小重构：把流程执行入口从 `PayrollChatService` 私有方法抽为可复用组件
  （建议 `FlowReplyService` 或包内可见方法），静态流程与动态流程共用，避免复制逻辑；
- 每个节点仍使用独立 `RuntimeContext`（`sessionId + ":" + nodeId`）与独立 `ToolTraceRecorder`；
- 现有 `STAGE_TIMEOUT = 150s`、重复工具调用保护、`numPredict` 限制全部保留；
- 动态团队额外设置总预算：计划 20s + 执行 300s，超时整体回退。

### 9.3 输入拼接

沿用 `AgentFlowExecutor.buildInput`，并按 §20.4 升级为结构化 `TeamMessage`：

- 用户问题 + 所有选中路径上游节点记录；
- 上游核算对象最多带 3 个；
- 兄弟分支互不污染；
- 未携带结构化附件的旧节点仍按纯文本渲染，保证兼容。

### 9.4 结果选择

沿用现有规则：选中路径上 rank 最深的智能体输出作为最终答复；同层多答案去重拼接。
动态团队只改变“谁参与、如何连边”，不改变最终答复的合成规则。

---

## 10. 回退策略

分层回退，任何一层失败都不向用户暴露内部错误：

| 层级 | 触发条件 | 行为 |
| --- | --- | --- |
| L0 | 功能开关 OFF / 低置信度 / 写操作 | 直接走现有静态链路 |
| L1 | 意图或计划生成失败 | 确定性模板 + Top-1 选角 |
| L2 | 计划校验失败且修复失败 | 静态链路（当前激活流程 → 三阶段） |
| L3 | 动态执行超时/节点失败 | 若已有部分有效答复则返回；否则静态链路 |
| L4 | 评分/统计不可用 | 仅用能力匹配 + 内置角色先验选角 |

回退必须记录 `fallback_reason`，并在前端团队卡片中显示“已回退到标准流程”（不展示技术细节）。

---

## 11. 反馈闭环

### 11.1 显式反馈

- 每条助手消息支持 👍 / 👎；
- 差评可选标签：金额错误、内容不完整、太慢、答非所问、其他；
- 同一用户对同一消息只保留最后一次反馈（幂等 upsert）；
- 反馈只关联当前消息实际参与的智能体节点。

### 11.2 隐式信号（v2，可选）

- 同一会话内短时间内重复提问相似意图 → 弱负反馈；
- 用户手动切换到其他智能体重问 → 弱负反馈；
- 不解析用户自然语言情绪，避免脆弱的文本匹配。

### 11.3 聚合

- 夜间批处理 + 写入后增量更新；
- 统计缓存 TTL 5 分钟；
- 评分公式版本化（`scoreVersion`），计划落库时保存快照，保证可回放。

---

## 12. API 与流式事件

### 12.1 HTTP

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| POST | `/api/chat/messages/{messageId}/feedback` | 提交反馈 | 会话所有者 |
| GET | `/api/chat/messages/{messageId}/team` | 本轮团队计划、节点状态、评分快照 | 会话所有者 |
| GET | `/api/chat/sessions/{sessionId}/team-runs` | 会话内团队运行记录 | 会话所有者 |
| GET | `/api/admin/agent-stats?days=30` | 智能体评分榜 | 管理权限 |
| GET | `/api/admin/agent-team-runs?status=&from=&to=` | 团队运行审计 | 管理权限 |
| GET | `/api/admin/agent-teams/shadow-report?from=&to=` | 影子对比报告 | 管理权限 |
| POST | `/api/admin/agent-teams/{runId}/replay` | 按历史快照重放（只读） | 管理权限 |

统一使用现有 `ApiResponse` 包装与 `ApiExceptionHandler`。

### 12.2 SSE 新增事件

保持现有事件不变，新增：

| 事件 | 数据 | 时机 |
| --- | --- | --- |
| `team_plan` | `{runId, mode, templateId, agents:[{agentId, name, avatar, role, score}], rationale}` | 计划校验通过后、执行前 |
| `agent_start`（扩展字段） | 追加 `agentId`、`role` | 节点开始 |
| `team_done` | `{runId, status, fallback, totalMs, nodes:[{nodeId, agentId, status, durationMs}]}` | 执行结束 |

前端未识别的新事件应被忽略，保证旧页面不崩。

### 12.3 前端

对话页：

- 回答上方显示可折叠“本轮协作团队”卡片：头像、名称、角色、状态、耗时、评分徽标；
- 执行中显示节点骨架与当前节点；
- 回答下方显示 👍/👎 与差评标签；
- 回退时显示“已使用标准流程”；
- 样式遵循现有微信风格会话渲染约定。

管理台新增“智能体团队”页签：

- 评分榜（综合分、样本量、成功率、复核通过率、P95、趋势）；
- 团队运行列表与详情（计划 DAG、每节点耗时、trace 跳转、回退原因）；
- 影子对比（动态 vs 静态：金额一致性、复核通过率、时延、反馈）；
- 历史计划回放。

---

## 13. 可观测与审计

### 13.1 日志

统一前缀 `[动态团队]`，关键节点各一条 INFO：

```
[动态团队] 意图：taskType=CALCULATE, period=2026-10, employees=[EMP001], risk=MEDIUM
[动态团队] 候选：role=CALCULATOR, top3=[calculator(86.2/120), agent-x(71.0/8), ...]
[动态团队] 计划：template=CALC_VALIDATE, nodes=n1:calculator,n2:validator, llm=true
[动态团队] 校验通过：runId=123, agent节点=2
[动态团队] 执行完成：runId=123, status=SUCCESS, 耗时=2140ms, 回退=false
[动态团队] 回退：runId=123, reason=PLAN_INVALID
```

DEBUG 级别输出完整意图 JSON、候选清单、计划 JSON 与校验错误，便于本地排查。

### 13.2 审计事件

写入现有 `audit_log`：

- `TEAM_PLAN_GENERATED`
- `TEAM_PLAN_REJECTED`
- `TEAM_FALLBACK`
- `TEAM_FEEDBACK_RECORDED`
- `AGENT_STATS_VIEWED`（管理端可选）

### 13.3 指标

- 计划生成成功率、校验通过率、修复成功率、回退率；
- 动态 vs 静态：金额一致率、复核通过率、P95 时延、差评率；
- 每智能体：被选次数、胜出次数（最终答复贡献）、失败率；
- 额外 LLM 调用次数与耗时。

---

## 14. 安全与合规

1. **角色边界**：自动团队只能使用 `PUBLIC + ONLINE` 智能体；私有智能体不参与。
2. **工具边界**：技能激活仍通过 `role-tools` 工具组控制，计划不能授予额外工具。
3. **金额边界**：金额只来自确定性工具；复核角色强制存在；v1 禁止写操作。
4. **提示注入**：用户文本只作为意图输入；计划必须通过 JSON Schema 与 PlanValidator；
   计划理由长度受限，且不会作为下游智能体的系统提示。
5. **数据隔离**：评分只做聚合；反馈按会话所有者鉴权；管理端统计不返回对话正文。
6. **可回放**：保存目录快照、评分快照、计划 JSON；智能体被删除/下架后仍可解释历史决策。
7. **降级安全**：任何组件异常都回退静态链路，不允许“无复核直接出金额”。

---

## 15. 测试与验收

### 15.1 单元测试

- `TeamIntentParser`：中英文、缺期间、多员工、写操作、低置信度、非法 JSON；
- `CapabilityIndex`：内置映射、显式声明、推断兜底、离线/私有过滤；
- `AgentScorer`：冷启动、Wilson 下界、时延、超时、反馈平滑、零样本；
- `PlanValidator`：缺复核、复核在计算上游、环、孤立节点、离线智能体、私有智能体、
  重复智能体、超节点数、未知条件、写操作；
- `TeamPlanCompiler`：计划 → `FlowDefinition` 的黄金 JSON；
- `TeamOrchestrator`：模型失败 → 确定性兜底 → 静态回退。

### 15.2 集成测试

- 固定 5 条黄金问题（单人核算、部门汇总、工资条查询、政策解释、写操作）：
  - 动态与静态金额完全一致；
  - 动态链路必须出现复核节点；
  - 写操作必须回退；
- 计划超时、节点超时、空输出、重复工具调用 → 正确回退且 SSE 事件完整；
- 反馈幂等与权限校验；
- SSE 新事件向后兼容（旧前端忽略未知事件）。

### 15.3 影子验收指标

- 金额一致率 = 100%；
- 危险计划（缺复核/私有智能体/写操作）= 0；
- 动态复核通过率不低于静态基线 2 个百分点；
- P95 总时延增加 ≤ 3s；
- 计划生成 + 校验成功率 ≥ 98%（含确定性兜底）；
- 回退率在预热 1 周后 ≤ 5%。

---

## 16. 分阶段实施

### P0：数据地基（必须先做）

- 新表与领域模型；
- 静态链路写 `agent_run_records`（阶段 key → 内置 agentId）；
- `TraceScope` 与 `chat_tool_traces.run_id/node_id` 增量列；
- 消息 👍/👎 API 与前端；
- 统计聚合任务与评分公式（只读展示，不影响选角）。

**价值**：在动态团队上线前先积累真实轨迹与反馈，避免冷启动拍脑袋。

### P1：影子模式

- TeamIntent、能力索引、评分、模板、计划生成、PlanValidator、编译器；
- `TeamMessage` 协议与 `MessageAssembler`（含纯文本兼容）；
- `SHADOW` 模式按采样率执行动态计划，只落库；
- 影子对比报告；
- 全量单测 + 集成测试。

### P2：灰度 ACTIVE

- 白名单/按会话开启；
- SSE 团队卡片与反馈入口；
- 回退链路与审计；
- 管理台团队页签。

### P3：增强

- 相似轨迹召回（ES）；
- 成功计划另存为 Nacos 流程模板（人工确认后）；
- 评分权重与模板的可配置化；
- 多轮澄清与团队重规划；
- 评估 B 模式（supervisor/handoff）在非金额知识任务中的可行性，默认不启用。

---

## 17. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 小模型计划输出不稳定 | 计划失败/乱选 | 模板约束 + 短名单 + 确定性兜底 + 一次修复 |
| 历史样本偏差 | 好智能体被埋没 | Wilson 下界 + 冷启动收缩 + 探索比例（Top-3 中非第一名按 10% 概率尝试） |
| 影子模式成本翻倍 | LLM 压力 | 采样 10%-20%，仅对白名单/低风险任务 |
| 动态拓扑难以排查 | 线上问题定位慢 | 计划 JSON、目录/评分快照、节点级日志与 trace 关联、回放接口 |
| 评分被恶意反馈操纵 | 选角失真 | 反馈需登录、按用户限权、单消息幂等、异常用户降权（v2） |
| 智能体被删除/下架 | 历史不可解释 | 快照 + 回放；计划执行前做可用性校验 |
| 写操作误入动态链路 | 合规风险 | `writeAction` 硬拒绝 + v1 仅只读任务 + 单测覆盖 |

---

## 18. 决策点（已确认）

确认日期：**2026-11-09**。D1–D13 全部按推荐方案执行，作为 P0–P3 的实施基线。

| 编号 | 决策 | 推荐方案 | 备选 |
| --- | --- | --- | --- |
| D1 | 上线方式 | **先 SHADOW 1-2 周，再白名单 ACTIVE** | 直接 ACTIVE |
| D2 | 评分输入 | **客观轨迹 + 显式反馈都纳入，但 v1 以客观分为主（O:F = 6:4）** | 仅客观分；仅反馈分 |
| D3 | 意图模型 | **新增 TeamIntent，内部复用 PayrollIntent** | 扩展 PayrollIntent；独立两套解析 |
| D4 | 团队规模 | **最多 5 个智能体节点、深度 4、并行 ≤ 4** | 最多 6 个；不限 |
| D5 | 计划生成自由度 | **模板约束 + 短名单内选角** | 完全自由生成 DAG |
| D6 | 成功计划固化 | **P3 起支持“另存为流程模板”，需人工确认** | 自动写入 Nacos |
| D7 | 写操作范围 | **v1 仅只读（查询/核算建议/解释），发薪与审批固定静态链路** | 允许动态入账 |
| D8 | 私有智能体 | **不参与自动团队** | 仅会话所有者可用 |
| D9 | 影子采样率 | **默认 15%，可按环境配置** | 100%；5% |
| D10 | 编排框架 | **AgentScope Java + DAG + TeamMessage，不引入 AutoGen/CrewAI** | Python sidecar；Harness supervisor 作为主链路 |
| D11 | 临时智能体 | **v1 不允许团队专属临时智能体，只引用目录中的 PUBLIC + ONLINE 智能体** | P3 支持 run 级临时角色 |
| D12 | 运行数据保留 | **完整内容默认保留 180 天，聚合评分长期保留，到期归档后清理** | 永久保留；90 天 |
| D13 | 删除会话时 | **团队运行记录软删除，保留审计元数据；完整消息快照随保留期清理** | 级联物理删除 |

---

## 19. 验收清单（Definition of Done）

- [x] 「决策点」D1–D13 已确认并回填本文件（2026-11-09）；
- [x] P0 表结构、静态链路运行记录、反馈 API/UI 完成；
- [x] P1 SHADOW 影子编排、报告与回退落库完成；
- [x] P2 ACTIVE 白名单、回退链路与审计、管理台团队页签完成（2026-11-10）；
- [ ] TeamIntent 解析准确率在黄金集 ≥ 90%；
- [ ] PlanValidator 负例测试全部通过，危险计划 0；
- [ ] 动态与静态在 5 条黄金问题上金额 100% 一致；
- [ ] SHADOW 报告可生成，指标达到 15.3；
- [ ] 任意失败路径均可回退且用户无感；
- [ ] 审计、日志、指标、回放接口可用；
- [ ] `TraceScope` + `chat_tool_traces.run_id/node_id` 上线，同消息多 run 不串线；
- [ ] 团队结束只解散运行时，共享智能体缓存数量不变；
- [ ] 成功、失败、超时、SSE 断开均执行幂等关闭与启动对账；
- [ ] 运行数据保留期、软删除与归档策略生效；
- [ ] README 与交接文档更新（含调试日志说明与后续 Agent 交接章节）。

---

## 20. 编排框架选型与 Agent 间消息协议

### 20.1 结论

1. **不引入 AutoGen / CrewAI，也不引入 Python sidecar。**
2. 动态团队 v1 固定采用 **DAG 数据流 + 结构化 `TeamMessage`**：
   上游节点产出 `NodeResult` → 组装为 `TeamMessage` → 仅沿选中边传给下游 → 下游在独立
   `RuntimeContext` 中消费。
3. Harness 原生的 subagent / inbox 能力作为 **P3 非金额场景增强**，不作为薪酬主链路。
4. 协同框架只解决“谁在什么时候执行、消息怎么流转”，不解决本需求的
   **历史轨迹选角与评分**；后者仍由本规格第 7 节的确定性评分层实现。

### 20.2 为什么不用 AutoGen / CrewAI

| 维度 | AutoGen | CrewAI | 当前 AgentScope Java |
| --- | --- | --- | --- |
| 运行时 | Python | Python | Java 17 / Spring Boot |
| 协同模型 | GroupChat、事件驱动 actor | Crew + Task，顺序/层级流程 | DAG 数据流 + Harness subagent |
| 接入方式 | Python 服务，Java 经 HTTP/gRPC 调用 | 同左 | 同进程调用 |
| 薪酬工具复用 | 需把 Java 工具经 MCP/HTTP 暴露，或重写 | 同左 | 直接复用 `PayrollTools` / `PayrollEngine` |
| 会话与上下文 | 需桥接 H2、`RuntimeContext`、会话隔离 | 同左 | 原生复用 |
| 追溯与审计 | 需桥接 `ToolTraceRecorder`、`chat_tool_traces`、`audit_log` | 同左 | 原生复用 |
| 流式输出 | 需跨进程转发 SSE 事件 | 同左 | 原生复用 `ChatStreamListener` / `ChatSse` |
| 失败模式 | 跨进程超时、模型配置双份、日志分散 | 同左 | 单进程、单一日志链路 |
| 选角与评分 | 不提供 | 不提供 | 本规格自建（与框架无关） |

引入它们的实际结果是：把“一个 Java 进程内的确定性编排”变成“Java + Python 两套运行时”，
而金额安全、强制复核、选角评分仍然要自己实现，收益为负。

### 20.3 现有 Java 栈已具备的协同原语

| 原语 | 位置 | 用途 |
| --- | --- | --- |
| DAG 执行与数据流 | `AgentFlowExecutor.execute(...)` | 控制流、条件分支、并行、上游记录注入 |
| 节点结果 | `AgentFlowExecutor.NodeResult(text, targets, toolError)` | 结构化传递核算对象与工具错误 |
| 节点运行隔离 | `PayrollChatService.executeFlowNode(...)` | 每节点独立 `RuntimeContext` + `ToolTraceRecorder` |
| 直接调用 | `HarnessAgent.call(Msg, RuntimeContext)` / `stream(...)` | supervisor / handoff 式调用 |
| 上下文注入 | `HarnessAgent.observe(Msg)` | 把中间结果写入另一智能体上下文，不触发新一轮 |
| 子智能体 | `DefaultAgentManager.invokeAgent(...)`、`subagentFactory(...)` | 父智能体调用子智能体 |
| 持久消息总线 | `MessageBus` + `InboxMiddleware` | 收件箱、发布订阅、跨轮次投递 |
| 结构化消息 | `Msg` + `TextBlock` + `DataBlock` / `getStructuredData(...)` | 文本与结构化附件同消息传递 |

注意：AgentScope Java 2.0.1 core **没有**现成的 GroupChat / pipeline / msghub 管理器。
若要做自由群聊，需要自己实现“选择下一个发言者”的循环；本规格不建议在薪酬链路中这样做。

### 20.4 TeamMessage 协议

#### 20.4.1 数据模型

```java
public record TeamMessage(
        String schemaVersion,        // 从 "1" 开始；回放时校验
        String runId,
        String planId,
        String fromNodeId,
        String fromAgentId,
        String fromRole,
        String toNodeId,
        String text,                 // 面向模型的可读摘要
        List<CalcTarget> targets,    // 核算对象
        boolean toolError,
        List<Long> traceIds,         // chat_tool_traces.trace_id
        Map<String, Object> artifacts, // 金额、校验结论、查询结果摘要等
        long createdAt) {
}
```

约束：

1. **不可变**：下游只能读取，不能修改上游消息；需要补充信息时组装新消息。
2. **只传选中路径**：仅沿 `edgeTaken=TRUE` 的上游边组装，兄弟分支不进入输入。
3. **金额来源唯一**：`artifacts.amounts` 只能来自工具追溯结果，禁止模型生成；
   `PlanValidator` 与复核节点都要校验其与 `traceIds` 的一致性。
4. **体积受限**：`text ≤ 8000` 字符，`traceIds ≤ 50`，`artifacts` 只放摘要，
   原始工具结果留在 `chat_tool_traces`，通过 `traceIds` 回查。
5. **兼容旧节点**：没有 `artifacts` 的 `NodeResult` 按纯文本段落渲染，不阻断执行。
6. **版本化**：`schemaVersion` 随计划一起落库，回放时按版本解析。

#### 20.4.2 与 AgentScope `Msg` 的映射

```java
Msg.builder()
        .role(MsgRole.USER)
        .name("team:" + fromAgentId)
        .text(renderedText)                       // TextBlock：模型可读
        .dataBlock("team.message", teamMessage)   // DataBlock：结构化附件
        .build();
```

- 模型侧看到 `renderedText`，其中保留现有 `【用户问题】/【上游记录】` 段落格式；
- 代码侧通过 `getStructuredData(TeamMessage.class)` 读取结构化字段；
- 不使用自然语言解析金额或核算对象，避免脆弱的文本匹配。

#### 20.4.3 渲染规则

```
【用户问题】
<原始问题>

【上游记录：① 计算智能体 / calculator】
<text>

【上游核算对象（最多处理前 3 个）】
- EMP001 | 2026-10

【上游结构化附件】
- targets: 1
- toolError: false
- traceIds: 12, 13
```

- 不把完整 `artifacts` JSON 直接塞进提示词，避免上下文膨胀；
- 需要明细时由下游通过工具按 `traceIds` 回查，或使用 `artifacts` 中的摘要字段；
- 段落标题继续去掉“①②”编号，与角色技能里的固定标题保持一致。

### 20.5 三种协同模式与适用边界

| 模式 | 控制方式 | 数据传递 | 可审计性 | 适用场景 | 本规格定位 |
| --- | --- | --- | --- | --- | --- |
| A. DAG 数据流 | 计划预定义边与条件 | `TeamMessage` 沿选中边传递 | 高：拓扑固定、可回放 | 薪酬核算、查询、复核 | **v1 唯一主链路** |
| B. Supervisor / Handoff | 主管模型决定交给谁 | `call/stream` + `observe` | 中：需记录 handoff 链 | 开放式知识任务、探索式分析 | P3 可选，禁止金额/写操作 |
| C. 群聊 / 黑板 | 选择器决定下一个发言者 | `MessageBus` / 共享 transcript | 低：轮次与成本难控 | 头脑风暴、多角色评审 | 不实现 |

#### A. DAG 数据流（默认）

- `TeamPlan` 编译为 `FlowDefinition`，控制流完全由计划和 `PlanValidator` 决定；
- 节点输入 = 用户问题 + 所有选中路径上游的 `TeamMessage`；
- 条件节点只做确定性判断，不让模型临时改变拓扑；
- 最终答复仍取选中路径上 rank 最深的智能体输出；
- 任何节点失败按 §10 回退，不把异常拓扑带入下一轮。

#### B. Supervisor / Handoff（P3）

- 通过 `subagent(...)` / `subagentFactory(...)` 注册子智能体，父智能体获得调用工具；
- 通过 `DefaultAgentManager.invokeAgent(...)` 或 `HarnessAgent.call(Msg, ctx)` 执行移交；
- 通过 `observe(Msg)` 把中间结论注入另一个智能体上下文；
- 强制约束：
  - 最多 2 次 handoff，单子智能体 `maxIters ≤ 4`；
  - 仅允许非金额、非写操作任务；
  - 必须记录完整 handoff 链（from/to/reason/traceIds）并写审计；
  - 一旦检测到金额工具调用，立即终止并回退静态链路。
- 不作为 v1 主链路：模型选择移交对象的可预测性弱于预编译 DAG。

#### C. 群聊 / 黑板（不实现）

- Harness 已提供 `MessageBus`（`inboxPush/inboxDrain/publish/subscribe`）与 `InboxMiddleware`，
  可实现持久化收件箱和共享黑板；
- 但 AgentScope Java 2.0.1 没有 GroupChat manager，需要自建发言者选择器、停止条件与轮次预算；
- 对薪酬场景而言，收益低、成本与审计风险高，明确不做。

### 20.6 运行约束

| 约束 | A. DAG | B. Handoff | C. 群聊 |
| --- | --- | --- | --- |
| 智能体上限 | 5 | 主管 + 2 子智能体 | 4 |
| 轮次/深度 | 深度 4，并行 4 | 2 次移交 | 4 轮 |
| 金额/写操作 | 允许金额（强制复核） | 禁止 | 禁止 |
| 超时 | 总预算 300s | 总预算 120s | 不适用 |
| 审计要求 | 计划 + 节点 + trace | 移交链 + trace | 不适用 |
| 上线阶段 | P1 影子，P2 灰度 | P3 评估 | 不实现 |

所有模式共同约束：

- 工具权限仍由角色技能与 `role-tools` 工具组决定，消息传递不能授权新工具；
- 每个节点/每次移交都必须有独立 `RuntimeContext` 与 `ToolTraceRecorder`；
- 目录、评分、计划、消息 `schemaVersion` 必须一起落库，保证可回放。

### 20.7 代码改动点

1. 新增 `domain/TeamMessage.java` 与 `service/TeamMessageCodec.java`（`Msg` ↔ `TeamMessage`）。
2. 扩展 `AgentFlowExecutor.NodeResult`：增加 `Map<String, Object> artifacts`，
   保留三参构造重载，避免影响现有调用方。
3. 新增 `service/TeamMessageAssembler.java`：
   - 替代 `buildInput` 中纯文本拼接的部分；
   - 保留 `【用户问题】/【上游记录】/【上游核算对象】` 渲染格式；
   - 无结构化附件时退化为现有行为。
4. `PayrollChatService.executeFlowNode` 在返回 `NodeResult` 时补齐：
   - `targets`、`toolError`、`traceIds`（来自 `ToolTraceRecorder`）、`artifacts` 摘要。
5. `agent_run_records` 增加 `message_json CLOB`（可选）保存输入/输出消息快照；
   至少保存 `input_digest`、`output_chars` 与 `trace_ids_json`，控制表体积。
6. 单测与集成测试覆盖：
   - `TeamMessage` 序列化/反序列化与版本校验；
   - 兄弟分支隔离、仅选边传递；
   - 金额附件与 `traceIds` 一致性；
   - 无结构化附件时的纯文本兼容；
   - 依赖检查：代码库不引入 AutoGen/CrewAI/Python 运行时。

### 20.8 本节的验收标准

- [ ] 动态团队 v1 主链路只使用 A（DAG 数据流），代码中不存在 B/C 的自动启用路径；
- [ ] 所有跨节点数据都通过 `TeamMessage` 或兼容的纯文本段落传递，无隐式共享可变状态；
- [ ] 金额字段 100% 可追溯到 `chat_tool_traces`，模型输出中的金额不进入下游 `artifacts`；
- [ ] `TeamMessage` 单测、兄弟分支隔离测试、回放测试通过；
- [ ] `pom.xml` 与运行镜像中不新增 Python / AutoGen / CrewAI 依赖。

---

## 21. 团队生命周期与追溯

### 21.1 三个对象，三套生命周期

动态团队最容易踩的坑，是把“团队结束”理解成“智能体结束”。实际上必须分开：

| 对象 | 归属 | 生命周期 | 团队结束后 |
| --- | --- | --- | --- |
| `AgentDefinition`（Nacos） | 全局共享配置 | 长期 | 保留，不删除 |
| `HarnessAgent` 运行时 | 进程级 `AgentCache` | 长期，跨会话复用 | **不关闭、不驱逐** |
| `TeamPlan` | 本次运行 | 运行期 | 保存快照，不注册为 Nacos 流程 |
| `TeamRun` / `AgentRunRecord` | 审计数据 | 持久化 | 标记 `CLOSED`，进入保留期 |
| `TeamMessage` | 本次运行 | 运行期 | 摘要/快照落库，临时对象丢弃 |
| 动态 `FlowDefinition` | 本次运行 | 运行期 | 丢弃，除非人工“另存为模板” |
| `chat_tool_traces` | 审计数据 | 持久化 | 保留，按保留期清理 |

核心规则：

> **解散的是“这一次团队运行”，不是智能体，也不是历史记录。**

现有实现已经体现了这一点：

- `AgentCache` 是进程级缓存，只有显式 `clear()`（测试/应用关闭）才关闭实例；
  会话重置和单次流程结束都不得关闭（[AgentCache.java:13](../src/main/java/io/agentscope/examples/payroll/agent/AgentCache.java#L13)）。
- 每个流程节点在 `finally` 中 `clearContext(nodeContext)`，清理的是节点上下文，
  不是智能体实例（[PayrollChatService.java:547](../src/main/java/io/agentscope/examples/payroll/service/PayrollChatService.java#L547)）。
- `AgentFlowExecutor` 在 `finally` 中关闭线程池，不残留执行线程
  （[AgentFlowExecutor.java:176](../src/main/java/io/agentscope/examples/payroll/service/AgentFlowExecutor.java#L176)）。

### 21.2 追溯链路

固定四段式：

```
message_id → run_id → node_id → trace_id
```

| 层级 | 存储 | 关键字段 | 回答什么问题 |
| --- | --- | --- | --- |
| 消息 | `chat_messages` | `message_id`、`session_id` | 用户看到的哪一轮 |
| 团队运行 | `agent_team_runs` | `run_id`、`plan_id`、`mode`、`status`、`is_primary` | 这一轮用了什么计划、为什么选这些人、是否回退 |
| 节点运行 | `agent_run_records` | `run_id`、`node_id`、`agent_id`、`role`、`trace_ids_json` | 哪个智能体在哪个环节做了什么、耗时多久 |
| 工具调用 | `chat_tool_traces` | `run_id`、`node_id`、`trace_id`、`tool_name`、`amounts_json` | 金额、员工、期间来自哪次工具返回 |

必须遵守的规则：

1. **一轮消息可以有多个 run**：影子、正式、回退、重试、回放。
   用 `is_primary` 标记聊天页展示的正式运行；影子与回退只在管理端可见。
2. **节点归因优先于智能体归因**：同一个智能体将来若在一个计划中出现多次，
   必须按 `node_id` 归因；`agent_id` 只用于聚合统计，不能反推某一次执行。
3. **禁止仅靠 `chat_tool_traces.agent` 追溯**：它只是阶段 key，多 run 会碰撞。
4. **回退也是独立 run**：`fallback_of_run_id` 指向原动态 run；重试使用
   `retry_of_run_id`，不复用旧节点记录。
5. **用户删除会话**：团队 run 软删除，保留最小审计元数据；完整消息快照按保留期清理（D13）。

### 21.3 TraceScope：把运行身份写进上下文

新增：

```java
public record TraceScope(
        String runId,
        String planId,
        String nodeId,
        String agentId,
        String role,
        String mode,        // SHADOW / ACTIVE / FALLBACK
        String stageKey) {  // 兼容 chat_tool_traces.agent
}
```

- 执行节点前写入 `RuntimeContext`：

```java
nodeContext.put(TraceScope.class, traceScope);
```

- `ToolTraceRecorder` 落库时从上下文读取 `TraceScope`，写入
  `chat_tool_traces.run_id`、`node_id`，`agent` 继续写 `stageKey`；
- 静态链路没有 run 身份时：`run_id` 为空、`node_id` 为空，退回按 `session_id + message_id + agent`
  查询，保证旧数据与旧链路不受影响；
- 禁止通过解析 `stageKey` 反推 runId。

### 21.4 版本快照

组队时保存，执行时使用快照，而不是每个节点重新读 Nacos：

`catalog_snapshot_json` 至少包含：

- 每个候选 `AgentDefinition` 完整 JSON（id/name/description/tags/skillIds/visibility/status/updatedAt）；
- `definitionSignature`（与 `PayrollAgentFactory` 缓存 key 同口径）；
- 技能 id 列表 + 技能内容 hash / `updatedAt`；
- 模板版本与条件类型版本。

`score_snapshot_json` 至少包含：

- 评分公式版本、权重、Wilson 参数、冷启动先验；
- 每个候选的 `samples / objectiveScore / feedbackScore / finalScore / rank`；
- 统计窗口与生成时间。

执行约束：

1. 运行开始时一次性解析计划引用的智能体定义，后续节点复用；
2. 运行中途 Nacos 变更**不影响当前 run**，下一次 run 才生效；
3. 运行开始时智能体已下架/删除 → `PlanValidator` 拒绝并回退，不执行半套计划；
4. 回放时对比“快照版本”和“当前版本”，明确展示差异，不假装与当时一致。

### 21.5 消息快照与敏感数据

- 默认落库：`input_digest`、`output_chars`、`trace_ids_json`、节点状态与耗时；
- 审计模式（环境变量/管理端开关）才保存完整 `message_json`，包含输入/输出 `TeamMessage`；
- `artifacts.amounts` 只能来自工具追溯；消息快照不得覆盖或改写原始 trace；
- 访问权限：
  - 会话所有者可看自己消息的团队卡片与摘要；
  - 完整消息快照、目录快照、评分快照仅管理权限可见；
  - 管理端列表默认不返回对话正文；
- 保留期与脱敏：
  - 完整内容默认 180 天（D12），到期归档后清理；
  - 聚合评分与审计事件长期保留；
  - 如启用完整快照，需评估身份证号、银行卡号等字段的脱敏与访问审计。

### 21.6 解散 / 关闭清单

团队运行结束（成功、失败、超时、取消、SSE 断开、应用关闭）都必须执行同一套幂等清理：

1. **先落库**：刷新 `ToolTraceRecorder`，写 `agent_run_records` 与 traces；
2. 清理每个节点的 `RuntimeContext`（`clearContext`），成功与异常路径都要走；
3. 丢弃本 run 的 recorder、临时消息、监听器；
4. 关闭本 run 的 SSE 通道，取消未完成 Future；
5. 关闭本 run 的线程池；
6. 删除 run 级临时资源：workspace、memory namespace、MessageBus inbox/queue、临时子智能体注册；
7. 更新 `agent_team_runs.status = CLOSED`、`closed_at`；
8. **不执行**：关闭共享 `HarnessAgent`、驱逐 `AgentCache`、删除 `AgentDefinition`/技能、
   把动态 `FlowDefinition` 写入 Nacos。

要求：

- 清理逻辑放在 `finally`，幂等，重复调用不报错；
- 落库失败时记录 `trace_flush_error`，仍然继续清理，避免资源泄漏；
- 应用启动时对超过阈值的 `RUNNING` 记录做对账，标记 `INTERRUPTED` 并补 `closed_at`；
- 用户断开 SSE 时，若执行仍在继续，标记 `CANCELLED` 或等待节点结束后关闭，
  不允许无限期后台运行。

### 21.7 并发、异常与隔离

| 场景 | 处理 |
| --- | --- |
| 同一智能体被多个会话同时使用 | 共享 `HarnessAgent`，每节点独立 `RuntimeContext`，互不清理对方上下文 |
| 同一计划中重复使用同一智能体 | v1 由 `PlanValidator` 禁止；将来放开需改为每节点独立实例或串行 |
| 并行分支同时写 trace | 每节点独立 `ToolTraceRecorder`，按 `node_id` 分开落库 |
| 节点超时 | 取消该节点 Future，标记 `TIMEOUT`，按 §10 回退 |
| 计划校验失败 | 不创建运行上下文，只落 `REJECTED` 记录 |
| 应用重启 | `RUNNING` → `INTERRUPTED`，保留已落库节点与 traces |
| 智能体定义在运行中被修改 | 当前 run 继续使用启动时快照；新 run 使用新版本 |
| 用户删除会话 | run 软删除；审计事件与聚合统计保留 |

### 21.8 状态机与保留策略

```
PLANNED ──拒绝──> REJECTED
   │
   └──开始执行──> RUNNING ──> SUCCEEDED
                      │   ├──> FAILED
                      │   ├──> CANCELLED
                      │   └──> FALLBACK ──> 新静态 run（fallback_of_run_id）
                      │
                      └──> CLOSED（运行时解散完成）
                              │
                              └──> ARCHIVED ──> PURGED（保留期到期）
```

- `FALLBACK` 是动态 run 的终态之一，静态 run 通过 `fallback_of_run_id` 关联；
- `CLOSED` 表示运行时资源已释放，不代表数据删除；
- `ARCHIVED` 表示完整内容已归档，管理端仍可看摘要；
- `PURGED` 只清理完整消息快照与可删除的 trace，保留最小审计元数据与聚合评分；
- 保留期配置：`PAYROLL_TEAM_RETENTION_DAYS`，默认 180。

### 21.9 追溯与生命周期 API

| 方法 | 路径 | 说明 | 权限 |
| --- | --- | --- | --- |
| GET | `/api/chat/messages/{messageId}/team` | 正式 run + 节点摘要 + trace 引用 | 会话所有者 |
| GET | `/api/chat/messages/{messageId}/team-runs` | 该消息全部 run（影子/回退/重试） | 会话所有者 |
| GET | `/api/admin/agent-team-runs/{runId}` | 计划快照、目录/评分快照、节点详情 | 管理权限 |
| GET | `/api/admin/agent-team-runs/{runId}/traces` | 按 run 查完整工具追溯 | 管理权限 |
| POST | `/api/admin/agent-team-runs/{runId}/replay` | 按快照回放（只读） | 管理权限 |
| POST | `/api/admin/agent-team-runs/{runId}/archive` | 手工归档 | 管理权限 |

`/api/chat/sessions/{sessionId}/messages/{messageId}/traces` 保持现状，继续服务“计算依据”面板；
新增接口只补充 run 维度，不替换旧接口。

### 21.10 测试与验收

单元/集成测试必须覆盖：

- [ ] 同一消息同时存在 shadow + active 时，trace 按 `run_id + node_id` 不串线；
- [ ] 回退与重试通过 `fallback_of_run_id` / `retry_of_run_id` 正确关联；
- [ ] 成功、失败、超时、SSE 断开四种路径都执行幂等清理；
- [ ] 团队结束后 `AgentCache` 数量不变，共享智能体未被关闭；
- [ ] 动态 `FlowDefinition` 不出现在 Nacos 流程列表；
- [ ] 运行中修改 Nacos 定义，当前 run 仍使用快照，下一次 run 使用新版本；
- [ ] 回放时能展示快照与当前定义的差异；
- [ ] 保留期清理与用户删除会话的软删除行为符合 D12/D13；
- [ ] 应用重启后 `RUNNING` 记录正确对账为 `INTERRUPTED`；
- [ ] `PlanValidator` 拒绝重复智能体、离线/私有智能体；
- [ ] 完整消息快照默认不落库，审计模式开启后才落库且仅管理端可见。

### 21.11 本节验收标准（Definition of Done）

- [ ] `TraceScope` 写入 `RuntimeContext`，trace 落库带 `run_id` / `node_id`；
- [ ] `chat_tool_traces` 增量列上线，旧数据查询不受影响；
- [ ] 团队运行状态机、`CLOSED` 清理清单、启动对账全部实现；
- [ ] 共享智能体生命周期有测试保护：团队结束不关闭缓存实例；
- [ ] 目录/评分/消息快照可按 `run_id` 回放；
- [ ] 保留期、软删除、归档与权限策略可配置并有测试覆盖。

---

## 22. P0 实施任务拆解（数据地基）

### 22.1 P0 边界

**纳入 P0**：

- 新表、领域模型与配置开关；
- `TraceScope` 与 trace 的 `run_id/node_id` 归因；
- 静态链路运行记录（`mode=STATIC`）；
- 消息 👍/👎 反馈 API 与前端；
- 团队运行/节点/追溯的只读查询 API；
- 评分聚合与只读统计（不参与选角）；
- 测试、文档与端到端验证。

**不纳入 P0**（属于 P1）：

- `TeamIntent` 解析、能力索引、候选人排序；
- `TeamPlan` 生成、`PlanValidator`、动态 `FlowDefinition` 编译；
- `AgentFlowExecutor` 动态执行、影子对比；
- 聊天页“本轮协作团队”卡片；
- 自动回退与动态审计事件。

P0 的验收前提：**现有对话行为、金额结果与 SSE 事件完全不变**，只新增旁路记录与反馈能力。

### 22.2 任务清单

| ID | 任务 | 关键改动 | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| P0-1 | 数据模型、建表与配置 | `AgentTeamRun`、`AgentRunRecord`、`ChatFeedback`、`TraceScope` 领域记录；`PayrollDatabase` 新表/增量列/索引；`H2AgentTeamStore`、`H2ChatFeedbackStore`；`TeamSettings`（mode/retention/window） | 无 | 旧库启动幂等；表结构与规格 §5.5/§21 一致；仓储单测通过 |
| P0-2 | TraceScope 与 trace 归因 | `ChatToolTrace` 增加 `runId/nodeId`；`RuntimeContext` 写入 `TraceScope`；`traceOf / insertTrace / mapTrace` 透传；静态三阶段各自生成 scope | P0-1 | 同一消息两次运行、相同 stageKey 的 trace 不串线；旧数据查询兼容 |
| P0-3 | 静态链路运行记录 | `StaticRunRecorder`（或 `TeamRunService`）；`mode=STATIC`； calculator/validator/accountant 节点记录；成功/失败/超时路径；消息保存后回填 trace id | P0-1、P0-2 | 一轮对话产生 1 条 run + N 条 node 记录；失败路径同样落库；开关关闭时零副作用 |
| P0-4 | 反馈后端 | `ChatFeedback` 校验与 upsert；`POST /api/chat/sessions/{id}/messages/{mid}/feedback`；会话归属校验；审计事件 `CHAT_FEEDBACK_RECORDED` | P0-1 | 跨用户 403；同用户重复提交幂等；非法 rating/tag 被拒绝 |
| P0-5 | 追溯读取 API | `GET /api/chat/sessions/{id}/messages/{mid}/team`：run、节点摘要、trace 引用、当前反馈；会话归属校验 | P0-3、P0-4 | 返回结构与前端约定一致；影子/静态/多 run 可区分；跨用户 403 |
| P0-6 | 反馈前端 | 助手消息下方 👍/👎；差评标签弹层；提交状态与失败提示；加载已有反馈；微信风格样式 | P0-4、P0-5 | 刷新后反馈状态保留；重复点击不重复提交；移动端布局不破坏 |
| P0-7 | 评分聚合与只读统计 | `AgentStatsService`：Wilson 下界、时延分、稳定性、反馈平滑、冷启动收缩；5 分钟缓存；`GET /api/admin/agent-stats`；只读表格 | P0-1、P0-3 | 零样本不除零；公式与 §7.3–7.6 一致；统计接口有权限校验 |
| P0-8 | 回归、文档与端到端 | 全量测试；旧库升级；README 环境变量；交接文档 P0 章节；本机 E2E | P0-1～P0-7 | 现有 241 项测试全绿；E2E 能查到 run/node/trace/feedback/stats；对话金额与 SSE 无回归 |

### 22.3 推荐实施顺序

```
P0-1 数据模型
  ├─> P0-2 TraceScope ──> P0-3 静态运行记录 ──> P0-5 读取 API ──> P0-6 反馈前端
  │                                   │
  │                                   └─> P0-7 评分统计
  └─> P0-4 反馈后端 ───────────────────> P0-5
                                            │
                                            └─> P0-8 回归/文档/E2E
```

P0-2 与 P0-4 可并行；P0-7 必须在 P0-3 产生真实数据后验证；P0-8 最后执行。

### 22.4 P0 验收清单

- [ ] `PAYROLL_TEAM_MODE=STATIC` 可开启静态运行记录，`OFF` 时行为与当前完全一致；
- [ ] 旧 H2 数据库启动自动增量升级，无数据丢失；
- [ ] 一次对话可按 `message_id → run_id → node_id → trace_id` 完整追溯；
- [ ] 成功、失败、超时路径都产生运行记录，且共享智能体未被关闭；
- [ ] 反馈可提交、可查询、幂等且鉴权正确；
- [ ] 评分统计在零样本与小样本下稳定，冷启动收缩生效；
- [ ] 全量测试与 E2E 通过，README/交接文档更新。
