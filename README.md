# 薪酬核算智能体（AgentScope Java）

基于 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) 的薪酬核算智能体。

核心原则：**大模型负责“听懂”和“解释”，确定性引擎负责“算对”。** 模型不参与任何金额计算，
所有数字都来自 `PayrollEngine`，保证结果可复算、可审计。

## 架构

```
用户自然语言
      │
      ▼
ReActAgent（默认 ollama:qwen2.5:3b-instruct，可切换 dashscope:qwen-plus）
  ├─ 意图识别 / 追问补参 / 工具编排 / 结果解释
  │
  ▼ @Tool（8 个工具，返回结构化 JSON）
PayrollTools
  │
  ▼
PayrollEngine（BigDecimal 全链路）
  ├─ 个税累计预扣法（跨月递推）
  ├─ 社保 / 公积金（基数上下限）
  ├─ 考勤扣款 / 三类加班
  ├─ 入职首月折算
  └─ 发薪前勾稽校验
  │
  ▼
PayrollRepository（演示 JSON；生产替换为 HR 主数据 / 考勤 / 绩效服务）
```

## 功能

| 能力 | 说明 |
| --- | --- |
| 单人核算 | 应发、考勤扣款、社保公积金、累计预扣个税、实发、企业成本 |
| 工资条 | 应发/扣款/实发三段式 + 个税计算过程 + 风险提示 |
| 批量核算 | 按部门或全员，汇总应发/实发/企业成本；单个员工失败不影响其他员工 |
| 发薪前校验 | 勾稽关系、社保基数上下限、实发是否为负等 6 项检查 |
| 考勤核对 | 事假、病假、旷工、工作日/休息日/法定节假日加班 |
| 政策问答 | 税率表、社保比例、基数上下限、考勤数据就绪月份 |
| 结构化意图抽取 | 将自然语言解析为 `action / employeeId / period / department`（建议使用 DashScope 模型） |

## 快速开始

