# Nacos 角色技能发布模板

三个角色的提示词已从 `PayrollAgentFactory` 拆到 AgentScope 动态技能中，默认使用
`RoleSkills` 的代码内置版本。把同名配置发布到 Nacos 后，启动时设置
`PAYROLL_NACOS_SERVER` 即可优先使用外置版本；Nacos 不可用或某个 dataId 缺失时，
逐个技能回退内置版本，不会阻断启动。

## 约定

| 技能 | dataId | 对应智能体 |
| --- | --- | --- |
| `payroll-calculator` | `payroll-calculator.md` | 计算智能体 |
| `payroll-validator` | `payroll-validator.md` | 验证智能体 |
| `payroll-accountant` | `payroll-accountant.md` | 核算智能体 |

- group 默认 `payroll-agent`，可用 `PAYROLL_NACOS_GROUP` 修改。
- 内容为 Markdown，建议保留 frontmatter：

  ```markdown
  ---
  name: payroll-calculator
  description: 薪酬计算智能体：理解诉求并调用确定性工具完成核算，产出可复核的计算记录
  ---

  ## 角色
  ...
  ```

- 技能只承载角色职责、工具选择与输出格式。金额安全规则（禁止心算、期间格式、
  脱敏、额度语义等）固定在系统提示词中，不要移到 Nacos，避免远端配置缺失时失去约束。
- 本目录的 3 个 `.md` 是内置版本的同步模板，修改内置提示词后请重新生成再发布。
  内容以 `src/main/java/io/agentscope/examples/payroll/skill/RoleSkills.java` 为准。

## 发布

无鉴权 Nacos：

```bash
for id in payroll-calculator payroll-validator payroll-accountant; do
  curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$id.md" \
    --data-urlencode "group=payroll-agent" \
    --data-urlencode "type=markdown" \
    --data-urlencode "content@docs/nacos-skills/$id.md"
  echo " <- $id"
done
```

开启鉴权时先登录拿 token，再在请求中附加 `accessToken`：

```bash
TOKEN=$(curl -s -X POST "http://127.0.0.1:8848/nacos/v1/auth/login" \
  -d "username=nacos&password=你的密码" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=payroll-calculator.md" \
  --data-urlencode "group=payroll-agent" \
  --data-urlencode "accessToken=$TOKEN" \
  --data-urlencode "content@docs/nacos-skills/payroll-calculator.md"
```

## 流程编排配置

流程定义与全局默认流程与技能共用同一个 Nacos group：

| 配置 | dataId | 内容 |
| --- | --- | --- |
| 流程列表 | `payroll-flows.json` | 流程定义 JSON 数组，模板见同目录 `payroll-flows.json` |
| 默认流程 | `payroll-flow-settings.json` | `{"activeFlowId":"payroll-default"}` |

- 未发布 `payroll-flows.json` 时，管理台只显示代码内置的 `payroll-default`；
  未发布 settings 或 `activeFlowId` 为空时，对话沿用既有链路。
- 设置默认流程后下一轮对话生效；默认流程引用的所有智能体必须处于 `ONLINE`。
- 流程字段为 camelCase：`nodes[].agentId`、`nodes[].conditionValue`、`edges[].sourceHandle`。
- 也可以直接在管理台“流程编排”页签拖拽编辑，保存时会自动写回 `payroll-flows.json`。

```bash
# 发布流程列表
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=payroll-flows.json" \
  --data-urlencode "group=payroll-agent" \
  --data-urlencode "type=json" \
  --data-urlencode "content@docs/nacos-skills/payroll-flows.json"

# 把内置标准流程设为全局默认
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=payroll-flow-settings.json" \
  --data-urlencode "group=payroll-agent" \
  --data-urlencode "type=json" \
  --data-urlencode "content={\"activeFlowId\":\"payroll-default\"}"
```

## 启动与验证

```bash
PAYROLL_NACOS_SERVER=http://127.0.0.1:8848 \
java -Dfile.encoding=UTF-8 -jar target/payroll-agent.jar
```

启动日志应出现：

```
[Skill] 角色提示词优先读取 Nacos http://127.0.0.1:8848（group=payroll-agent，TTL=60s），缺失时回退代码内置版本
[Skill] 从 Nacos 加载技能 payroll-calculator（579 字）
[Skill] 生效技能：[payroll-calculator(nacos), payroll-validator(nacos), payroll-accountant(nacos)]
```

单个 dataId 缺失或 Nacos 不可用：

```
[Skill] Nacos 未提供 payroll-validator.md，技能 payroll-validator 回退代码内置版本
```

内容修改后最多等待 `PAYROLL_NACOS_SKILL_TTL_SECONDS`（默认 60 秒），下一轮对话会重新读取；
AgentScope 检测到技能内容变化后重建 SkillBox，无需重启应用。

## Agent 定义（payroll-agents.json）

管理台“Agent 管理”页签把整个智能体列表保存在同一个 dataId 中：

- dataId：`payroll-agents.json`，group 同上，type 为 `json`。
- 内容为 JSON 数组，字段使用 camelCase（`skillIds`、`createdBy`、`updatedAt` 等）。
- 示例见 [`payroll-agents.json`](payroll-agents.json)；该文件是三个内置智能体的快照，
  可直接发布作为初始配置。
- 首次在管理台保存时，系统会把“当前列表（含内置默认项）+ 修改”整体写回 Nacos；
  之后每次保存都会覆盖整个 dataId，因此不要在多处并发编辑同一份配置。
- Nacos 未启用、配置缺失或 JSON 损坏时，管理台回退到代码内置的三个智能体并禁用写操作。
- 智能体定义当前只用于 Agent 广场与管理台展示，尚未接入对话编排。

手工发布：

```bash
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=payroll-agents.json" \
  --data-urlencode "group=payroll-agent" \
  --data-urlencode "type=json" \
  --data-urlencode "content@docs/nacos-skills/payroll-agents.json"
```

启动后可访问 `GET /api/admin/agents/meta` 确认 `writable=true` 且 `location` 指向预期地址；
管理台保存操作会记录 `agent.create`/`agent.update`/`agent.delete` 审计事件。

