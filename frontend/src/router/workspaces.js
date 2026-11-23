// 双工作台信息架构（M8 / D1）：页面归属、菜单生成与默认落地页。
// 页面菜单数据来自路由 meta（workspace + menu），这里只做纯函数，便于单测。

export const WORKSPACES = {
  platform: { key: 'platform', title: '平台工作台', short: '平台' },
  payroll: { key: 'payroll', title: '薪酬工作台', short: '薪酬' },
  governance: { key: 'governance', title: '平台治理', short: '治理' },
};

export const WORKSPACE_ORDER = ['platform', 'payroll', 'governance'];

// 旧路径保留一个版本，书签、断点恢复与邮件链接不失效。
export const LEGACY_REDIRECTS = {
  '/agents': '/platform/agents',
  '/flows': '/platform/flows',
  '/skills': '/platform/skills',
  '/team': '/platform/team',
  '/kb': '/platform/kb',
  '/employees': '/payroll/employees',
  '/reviews': '/payroll/reviews',
  '/attendance': '/payroll/attendance',
  '/runs': '/payroll/runs',
  '/tasks': '/payroll/tasks',
  '/policies': '/payroll/policies',
  '/models': '/platform/models',
  '/users': '/platform/users',
  '/audit': '/platform/audit',
  '/usage': '/platform/usage',
  '/profile': '/platform/profile',
};

/** 按角色计算登录落地页：平台能力 → 平台工作台，薪酬读写 → 薪酬工作台，其余留在对话。 */
export function landingPath(user) {
  if (!user) {
    return '/chat';
  }
  const permissions = Array.isArray(user.permissions) ? user.permissions : [];
  const has = (code) => permissions.includes(code);
  if (has('agent:manage')) {
    return '/platform/agents';
  }
  if (has('employee:read')) {
    return '/payroll/employees';
  }
  if (has('payroll:read')) {
    return '/payroll/runs';
  }
  if (has('kb:read')) {
    return '/platform/kb';
  }
  return '/chat';
}

/** 从路由记录生成按工作台分组、按权限过滤的菜单。 */
export function menuFromRoutes(routes, hasPermission, profileEnabled) {
  const items = routes
    .filter((route) => route.meta && route.meta.menu)
    .map((route) => ({ path: route.path, ...route.meta.menu }))
    .filter((item) => !item.permission || hasPermission(item.permission))
    .filter((item) => item.feature !== 'profile' || profileEnabled)
    .sort((a, b) => (a.order || 0) - (b.order || 0));
  return WORKSPACE_ORDER.map((key) => ({
    ...WORKSPACES[key],
    items: items.filter((item) => item.workspace === key),
  })).filter((group) => group.items.length);
}

// 权限码只读分组（M8 步骤 5）：不改权限判定，只决定展示位置。
export const PERMISSION_GROUPS = [
  { key: 'platform', title: '平台能力', codes: ['agent:manage', 'flow:manage', 'skill:manage', 'kb:read', 'kb:create', 'kb:manage'] },
  { key: 'payroll', title: '薪酬业务', codes: ['employee:read', 'employee:write', 'review:read', 'review:write', 'attendance:read', 'attendance:write', 'payroll:read', 'payroll:write', 'payroll:approve', 'approval:manage', 'import:use', 'policy:read', 'policy:write'] },
  { key: 'governance', title: '平台治理', codes: ['user:manage', 'role:read', 'role:manage', 'department:read', 'department:manage', 'audit:read', 'usage:read', 'profile:read', 'profile:write', 'profile:manage'] },
  { key: 'common', title: '通用', codes: ['chat:use'] },
];

const PLATFORM_PREFIXES = ['agent', 'flow', 'skill', 'kb', 'vector', 'team', 'model', 'usage'];
const PAYROLL_PREFIXES = ['employee', 'review', 'attendance', 'policy', 'payroll', 'batch', 'approval', 'import'];
const GOVERNANCE_PREFIXES = ['user', 'role', 'department', 'audit', 'settings', 'login', 'logout', 'password'];

function matchesPrefix(key, prefixes) {
  return prefixes.some((prefix) => key === prefix || key.startsWith(`${prefix}.`) || key.startsWith(`${prefix}_`));
}

/** 审计动作 → 工作台（展示用，未识别的动作归“通用”）。 */
export function workspaceOfAction(action, resourceType) {
  const actionKey = String(action || '').trim().toLowerCase().split(/\s+/)[0] || '';
  const resourceKey = String(resourceType || '').trim().toLowerCase();
  for (const key of [actionKey, resourceKey]) {
    if (matchesPrefix(key, PLATFORM_PREFIXES)) return 'platform';
    if (matchesPrefix(key, PAYROLL_PREFIXES)) return 'payroll';
    if (matchesPrefix(key, GOVERNANCE_PREFIXES)) return 'governance';
  }
  return 'common';
}

/** 工作台的默认页：该工作台第一个可见菜单项。 */
export function defaultPathForWorkspace(groups, workspace) {
  const group = groups.find((item) => item.key === workspace);
  return group && group.items.length ? group.items[0].path : '/chat';
}
