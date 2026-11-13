# Agent 管理模块交接说明

## 功能范围

参考智枢"专家智能体广场"，为管理台增加 Agent 定义的管理界面，并支持管理员全局指定薪酬核算使用的智能体：

- 卡片流展示全部智能体：头像、名称、标签、技能数、描述、可见范围、上下架状态、创建人/更新时间
- 顶部筛选：关键字、标签、技能、状态、可见范围
- 新建 / 编辑 / 删除（写操作需要 `agent:manage` 权限码）
- 全局核算智能体：仅公开且已上架的智能体可设；所有会话下一轮对话生效，失效自动回退默认计算专家
- Nacos 未启用时整个页面只读，列表回退为三个内置薪酬角色智能体

## 存储：Nacos 单个 JSON 配置

- dataId: `payroll-agents.json`，group 沿用 `payroll-agent`（见 `NacosSkillSettings`）
- 整份列表作为一个 JSON 数组读写，读路径 TTL 缓存 60s，写入后立即刷新缓存
- 首次写入会把当前完整列表（含内置默认）持久化，之后内置默认不再参与回退
- 配置内容为 `AgentDefinition` 的 camelCase JSON（该类用 `@JsonNaming` 覆盖了全局 SNAKE_CASE）

```json
[
  {
    "id": "payroll-calculator",
    "name": "薪酬计算专家",
    "avatar": "🧮",
    "description": "薪酬计算智能体：理解诉求并调用确定性工具完成核算…",
    "tags": ["薪酬", "内置"],
    "skillIds": ["payroll-calculator"],
    "visibility": "PUBLIC",
    "status": "ONLINE",
    "createdBy": "system",
    "createdAt": "2026-11-07T21:40:42.888",
    "updatedAt": "2026-11-07T21:40:42.888"
  }
]
```

全局核算智能体单独存放在 dataId `payroll-agent-settings.json`，group 同为 `payroll-agent`：

```json
{ "calculationAgentId": "agent-1a2b3c4d" }
```

读取带 60s TTL 缓存，管理接口写入后立即刷新；配置缺失或为空时使用 `payroll-calculator`。

## 运行时：按技能选择管线

| 选中的智能体 | 行为 |
| --- | --- |
| 技能含 `payroll-calculator` | 沿用「计算 → 验证 → 核算」三段流水线 |
| 技能不含 `payroll-calculator` | 单智能体模式：可用工具由技能并集决定（计算技能→计算工具，验证技能→验证工具），直接输出最终答复 |
| 未配置 / 已下架 / 已删除 | `NacosAgentRegistry.activeAgent()` 回退内置薪酬计算专家 |

- `PayrollChatService` 每轮通过 `Supplier<AgentDefinition>` 读取配置；`Team.matches()` 比较 id/名称/描述/技能，变化时关闭旧实例并重建
- 单智能体模式仍会注入 `COMMON_RULES`（金额必须来自工具）与 `SKILL_BOOT_RULE`（先加载技能）
- 聊天页 `/api/health` 返回 `chat_agent`，工具栏徽标展示当前核算智能体

## 后端结构

| 文件 | 职责 |
| --- | --- |
| `domain/AgentDefinition.java` | 定义记录；visible=`PUBLIC/PRIVATE`，status=`ONLINE/OFFLINE` |
| `skill/NacosAgentRegistry.java` | 读写 Nacos 配置，内置默认回退；`activeAgent()` / `setActiveAgent()` 管理全局核算智能体 |
| `skill/NacosConfigClient.java` | 新增 `publish` / `remove`，沿用 v1 OpenAPI 与 token 逻辑 |
| `web/AgentAdminController.java` | `/api/admin/agents` 列表/元信息/技能/CRUD/核算智能体，写操作 `@RequirePermission(AGENT_MANAGE)` |
| `agent/PayrollAgentFactory.java` | `createTeam(tools, activeAgent)`：按技能决定三段流水线或单智能体 |
| `service/PayrollChatService.java` | 每轮解析核算智能体，配置变化时重建 Team |
| `support/Permissions.java` | 新增 `agent:manage`（ADMIN 自动拥有；HR/VIEWER 不含） |

