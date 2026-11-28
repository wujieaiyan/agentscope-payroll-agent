---
name: payroll-frontend-page
description: 新增或改造管理台页面时使用。按本仓库的信息架构落地：views/admin 建页面、router 用 workspacePage 注册并声明权限与菜单元数据、操作按钮用 can() 判权、请求统一走 api/http.js，并完成单测、构建与 nginx 8880 实机验证。
---

# 管理台页面改造

## 适用场景

- 新增一个管理页（列表、表单、详情、看板）；
- 给已有页面加按钮、筛选、导出；
- 排查「菜单不显示 / 点了 403 / 页面空白 / 切页数据串了」。

## 铁律

1. **不要改 `AppLayout.vue` 加菜单**：菜单由路由 `meta.menu` 自动生成。
2. **只保留一套请求层与提示层**：新代码用 `src/api/http.js`，不要 `fetch` 直连，不要 `window.confirm`，不要自研 toast。
3. **权限只用 `can('xxx:write')`**，不要写 `role_code === 'ADMIN'`。
4. 图表优先用无第三方依赖的 SVG 组件（本仓库既有做法），不要引新图表库。

## 步骤

### 1. 建页面

`src/views/admin/<Name>View.vue`。三态必须齐：`loading` / `empty`（真的没数据）/ `error`（请求失败，带重试按钮）。
当前常见缺陷是把「请求失败」渲染成「没有数据」，不要复制这种写法。

### 2. 注册路由

```js
workspacePage('payroll/<path>', '<name>', '<Name>View', '<页面标题>', 'payroll',
  { label: '<菜单名>', icon: '<ICONS 里的键>', order: 50 },
  { permission: '<资源>:read' })
```

- `workspace` 只能是 `platform` / `payroll` / `governance`；
- 需要新图标时在 `router/index.js` 的 `ICONS` 里加一条 24×24 path；
- 旧路径迁移加到 `router/workspaces.js` 的 `LEGACY_REDIRECTS`，**不要在两处各写一份重定向**。

### 3. 接数据

- `api/http.js` 已统一处理 `ApiError` 与 toast；页面里只关心成功分支；
- 列表必须有分页或上限；不要把后端无界接口直接灌进 `v-for`；
- 详情页要处理 404/403，不要只处理成功。

### 4. 权限

- 页面级：`meta.menu.permission` 控制菜单可见性；
- 操作级：按钮 `v-if="can('payroll:approve')"`，不要在点击后才发现 403；
- 权限码必须与后端 `Permissions` 一致。

### 5. 验证

```bash
cd frontend
npm run build
npm run test
```

- 补一条 vitest：纯函数（格式化/校验/权限判断）或组件渲染的三态；
- 构建通过与单测通过**不等于可用**：DoD 要求在 nginx 8880 上真实打开页面跑主路径（登录 → 进入页面 → 完成一次读写）。

## 检查表

- [ ] 路由归属正确，菜单出现且分组对。
- [ ] 无权限用户看不到按钮，直接输 URL 有明确 403 或空态说明。
- [ ] loading/empty/error 三态齐，错误态可重试。
- [ ] 切页/切会话无旧响应覆盖新状态（异步请求带 `activeId` 或序号校验）。
- [ ] 定时器、SSE、`AbortController` 在 `onBeforeUnmount` 里清理。
- [ ] 列表有分页；长文本不逐帧正则处理。
- [ ] `npm run build` + `npm run test` 绿，并实机走通。

## 反例

- 在 `ChatView.vue` 里继续堆状态，而不是抽 composable。
- 用 `v-html` 渲染后端文本（本仓库只允许 `MarkdownPreview` 里经 DOMPurify 的白名单用法）。
- 为了「先能用」把接口错误 catch 掉，只打 `console.log`。
