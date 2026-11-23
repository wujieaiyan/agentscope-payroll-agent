# 场景包接入契约（v0）

> 依据：`docs/architecture-remediation-spec.md` §6（M1）、`docs/architecture-review.md` §6（D1 双主线）。
> 适用对象：希望在「Agent 平台」上新增业务场景的开发者，以及评估 `devteam` 重启（D2-A）的维护者。

## 1. 定位

平台提供智能体运行、技能、流程、团队、知识库检索、模型连接与用量审计；业务场景包只提供**定义与提示词**，不复制编排逻辑。

- 薪酬（`payroll`）是第一个场景包：`PayrollCalculatorAgent` / `PayrollValidatorAgent` / `PayrollAccountantAgent`、薪酬技能、薪酬流程、`PayrollEngine` / `PayrollTools`。
- `devteam` 是第二个场景包的**冻结雏形**（EXPERIMENTAL，2026-11-22 起不进菜单、不补 REST），等本契约稳定后按 D2-A 重启。

## 2. 允许依赖的平台 SPI

场景包只能通过下表接口接入平台；表中为当前真实类型，新增场景不得直接 new 平台实现类。

| SPI | 实际类型 | 位置 | 场景包用途 |
| --- | --- | --- | --- |
| 智能体定义 | `AgentDefinition` | `payroll-common/.../domain` | 声明 id、名称、模型分面、能力（如 `kb:read`）、技能绑定 |
| 技能目录 | `PayrollSkillCatalog`（`SkillRepositories` 组合 `BuiltIn` / `Nacos`） | `payroll-common/.../skill` | 列出元数据、按需批量取正文；技能正文由平台注入提示词 |
| 流程定义 | `FlowDefinition` / `FlowNode` / `FlowEdge` | `payroll-common/.../domain` | 声明 DAG；执行器与并行调度由平台负责 |
| 知识库通道 | `KbSkillGateway` | `payroll-common/.../service` | 由 runtime 发起 `kb_search` 等调用，Web 侧按用户权限执行 |
| 模型连接 | `ModelConnection` / `ModelConnections` | `payroll-common/.../model` | 引用已登记的模型连接与分面（coding / utility / …） |
| 运行记录 | `AgentRunRecord` / `TraceScope` / `ChatToolTrace` | `payroll-common/.../domain` | 只读消费平台产生的追溯数据，不自行落库 |

## 3. 禁止面

以下依赖由 `ArchitectureRulesTest` 强制（规则编号见 §6）：

1. **平台包不得引用场景业务类型**（R6）：`skill / model / usage / memory / profile / team / kb / health / support` 不得依赖薪酬业务模型与 `PayrollEngine`、`PayrollTools`、`PayrollAgentFactory`。
2. **平台包与管理台 Controller 不得引用冻结场景包**（R7）：不得依赖 `..devteam..`。
3. **场景包不得反向依赖平台内部实现**：`ChatSessionManager`、`PayrollChatService`、`PayrollAgentConfiguration` 等编排/装配类只允许场景入口层（`payroll-agent` 的 `service` / `agent` 包）使用。
4. **沙箱不得依赖 `payroll-common`**（R5）：只共享 `payroll-protocol` 的协议类型与 `InternalAuth`。
5. **runtime 不得依赖控制面 web 实现**（R3）：共享的 SSE 工具类正在下沉，见 §6 冻结清单。

## 4. 新增场景包步骤

1. 建独立包（如 `io.agentscope.examples.payroll.<scenario>`），只在 `payroll-agent` / `payroll-backend` 的场景入口装配。
2. 智能体与流程只写定义（`AgentDefinition`、`FlowDefinition`），通过注册表发布；不 fork `PayrollChatService` 的链路选择。
3. 需要的平台能力先查 §2 的 SPI；缺能力时扩展 SPI，不在场景包内直连平台实现。
4. 新权限码带工作台前缀（`platform:` / `payroll:`），不重命名既有权限码。
5. 为新场景补一条 `ArchitectureRulesTest` 的隔离断言（参照 R7），并跑 `mvn -pl payroll-tests -Dtest=ArchitectureRulesTest test`。
6. 在本文 §2 表内登记新增 SPI，在 `docs/architecture.md` 的边界表登记新场景。

## 5. `payroll-common` 对外 API 清单（首版）

| 包 | 可被谁引用 | 说明 |
| --- | --- | --- |
| `..domain` | 全部模块 | 平台类型（`AgentDefinition`、`FlowDefinition`、`Chat*`、`Trace*`）与薪酬业务类型同包；平台包只准用平台类型（R6） |
| `..service` | `payroll-agent`、`payroll-backend`、场景包 | `PayrollEngine` 等场景锚点只准场景层引用；`KbSkillGateway` / `InternalAuth` 是跨节点契约 |
| `..skill` / `..model` / `..team` / `..kb` / `..memory` / `..profile` / `..usage` / `..health` / `..support` | 场景层只读调用 | 平台实现，`@Internal` 约定：不得在场景包内继承或替换 |
| `..web` | `payroll-backend`、`payroll-agent`（runtime 仅共享工具类） | 控制面入口与共享 SSE/过滤器；runtime 的依赖正在收敛 |
| `..db` / `..repository` | 平台包 | 存储实现；场景包不得直接建表 |

## 6. 冻结基线（2026-11-22 建档）

`ArchitectureRulesTest` 的 R3 / R6 / R7a 使用 `FreezingArchRule`，基线存于 `payroll-tests/archunit_store/`。新增违规会让 CI 失败；清理后需显式更新基线：

```bash
mvn -pl payroll-tests -Dtest=ArchitectureRulesTest \
  -Darchunit.freeze.store.default.allowStoreUpdate=true test
```

| 规则 | 冻结内容 | 处置 | owner | 到期 |
| --- | --- | --- | --- | --- |
| R3 | runtime 引用 `web.ChatSse` / `ChatStreamRejection` / `NodeRoleFilter`（2 文件） | 把共享 SSE 与过滤器下沉到独立包，runtime 只依赖下沉后的类型 | 架构负责人 | 2026-12-31 |
| R6 | `team.TeamLlmClient` 调用 `PayrollAgentFactory.createUtilityAgent` | 把 utility 智能体构造移到平台工厂端口，team 只依赖端口 | 架构负责人 | 2026-12-31 |
| R7a | `model.ModelConnections` 读取 `devteam.DevModelSettings` | 把编码面模型配置迁到 `model` 包，devteam 只消费平台配置 | 架构负责人 | 2026-12-31 |

另有未纳入规则的存量债务：平台包引用 4 个薪酬异常类型（`PayrollException` / `PayrollConflictException` / `PayrollNotFoundException` / `PayrollUnavailableException`，14 文件）。处置：3 周内中立化为平台通用异常，场景层保留兼容别名；完成后把异常类型加入 R6 的禁止清单。

## 7. 验收

- [x] `mvn -pl payroll-tests -am -Dtest=ArchitectureRulesTest test` 绿（8 个断言，7 条规则）；
- [x] 冻结条目均有 owner 与到期日（§6）；
- [x] 平台包对薪酬业务类型依赖为 0（R6 仅冻结 `TeamLlmClient` 一处锚点调用）；
- [x] 本文列出平台 SPI 与禁止面；
- [x] 新增违规时测试输出包含规则名与 `because` 说明。
