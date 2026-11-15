# 知识库前端 Console 对齐规格

- 状态：已实现（2026-11-15），前端迁移与接口适配完成
- 日期：2026-11-15
- 基准：`D:\workspace\knowledge-base-console\frontend`
- 关联：`docs/kb-chat-integration-design.md`、`docs/three-module-split-spec.md`
- 硬约束：**知识库页面的结构与排版以 console 前端为唯一基准，不私自调整布局**；改造范围仅限知识库页面，薪酬管理台外壳与其他页面保持原样

## 一、目标与范围

| 项 | 决策 | 说明 |
| --- | --- | --- |
| 布局基准 | console frontend 原样移植 | 页面骨架、间距、栅格、页签顺序均来自 console，不重新设计 |
| 改造范围 | 仅知识库页面 | `/kb`、`/kb/create`、`/kb/:id`；侧边栏、顶栏、其他业务页不改 |
| 组件方案 | 引入 Element Plus 2.x（zh-cn） | console 页面本身依赖 Element Plus，直接复用组件与样式 |
| 后端契约 | 差异全部收敛在适配层 | console 页面组件不感知薪酬后端的参数名与响应结构 |
| 鉴权方式 | 同源 Cookie 会话 | 薪酬后端没有 `kb_token`，也不再使用 Bearer |

## 二、文件对照

| 能力 | console 来源 | 薪酬落地位置 |
| --- | --- | --- |
| 应用装配 | `main.js` | `frontend/src/main.js` |
| 主题与页面骨架 | `src/styles/*` | `frontend/src/kb/styles/kb-console.css` |
| 列表页 | `views/SeriesListView.vue` | `frontend/src/kb/views/SeriesListView.vue` |
| 四步向导 | `views/SeriesCreateView.vue` | `frontend/src/kb/views/SeriesCreateView.vue` |
| 详情页 | `views/SeriesDetailView.vue` | `frontend/src/kb/views/SeriesDetailView.vue` |
| 详情页签 | `views/detail/*.vue` | `frontend/src/kb/views/detail/*.vue`（概览/知识/目录/标签/任务/检索/成员/设置） |
| 卡片组件 | `components/SeriesCard.vue` | `frontend/src/kb/components/SeriesCard.vue` |
| API | console 直连后端 | `frontend/src/kb/api/index.js`（console 签名 + 薪酬映射） |
| 请求底座 | console axios 实例 | `frontend/src/kb/api/http.js`（fetch + 同源 Cookie + `ApiResponse` 解包） |
| 鉴权视图 | `stores/auth`（Pinia） | `frontend/src/kb/stores/auth.js`（复用薪酬 `auth` store 的形态适配） |

## 三、布局不变量

这些数值只在 console 原始 CSS 中定义，薪酬侧不覆盖：

| 规则 | 值 | 位置 |
| --- | --- | --- |
| 基础页容器 | `padding: 30px 36px 56px`，`max-width: 1360px`，`margin: 0 auto` | `kb-console.css:81-85` |
| 窄屏容器 | `max-width: 900px` 时 `padding: 22px 16px 42px` | `kb-console.css:351-354` |
| 详情页 | `max-width: 1440px` | `SeriesDetailView.vue:260-262` |
| 向导页 | `max-width: 1180px` | `SeriesCreateView.vue:692-694` |
| 卡片栅格 | `repeat(auto-fill, minmax(310px, 1fr))`，`gap: 16px`，窄屏单列 | `SeriesListView.vue:177-181`、`204-206` |
| 内容区接管 | 路由命中 `.kb-app` 时内容区 `padding: 0` 并换纸面底色 | `kb-console.css:70-73` |
| 详情页签 | 8 个页签固定顺序：概览/知识管理/目录管理/标签管理/任务中心/检索测试/成员权限/设置 | `SeriesDetailView.vue:72-81` |

为什么不用现成的 `.content.flush`：外壳的 `flush` 会附带 `overflow: hidden`，知识库页面需要整页滚动，因此在 `kb-console.css` 里用 `.content:has(.kb-app)` 只清 padding 与底色。

## 四、路由与导航