接口：

- `GET /api/admin/agents?keyword=&tag=&skillId=&status=&visibility=`
- `GET /api/admin/agents/meta` → `{writable, location, tags, activeAgentId}`
- `GET /api/admin/agents/active` → `{agentId, name, configured}`
- `PUT /api/admin/agents/{id}/active` → 设为全局核算智能体（审计 `agent.activate`）
- `GET /api/admin/agents/skills` → 可绑定技能（来自 CompositeSkillRepository）
- `POST /api/admin/agents`、`PUT /api/admin/agents/{id}`、`DELETE /api/admin/agents/{id}`

入参校验：名称必填且 ≤30 字；技能必须存在于技能仓库；标签去重且 ≤12 字；头像空则默认 🤖。

## 前端结构

- Vue 3.5 生产版已放本地：`static/vendor/vue.global.prod.js`（不依赖外网 CDN）
- `static/agents.js`：单个 Options API 组件，挂载到 `#agents-app`；`initAgentsTab()` 由 `app.js` 的 `showTab('agents')` 调用
- `index.html`：新增 `data-tab="agents" data-permission="agent:manage"` 导航按钮与 `#tab-agents` 容器
- 复用 `app.js` 的全局 `api()` / `toast()` / `showError()`；样式追加在 `style.css` 末尾的"Agent 管理"段

## 调试记录（踩过的坑）

1. **Nacos 发布返回 HTTP 500 `invalid dataId: payroll-agents.json,payroll-agents.json`**
   参数同时出现在 query string 和 form body，Nacos 把重复值合并成逗号串。POST 只放 body，DELETE 才走 query。
2. **Vue 页面不加载数据**
   `createApp(App)` 返回应用实例，不暴露组件方法；必须保存 `createApp(App).mount('#agents-app')` 的返回值再调 `reload()`。
3. **admin 突然缺少 agent:manage**
   本机 8080 端口跑着旧代码实例，与测试实例共用 `./data/payroll` H2 库。旧实例启动时会把内置角色权限回写为旧集合（`PayrollDatabase` 的 builtin 角色同步逻辑）。重启新实例即可恢复；正式环境不存在新旧并存问题。
4. **Nacos 未启动时**
   读路径回退内置列表，写操作返回 400"未启用 Nacos…"，前端保存按钮置灰。
5. **控制台中文乱码**
   Git Bash 默认 GBK 显示，接口返回本身是 UTF-8；排查时用 `python -m json.tool` 或浏览器 network 看。

## 本地验证

```bash
# 启动（需先起 Nacos 8848，账号 nacos/nacos；对话链路另需启动 payroll-agent-runtime.jar）
PAYROLL_NACOS_SERVER=http://127.0.0.1:8848 PAYROLL_NACOS_USERNAME=nacos PAYROLL_NACOS_PASSWORD=nacos \
  PAYROLL_WEB_PORT=8091 java -jar payroll-backend/target/payroll-backend-exec.jar

# 登录拿 cookie
curl -c jar.txt -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' http://127.0.0.1:8091/api/auth/login

curl -b jar.txt http://127.0.0.1:8091/api/admin/agents/meta
```

浏览器：`/` → 登录 admin → "Agent 管理" tab → 新建/编辑/筛选；卡片上的"设为核算智能体"写入 `payroll-agent-settings.json`，聊天页工具栏徽标同步显示。

自动化测试（`mvn test`，154 个）：

- `AgentAdminWebTest`：列表/元信息/技能/当前核算智能体可读 + 名称缺失、未知技能返回 400
- `NacosAgentRegistryTest`：核算智能体默认值、写入持久化、下架/删除回退、非法设置拒绝
- `AgentAdminControllerTest`：`agent.activate` 审计、active 返回值
- `DynamicSkillWiringTest`：多技能过滤与"计算技能决定流水线"

## 后续待办

- 调用量/点赞统计：需要埋点表或会话侧计数，二期
- 按会话/按用户选择智能体：当前为管理员配置的全局设置
- Agent 详情页：展示绑定的技能内容、知识库（当前只有技能维度）
