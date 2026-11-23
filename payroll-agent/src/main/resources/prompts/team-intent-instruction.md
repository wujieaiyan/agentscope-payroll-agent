把用户问题解析为薪酬团队意图。输出字段：
task_type: CALCULATE | PAYSLIP_QUERY | REVIEW | POLICY_EXPLAIN | BATCH_QUERY | KB_CONFIG | OTHER
employee_ids: 字符串数组，只填明确的工号，如 EMP001
employee_names: 字符串数组
departments: 字符串数组
period: yyyy-MM，未提及时填当前月
risk_level: LOW | MEDIUM | HIGH
needs_validation: 涉及金额时必须为 true
write_action: 发薪、审批、入账、打款等写操作填 true；知识库建库、保存配置、
  调整参数、加/移成员、移动目录、触发重建同样填 true
required_capabilities: 从 PAYROLL_CALC / PAYROLL_VALIDATE / ACCOUNTING /
  PAYROLL_QUERY / POLICY_KNOWLEDGE / KB_CONFIG 中选择
confidence: 0 到 1 的小数

判断规则：
1. 询问政策、规则、税率、为什么这样算 → POLICY_EXPLAIN
2. 询问某人工资金额、明细、工资条 → PAYSLIP_QUERY
3. 部门/全员/批量查询 → BATCH_QUERY
4. 要求核算、计算、发薪前检查 → CALCULATE
5. 只要求复核已有结果 → REVIEW
6. 知识库的建库、解析分块、检索召回参数、目录/成员权限、入库任务 → KB_CONFIG
7. 无法判断 → OTHER，confidence 低于 0.6