| 路径 | 名称 | 组件 |
| --- | --- | --- |
| `/kb` | `kb` | `SeriesListView.vue` |
| `/kb/create` | `kb-create` | `SeriesCreateView.vue` |
| `/kb/:id(\d+)` | `kb-detail` | `SeriesDetailView.vue` |

- 侧边栏菜单项 `{ path: '/kb', label: '知识库', permission: 'kb:read' }` 复用既有外壳；`router-link-active` 的前缀匹配让详情页仍保持「知识库」高亮（已实测）。
- 页面内跳转统一为 `/kb/...`，不再出现 console 的 `/series/...`。
- 旧单页实现 `frontend/src/views/admin/KbView.vue` 已从路由移除，本次未删除文件。

## 五、运行时与端口

| 环境 | 端口 | 说明 |
| --- | --- | --- |
| 前端开发 | 5173 | `vite.config.js` `server.port`，`/api` 代理到 `127.0.0.1:8080` |
| 前端容器/nginx | 8880 | 生产入口 |
| 薪酬后端 | 8080 | 提供 `/api/**` |
| Agent Runtime | 8100 | 知识库页面不直连 |

## 六、接口适配矩阵

适配层原则：console 页面按原签名调用，`kb/api/index.js` 负责参数名、分页方式与响应结构转换。

| 领域 | console 契约 | 薪酬后端契约 | 适配方式 |
| --- | --- | --- | --- |
| 统一响应 | 业务体直接返回 | `{status, data, message}`，错误 `status=ERROR` | `http.js` 解包 `data`，`ERROR` 抛错并提示 |
| 认证 | `kb_token` + Bearer | 同源会话 Cookie，401 跳 `/login` | `credentials: 'same-origin'`，无 Authorization 头 |
| 系列列表 | `page/size/deptId/scope` | `dept_id`；只支持 `scope=mine` | 参数改名；`scope=maintainable` 取 `size=100` 后按 `role ∈ {EDITOR,ADMIN,OWNER}` 客户端过滤再分页 |
| 系列字段 | `configVersionId` | `activeConfigVersionId` | `mapSeries` |
| 部门数据 | 部门树 | `/admin/departments` 扁平列表（需 `department:read`） | 选择器直接用 `{id,name}` |
| 用户数据 | 组织用户 | `/admin/users`（需 `user:manage`） | 成员选择改走库内 `members/candidates`，见下 |
| 配置读写 | `parse/chunk/retrieval` | `parseConfig/chunkConfig/retrievalConfig`，保存含 `rebuildMode` | `mapConfig` + 保存时反向组装 |
| 配置保存结果 | `configVersionId` | `{config, rebuildTasks}` | 取 `config.versionId` 映射 |
| 目录树 | `tree`、`docCount` | `/directories` 列表、`documentCount` | `mapDirectory` 递归映射 |
| 目录移动 | `targetParentId` | `parentId` | 参数改名 |
| 目录删除 | 可迁移到上级 | `DELETE` body `{confirmName}`，非空目录必须精确名称，无迁移策略 | 透传确认名；无对应的 MOVE_TO_PARENT 分支 |
| 标签 | `docCount` | `documentCount` | `mapTag` |
| 文档列表 | 服务端分页 | 一次返回全量 | 客户端 `paginate` |
| 文档字段 | `directoryName` | `directoryPath` | `mapDocument` |
| 文档上传 | `directoryId/dedupeAction`，结果 `items/failedItems` | `directory_id/dedupe_action`，结果 `documents/messages` | 重组 FormData 与结果 |
| 文档批量 | `successCount/failedCount/failedItems` | `succeeded/failed/messages` | 字段映射，失败信息按 `：` 拆为「文件名 + 原因」 |
| 状态汇总 | 大写键 | 小写键 | `mapSummary` 统一转大写 |
| 任务列表 | 服务端分页 | 只支持 `limit` | 取 `limit = max(size, page*size)` 后客户端切片 |
| 任务日志 | 独立日志接口 | 任务详情内含 `logs` | `detail` 中提取 |
| 成员列表 | `account/deptName/memberCount` | `subjectExtra = "账号 · 部门"` | `mapMember` 拆分，`null` 部门归一为空 |
| 成员授权 | `{grants:[{subjectId,...}]}` | `{subjectType, subjectIds[], role}` | 逐条组装为单元素 `subjectIds`，成功后重取列表 |
| 成员候选人 | 组织接口 | `GET /members/candidates`（需库级 ADMIN） | `{subjectType, subjectId, name, extra}` 直接映射 |
| 检索测试 | `citations/noAnswer/totalCandidates/...` | `{hits, diagnostics}` | 组装返回，`chunkLevel` 由是否存在父内容推断，`documentExt` 取扩展名 |