环境：JDK 17、Maven 3.6+、[Ollama](https://ollama.com/)（本地模型对话时需要）。

```bash
mvn clean package

# 离线演示：不调用大模型，直接验证核算引擎
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --demo

# 单人工资条 + 发薪前校验
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --payroll EMP001 --period 2026-10

# 部门批量
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar --department 研发部 --period 2026-10
```

本地模型对话（默认，无需 API Key）：

```bash
ollama serve
ollama pull qwen2.5:3b-instruct
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar
```

使用 DashScope 在线模型：

```bash
export DASHSCOPE_API_KEY=sk-xxx          # Windows: set DASHSCOPE_API_KEY=sk-xxx
export PAYROLL_MODEL=dashscope:qwen-plus # Windows: set PAYROLL_MODEL=dashscope:qwen-plus
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar
```

示例提问：

- `算一下张伟 2026-10 的工资`
- `研发部 10 月工资表`
- `王强这个月为什么实发这么低？`
- `社保基数是怎么定的？`
- `替我检查 EMP005 10 月工资能不能发`

### 模型配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PAYROLL_MODEL` | `ollama:qwen2.5:3b-instruct` | 模型标识；Ollama 用 `ollama:<模型名>`，DashScope 用 `dashscope:<模型名>` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama 服务地址 |
| `PAYROLL_OLLAMA_NUM_CTX` | `8192` | Ollama 上下文窗口 |
| `PAYROLL_OLLAMA_TEMPERATURE` | `0.1` | 采样温度，核算场景建议保持低温度 |
| `DASHSCOPE_API_KEY` | 无 | 仅 `dashscope:` 模型必填 |

启动时会预检 Ollama 服务与模型是否就绪，未启动或未拉取模型会直接给出中文提示。
本地小模型（1B–3B）在长系统提示下可能漏调工具，建议使用 7B 以上模型或在线模型；
核算金额始终来自确定性引擎，模型漏调工具时不会产生错误金额，但会影响回答完整性。
`--extract` 依赖结构化输出能力，Ollama 扩展暂不提供该元数据，建议改用 DashScope 模型执行。

> Windows 控制台如出现中文乱码，请加 `-Dfile.encoding=UTF-8`，或先执行 `chcp 65001`。

## 工具清单

| 工具 | 用途 | 只读 |
| --- | --- | --- |
| `list_employees` | 按部门查询员工，定位工号 | 是 |
| `get_employee_profile` | 查询薪酬档案（银行卡脱敏展示） | 是 |
| `get_attendance_summary` | 查询月度考勤汇总 | 是 |
| `calculate_payroll` | 单人完整核算（含个税过程与风险提示） | 是 |
| `calculate_department_payroll` | 部门/全员批量核算 | 是 |
| `validate_payroll` | 发薪前勾稽校验 | 是 |
| `generate_payslip` | 生成工资条文本 | 是 |
| `get_payroll_policy` | 查询税率、社保比例、基数上下限 | 是 |

所有工具只读，计算是纯函数，便于并发调用与回放。工具失败时返回
`{"status":"ERROR","message":"..."}`，让模型继续追问而不是崩溃。

## 核算口径（演示）

默认策略位于 `PayrollPolicy.demoPolicy()`，**仅用于演示，不代表任何地区现行政策**：

- 个税：累计预扣法，月减除费用 5,000 元，七级超额累进税率（3%–45%）
- 社保基数：`max(申报基数或基本工资+岗位津贴, 下限 7,500)`，上限 37,500
- 个人：养老 8%、医疗 2% + 3 元、失业 0.5%；公积金按员工档案比例
- 企业：养老 16%、医疗 9.8%、失业 0.5%、工伤 0.2%、生育 0.8%；公积金与个人同比例
- 加班：基本工资 / 21.75 / 8 为时薪，工作日 1.5 倍、休息日 2 倍、法定节假日 3 倍
- 请假：事假、旷工按日薪全额扣减；病假按 60% 计薪（扣减 40%）
- 入职首月：固定工资与津贴按自然日在职天数折算
- 精度：所有金额 `BigDecimal` + `HALF_UP` 保留两位小数，入口统一在 `Money`

个税公式：

```
累计应纳税所得额 = 累计计税收入 - 累计减除费用 - 累计个人社保公积金 - 累计专项附加扣除
本月预扣税额     = 累计应纳税额（按档位） - 已预扣税额
```

## 演示数据

`src/main/resources/data/`：

- `employees.json`：6 名员工，覆盖研发/销售/生产/财务/HR
- `attendance-2026-10.json`：当月考勤（事假、病假、旷工、三类加班）
- `variable-pay.json`：绩效奖金与其他补贴

历史月份由 `JsonPayrollRepository.demo()` 按全勤补齐，用于演示累计预扣的跨月递进。
**这是数据构造，不是引擎行为**：生产环境任一月考勤缺失，引擎都会拒绝核算，避免“缺勤当全勤”。

## 关键设计决策

1. **金额与语言解耦**：系统提示词明确禁止模型心算；工具结果是唯一事实来源。
2. **缺数据失败关闭**：考勤缺失、期间超过数据就绪月份、入职前月份都直接拒绝，不猜默认值。
3. **累计预扣可复算**：每次核算从当年 1 月（或入职月）顺序递推税态，同一输入必得同一结果。
4. **批量故障隔离**：单个员工核算失败进入 `failures`，不阻断整批出账。
5. **精度集中治理**：日薪/时薪保留 10 位中间精度，展示与入账统一两位小数。
6. **校验独立于计算**：`validate_payroll` 检查勾稽关系，计算正确不等于可以发薪。
7. **敏感数据最小暴露**：工具只返回脱敏银行卡；生产环境应叠加数据权限与审计。

## 测试

```bash
mvn test
```

覆盖：

- 个税档位边界连续性（36,000 / 144,000 / 300,000 / 960,000）
- 社保基数上下限封顶
- 事假 + 加班 + 社保 + 累计个税的全链路金额
- 入职首月自然日折算
- 跨月累计税额单调递增
- 批量合计等于逐人之和
- 考勤未就绪、入职前月份拒绝核算
- 工具结构化成功/错误返回、工资条输出
- Toolkit 反射注册与 schema 生成

## 生产化清单

- 用 HR 主数据、考勤、绩效服务替换 `PayrollRepository`，并做行级数据权限
- 政策参数（税率、基数、比例、生效日期）迁移到配置中心，按社保年度版本化
- 以 `employee_id + period` 做幂等键，核算结果落库并保留计算快照
- 接入审批流、发薪复核、审计日志与敏感字段加密
- 批量核算分页与缓存，避免大部门一次拉全量
- 如需限制敏感操作，可结合 AgentScope 的 Permission / HITL 能力增加人工确认
- 若要升级到仓库内的 `2.0.3-SNAPSHOT`，修改 `pom.xml` 的 `<agentscope.version>` 即可

## 目录

```
src/main/java/io/agentscope/examples/payroll
├── PayrollApplication.java       # CLI 入口（演示/单人/批量/意图抽取/对话）
├── agent
│   ├── PayrollAgentFactory.java  # ReActAgent 装配与系统提示词
│   ├── PayrollTools.java         # 8 个 @Tool
│   └── PayrollIntent.java        # 结构化意图
├── domain                        # 员工、考勤、政策、税态、核算结果
├── repository                    # 数据访问边界与 JSON 演示实现
├── service
│   ├── PayrollEngine.java        # 唯一金额计算入口
│   └── PayslipFormatter.java     # 工资条渲染
└── support/JsonSupport.java      # snake_case JSON
```
