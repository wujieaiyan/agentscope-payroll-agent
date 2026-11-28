---
name: payroll-prompt-and-skill
description: 修改系统提示词、角色提示词、技能正文或技能文件包时使用。提示词只放 payroll-agent/src/main/resources/prompts 并由 PromptLibrary 加载；技能正文要过质量门槛与 frontmatter 校验；改完必须跑 PromptLibraryTest 并真实发一轮对话验证行为变化。
---

# 提示词与技能改造

## 适用场景

- 调整某个角色（计算/复核/核算/自定义智能体）的说法、口径、输出格式；
- 新增技能、改技能正文、加参考文件或脚本；
- 排查「改了提示词没生效」「技能加载不到文件」「模型没调用该调的工具」。

## 铁律

1. **系统提示词只在 `payroll-agent/src/main/resources/prompts/*.md`**，由 `PromptLibrary` 按文件名加载。
   禁止在 Java 里内联提示词常量；改提示词等同改代码，需要评审并随版本发布。
2. **提示词不改变金额**。金额只能来自 `PayrollEngine`；凡是「让模型自己算/自己四舍五入」的改法一律打回。
3. 技能的权威存储在 Nacos 或 MySQL（`PAYROLL_SKILLS_SOURCE`），**代码内置版本只是回退**；改内置版本不会覆盖已在库里/配置中心的同 ID 技能。
4. 改提示词要说明「期望的行为变化」和「如何观测」，否则无法验证。

## 步骤

### 1. 定位改哪一份

| 要改的行为 | 改哪里 |
| --- | --- |
| 角色职责、输出结构 | `prompts/<role>.md` |
| 工具使用策略 | `prompts/<role>.md` 的工具段落，或 `SkillToolGroupActivationMiddleware` 的激活规则 |
| 技能专属知识 | 技能正文（Nacos/DB 源），或 `src/main/resources/skill-templates/*.md`（新建模板） |
| 技能附带文件 | 技能文件包（`references/**`、`scripts/**`、`assets/**`） |

### 2. 改技能正文要过质量门槛

- 正文 `strip()` 后 ≥ 200 字；
- 至少一个 Markdown 标题（1–3 个 `#` 后跟空格）；
- 出现「角色 / 规则 / 步骤 / 流程 / 输出 / 要求 / 职责」之一；
- 超限或不合规在导入时就被拒收，表现为对账里的 `missingInDb`。

### 3. frontmatter 只改允许的键

- 管理台保存只重写 `name` / `description` / `execution`，其余（`version` / `license` / `type` / `activation` / `setup` / `data`）原样保留；
- 要让技能能跑脚本，需要 `execution.enabled: true` **且** 环境层 `PAYROLL_SKILL_EXEC_ENABLED=true`，两个都满足才注册 `skill_run`。

### 4. 验证

```bash
mvn -o -pl payroll-tests -am test -Dtest=PromptLibraryTest -Dsurefire.failIfNoSpecifiedTests=false
```

- `PromptLibraryTest` 会断言关键规则还在（例如「银行卡号脱敏」），删规则前先看它为什么在那；
- 真实链路：起 runtime → 发一轮目标问题 → 看 `agent_start`/`tool_start` 是否符合预期 → 必要时看 `llm_usage`；
- 技能改动：`GET /api/admin/skills/{id}/overview` 看健康卡，`/versions` 看是否落了新版本。

### 5. 缓存与生效

- 正文变更会使智能体缓存键失效；`db` 源跨实例靠 `PAYROLL_SKILL_INVALIDATION_POLL_MS`（默认 15s）收敛；
- Nacos 源读到的是配置中心版本，改代码内置版本不会生效；
- 缓存没刷新时先看是否改了「同 ID 的另一份」。

## 检查表

- [ ] 没有在 Java 里新增提示词字符串。
- [ ] 技能正文过 200 字 / 标题 / 关键词三条门槛。
- [ ] frontmatter 未误删 `version`/`license`/`setup`。
- [ ] `PromptLibraryTest` 绿；真实对话行为符合预期。
- [ ] 审计里 `skill.update` 带 `revision` 与 `versionNo`（db 源）。
- [ ] 若改了金额相关口径，另按 `payroll-money-change` 走一遍。

## 反例

- 在 `PayrollAgentFactory` 里拼一段 `"你是薪酬专家……"` 字符串。
- 为了「让模型更准」把税率表抄进提示词——政策一变就全错，而且不可审计。
- 直接改 Nacos 上的正文但没重发文件包，导致 `mdHash` 不匹配、参考文件被忽略。