## 七、鉴权与权限

| 场景 | 规则 |
| --- | --- |
| 401 | 非 `/login` 路径直接跳转登录页 |
| 平台管理员 | `role_code === 'ADMIN'` 或权限含 `kb:manage` |
| KB 页面准入 | 侧边栏权限 `kb:read`；库内角色决定页签可见性（`canMaintain = EDITOR/ADMIN/OWNER`，`canAdmin = ADMIN/OWNER`） |
| 文档下载 | `fetch` 带 `credentials: 'same-origin'`，失败抛「下载失败」 |
| 成员候选 | 仅 `ADMIN/OWNER` 可拉取；非管理员时选择器为空并保留手动提示 |

## 八、已知差异

| 项 | 现状 | 影响 | 处理建议 |
| --- | --- | --- | --- |
| 目录删除策略 | 薪酬要求非空目录输入精确名称，无「迁移到上级目录」 | console 若依赖迁移分支会报后端错误 | 保持现状，按薪酬语义提示；如需补齐需改后端 |
| 建库「用此库问答」 | 详情页跳 `/chat?seriesId=...`，薪酬对话页未消费该参数 | 不会自动绑定知识库 | 后续在对话页补会话绑定，本次不改 |
| 文档/任务分页 | 客户端分页 | 数据集大时首屏请求偏重 | 待后端补分页接口 |
| 可维护范围 | 客户端过滤 | 依赖一次取 100 条 | 后端补 `scope` 维度后可移除 |
| 任务进度文案 | 直接展示后端 `stage · percent` | 与 console 文案风格略有差异 | 属数据字段差异，不改布局 |
| 旧页面残留 | `views/admin/KbView.vue` 不再被路由引用 | 打包不含该 chunk | 确认无用后可单独清理 |

## 九、验收记录（2026-11-15）

| 检查项 | 结果 |
| --- | --- |
| `npm run build` | 通过，1676 个模块，产物含 `SeriesListView/SeriesCreateView/SeriesDetailView` 独立 chunk |
| `/kb` | 2 个知识库卡片、部门/状态筛选、搜索、分页正常；容器 padding 与 `max-width: 1360px` 生效，窄屏落到 console 自带的响应式值；侧边栏「知识库」高亮 |
| `/kb/4` | hero + 5 项统计条 + 8 页签；概览配置快照、文档状态、最近任务均渲染 |
| 成员权限页签 | 列表显示 `admin · 拥有者`；「添加授权」对话框加载候选人，无部门用户显示「未分配部门」 |
| 设置页签 | 回填基础信息、当前配置 v1、配置版本历史 |
| 检索测试 | `kafka 如何保证消息顺序`：候选 36、返回 3、4629 ms、Elasticsearch、混合 + 精排，引用含切片序号与页码 |
| `/kb/create` | 四步向导、步骤条、部门预填、`max-width: 1180px` |
| `/chat` | 页面与导航无回归 |

## 十、变更文件

| 文件 | 变更 |
| --- | --- |
| `frontend/src/main.js` | 注册 Element Plus（zh-cn）与图标，引入 Element Plus 样式和 `kb-console.css` |
| `frontend/src/router/index.js` | 新增三条 `/kb` 路由，移除旧知识库单页路由 |
| `frontend/src/kb/**` | console 页面、组件、样式、工具整体迁入 |
| `frontend/src/kb/api/index.js` | 契约适配层 |
| `frontend/src/kb/api/http.js` | fetch 请求底座与统一错误提示 |
| `frontend/src/kb/stores/auth.js` | 薪酬用户 → console 鉴权视图 |
| `frontend/src/kb/utils/download.js` | 去除 Bearer，改同源 Cookie |
