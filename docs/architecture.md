# 系统架构（单一事实源）

- 定位：**Agent 平台 + 薪酬运营双主线**；平台为骨，薪酬为首个旗舰场景包。
- 本文只描述「现在是什么」；复审结论与整改计划见 [`architecture-review.md`](architecture-review.md)、
  [`architecture-remediation-spec.md`](architecture-remediation-spec.md)。

## 1. 进程与职责

```
浏览器 (Vue 3 + Vite)
   │ /api
   ▼
payroll-backend（控制面，:8080）
   ├─ 鉴权 / 会话 / 轮次生命周期 / SSE
   ├─ 管理台 REST（员工、批次、审批、审计、KB、定义管理）
   └─ 知识库读写与检索入口
   │ /internal（内部令牌）
   ▼
payroll-agent（数据面，:8100）
   ├─ ReAct / 流程 / 团队执行
   ├─ 模型连接与故障切换
   └─ 工具调用（薪酬、KB、技能、确认）
   │
   ▼
payroll-sandbox（:8200，可选）—— 技能脚本隔离执行
```

- 控制面与数据面共享 `payroll-common`；两者都只依赖 common，不互相依赖。
- sandbox 不依赖 common，通过 HTTP 协议交互。
- 外部依赖：MySQL（生产）/ H2（本地与测试）、Redis、Elasticsearch（可选）、Nacos（定义中心）、模型服务。

## 2. Maven 模块

| 模块 | 职责 | 依赖方向 |
| --- | --- | --- |
| `payroll-protocol` | sandbox 协议 | 无业务依赖 |
| `payroll-common` | 领域模型、配置、DB、KB、技能/模型/用量、团队、健康检查 | → protocol |
| `payroll-agent` | 运行时入口与对话服务、路由、流程执行 | → common |
| `payroll-backend` | Web 入口、Controller、管理面 | → common |
| `payroll-sandbox` | 脚本隔离执行 | → protocol |
| `payroll-tests` | 跨模块集成 / E2E / 架构规则 | → 全部 |

包级分层（ArchUnit 强制，基线违规冻结）：
`domain` 不依赖 `service/web/skill/db`；`kb` 不反向依赖对话；runtime 不依赖 `web`；
Controller 只在 `web` / `kb`；平台包（`skill/model/usage/memory/profile/team/kb/health/support`）
不依赖薪酬业务类型与 `PayrollEngine / PayrollTools / PayrollAgentFactory`。

## 3. 一轮对话的路由

优先级从高到低：

| 序 | 条件 | path |
| --- | --- | --- |
| 1 | 全局默认流程可用且满足能力门禁 | `FLOW` |
| 2 | 轻量模型/规则选中单智能体 | `AGENT` |
| 3 | ACTIVE 动态团队命中 | `TEAM_ACTIVE` |
| 4 | `payroll.orchestrator=graph` 且未激活流程 | `GRAPH`（POC） |
| 5 | 写操作确认后的执行 | `ACTION` |
| 6 | 其余 | `PIPELINE` |

- 每条消息落库 `ChatRouting` 快照（path / persona / 原因 / 回退来源 / 模型 / 定义指纹 / KB 判定），
  前端以 chip 展示，技术细节收进折叠卡片。
- 选择依据依次为：确定性规则 → 语义分类器 → 轻量模型路由；任一步失败按类型回退，不中断对话。
- 能力门禁：KB 相关请求要求候选智能体/流程声明 `kb:read`；不满足时改道到具备检索能力的智能体。
- 同一轮最多一次模型故障切换；切换与回退都写日志与指标。

## 4. 编排语义

- **单智能体**：一个 ReAct 循环，按定义挂载技能、工具与能力。
- **流程（DAG）**：`START / AGENT / CONDITION / END`；同层就绪节点并行（≤8）；
  条件只允许预设枚举；每个节点独立智能体实例与工具追溯；单末端智能体时实时推流。
  详见 [`agent-orchestration.md`](agent-orchestration.md)。
- **动态团队**：SHADOW 影子评估与 ACTIVE 白名单两种模式，失败回退固定流水线。
- **流水线**：计算 → 验证 → 核算三段兜底，不依赖模型判断顺序。

## 5. 数据与配置

| 项 | 生产唯一形态 | 测试 / 本地 |
| --- | --- | --- |
| 关系库 | MySQL + Flyway（`db/migration/common`） | H2（`MODE=MySQL`）+ 同一套 Flyway 迁移 |
| 缓存 / 会话 | Redis | 进程内实现 |
| 检索 | Elasticsearch + 远程嵌入 | BM25 / Hash 兜底 |
| 定义中心 | Nacos（flows / skills / models / settings）；agent 定义可选落 `agent_definition` 表（`payroll.definitions.source=db`） | 代码内置默认 |
| 部署模式 | `payroll.deployment.mode=cluster` | `single` |

- Spring profile：`application-common.yml` 为基础；`mysql`、`redis`、`prod` 叠加；
  生产档由 `TopologyGuard` 启动期强校验，缺失依赖直接失败（见 [`deployment-matrix.md`](deployment-matrix.md)）。
- 所有新表只写一份 Flyway 迁移（`db/migration/common`），H2（`MODE=MySQL`）与 MySQL 共用，SQL 需双方言可执行；
  旧库启动时由 `PayrollDatabase` 幂等回放迁移后基线到 V21，再增量执行新版本。

## 6. 质量门

- CI（`.github/workflows/ci.yml`）：PR 跑单元测试 + 前端 test/build + secret 扫描；
  `main` 与夜间跑全量；测试按 `integration / e2e / eval` 分组。
- 架构规则：`payroll-tests` 的 `ArchitectureRulesTest`，违规以冻结清单管理。
- 变更纪律：新接口带权限码与审计；新表双 DDL；异常按类型分支；文档回写。

## 7. 双主线边界

| 层 | 范围 | 约束 |
| --- | --- | --- |
| 平台能力层 | 技能、模型连接、用量、记忆、画像、团队、KB、健康检查 | 不依赖薪酬业务类型 |
| 薪酬场景层 | `domain`、`PayrollEngine`、`PayrollTools`、薪酬技能/流程/智能体 | 只依赖平台 SPI |
| 控制面 | Controller 与对话服务 | 按工作台归属组织，对话入口唯一 |
| 信息架构 | 平台工作台 / 薪酬工作台 / 平台治理 | 旧路径保留重定向 |

## 8. 文档索引

| 主题 | 文档 |
| --- | --- |
| 复审与整改 | `architecture-review.md`、`architecture-remediation-spec.md` |
| 流程编排 | `agent-orchestration.md` |
| 场景包接入 | `scenario-packs.md` |
| 部署矩阵 | `deployment-matrix.md` |
| 会话续流与轮次 | `chat-resume-design.md` |
| 知识库 | `kb-*.md`、`vector-memory-spec.md` |
| 运维与恢复 | `restore-runbook.md`（观测文档随 M4 落地） |
| 快速开始与配置项 | `../README.md` |
