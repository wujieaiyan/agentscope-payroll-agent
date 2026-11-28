---
name: payroll-admin-api
description: 新增或修改管理台后端接口时使用。按本仓库的四件套落地：权限码登记、@RequirePermission、审计事件、*WebTest；Controller 只放 payroll-backend，领域逻辑回落 payroll-common，写接口支持 revision 乐观并发并返回 409。
---

# 管理台接口改造

## 适用场景

- 管理台要新增页签/按钮对应的接口；
- 给已有接口加字段、加筛选、加分页；
- 修「接口 403 / 前端看不到按钮 / 操作没进审计」这类问题。

## 四件套（缺一项就不算完成）

### 1. 权限码

- 在 `payroll-common/.../support/Permissions.java` 登记，命名 `<资源>:read|write|manage`；
- 新能力带工作台/场景前缀，**不重命名已有码**（角色种子、前端判断、测试都依赖）；
- 内置角色（ADMIN/HR/VIEWER）默认权限需要一并确认，否则页面可见但调不通。

### 2. Controller

- 只在 `payroll-backend` 的 `web` 或 `kb` 包；**`payroll-common` 的 `web` 包只放原语**（异常处理、SSE、拦截器）。
- 类或方法加 `@RequirePermission`（本仓库不用 Spring Security 的 `@PreAuthorize`）。
- 领域逻辑进 `payroll-common` 的 service，不要在 Controller 里拼装业务。
- 错误直接抛业务异常，由 `ApiExceptionHandler` 统一映射；不要自己 catch 后造响应体。

### 3. 审计

- 写操作必须落审计：`action` / `resource_type` / `resource_id`，修改与删除带变更前快照；
- 敏感字段（口令、令牌、密钥、银行卡、证件号）不得进 `payload_json`；
- 注意 `AuditFilter` 的兜底会把**整段请求体**落库，因此新接口的 body 里不要出现未登记的敏感字段名。

### 4. 测试

- `payroll-tests/.../XxxWebTest.java`，`@Tag("integration")`；
- 至少三条：有权限 200、无权限 403、写操作产生审计行；
- 命名与被测 Controller 对应，`@DisplayName` 写中文行为。

## 接口契约

| 场景 | 约定 |
| --- | --- |
| 列表 | 分页入参 `page`/`size`（或 cursor），返回 `total`，禁止无界返回 |
| 乐观并发 | 写接口接受可选 `?revision=`，不一致返回 409 并回显 `currentRevision` |
| 冲突语义 | 409 = 状态/版本冲突（可重试）；400 = 参数错；403 = 权限；429 = 限流 |
| 幂等 | 会产生外部副作用的写接口必须用条件更新抢占状态，不能 check-then-act |
| 命名 | 新增 KB 接口用 camelCase（`@KbCamel`），对话相关沿用 snake_case，与本模块既有风格一致 |

## 检查表

- [ ] 权限码已登记且进了角色默认集合。
- [ ] `@RequirePermission` 挂在类或方法上，未被 `PUBLIC_PATHS` 意外放行。
- [ ] 写操作有审计，且 body 无敏感明文。
- [ ] 列表有分页与上限。
- [ ] 并发/重复提交有明确结果（409 或幂等返回），不靠 `synchronized`。
- [ ] 四条测试（200/403/审计/冲突）齐全。
- [ ] 前端页面已加 `can()` 判断，菜单项权限与接口一致。

## 反例

- 只在 Controller 加 `@RequirePermission`，忘了在 `Permissions` 登记 —— 权限测试会红，或者角色全量受限。
- 用 `if (!"OK".equals(response.getMessage()))` 判断业务结果。
- 为了「先跑通」把新接口加进 `PUBLIC_PATHS`。
