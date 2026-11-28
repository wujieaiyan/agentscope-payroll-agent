# frontend

## 职责

Vue 3 + Vite 单页控制台，构建产物 `dist/` 由本机 nginx（8880）托管，`/api` 反代到 Web（8080）。与后端只通过 `/api` 交互，没有服务端渲染。

信息架构按三个工作区组织：`platform`（平台工作台）/ `payroll`（薪酬工作台）/ `governance`（平台治理），对话页 `chat` 全局唯一。

## 目录地图

| 路径 | 内容 |
| --- | --- |
| `src/main.js` | 挂载 Vue + Element Plus + 路由 |
| `src/router/index.js` | 路由 + 菜单元数据（`workspacePage()` 生成） |
| `src/router/workspaces.js` | 工作区定义、落地页、旧路径重定向 |
| `src/components/AppLayout.vue` | 外壳：侧栏分组、面包屑、改密入口 |
| `src/views/ChatView.vue` | 对话页（3000+ 行，SSE 状态机全在这里） |
| `src/views/admin/*.vue` | 管理台页面（旧一代：手写 table + 自研 toast） |
| `src/kb/**` | 知识库控制台（新一代：Element Plus + `components/ui`） |
| `src/api/http.js`、`src/api/sse.js` | 统一请求与 SSE 解析 |
| `src/stores/*.js` | `reactive` 单例（没有 Pinia）：auth / toast / activeTurn / tabSync / skillEnv |
| `src/composables/useModalFocus.js` | 弹窗焦点管理 |
| `tests/unit/`、`tests/e2e/` | vitest 单测、playwright e2e |

## 新增页面（固定步骤）

1. 建 `src/views/admin/<Name>View.vue`。
2. 在 `router/index.js` 用 `workspacePage(path, name, file, title, workspace, {label, icon, order}, {permission})` 注册——菜单由 `meta.menu` 自动生成，**不需要改 `AppLayout.vue`**。
3. `workspace` 只能是 `platform` / `payroll` / `governance`；旧路径重定向加到 `workspaces.js` 的 `LEGACY_REDIRECTS`，不要在两处各写一份。
4. 操作按钮用 `can('xxx:write')` 判权限；请求走 `api/http.js`（它会统一抛 `ApiError` 并弹 toast）。
5. 补一条 vitest 单测；图表优先用无第三方依赖的 SVG 组件。
6. `npm run build` + `npm run test`，并在 nginx 8880 上走一遍主路径。

## 改这里的规则

1. **只保留一套请求层与提示层**：新代码不要用 `fetch` 直连，不要用 `window.confirm`，不要 `role_code === 'ADMIN'` 判权（统一 `stores/auth.js#can()`）。
2. 权限码与后端 `Permissions` 一致，不要在前端发明新码。
3. 中文文案直接写中文（项目没有 i18n），但不要硬编码内部地址或密钥。
4. 长列表/大表格要分页或虚拟化，不要全量 `v-for`。
5. SSE 相关改动要同步 `ChatView` 的事件处理与 `api/sse.js` 的解析，并检查卸载时是否 `abort`。

## 坑

- **两代前端并存**：`kb/` 有自己的 `api/http.js` 与 `stores/auth.js`（还会用 `role_code === 'ADMIN'` 短路）。改动 KB 页面时注意它不走主应用的错误处理。
- 路由守卫只判登录，不判 `meta.menu.permission`：直接输入 URL 能进页面，接口才 403，页面会停在空壳。
- `ChatView.vue` 3200 行、无组件级测试；`handleEvent` / `resumeLoop` 是最容易改坏的两段。
- 会话列表加载失败会触发「自动新建会话」，网络抖动时表现为「会话全没了」。
- e2e 的 `webServer` 固定 `127.0.0.1:4173` 且跑的是 vite dev；浏览器路径默认 `D:/playwright-browsers`，只能在本地用。

## 验证

```bash
cd frontend
npm run build      # 必须绿
npm run test       # vitest
npm run test:e2e   # 需要 playwright 浏览器，仅本地
```

`npm run build` 通过不等于页面可用：DoD 要求在 nginx 8880 上真实走通主路径。
