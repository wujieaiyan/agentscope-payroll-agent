import { describe, expect, it } from 'vitest';
import router from '../../src/router';
import {
  LEGACY_REDIRECTS,
  landingPath,
  menuFromRoutes,
  workspaceOfAction,
} from '../../src/router/workspaces';

const has = (codes) => (code) => codes.includes(code);

describe('双工作台路由', () => {
  it('所有旧路径重定向到新前缀', () => {
    for (const [from, to] of Object.entries(LEGACY_REDIRECTS)) {
      const record = router.resolve(from).matched.at(-1);
      expect(record, from).toBeTruthy();
      expect(record.redirect, from).toBe(to);
    }
    const kbDetail = router.resolve('/kb/12').matched.at(-1);
    expect(kbDetail.redirect({ params: { id: '12' } })).toBe('/platform/kb/12');
    expect(router.resolve('/kb/create').matched.at(-1).redirect).toBe('/platform/kb/create');
  });

  it('平台角色落到智能体页', () => {
    expect(landingPath({ permissions: ['agent:manage'] })).toBe('/platform/agents');
  });

  it('薪酬角色落到员工页', () => {
    expect(landingPath({ permissions: ['employee:read', 'payroll:read'] })).toBe('/payroll/employees');
  });

  it('纯员工留在对话页', () => {
    expect(landingPath({ permissions: ['chat:use'] })).toBe('/chat');
    expect(landingPath(null)).toBe('/chat');
  });
});

describe('按权限生成菜单', () => {
  const routes = router.getRoutes();
  const paths = (groups) => groups.flatMap((group) => group.items.map((item) => item.path));

  it('HR 看到薪酬与知识库，看不到治理项', () => {
    const groups = menuFromRoutes(routes, has(['employee:read', 'kb:read', 'profile:read', 'chat:use']), false);
    const all = paths(groups);
    expect(all).toContain('/payroll/employees');
    expect(all).toContain('/platform/kb');
    expect(all).not.toContain('/platform/models');
    expect(all).not.toContain('/platform/audit');
    expect(all).not.toContain('/platform/users');
  });

  it('纯员工看不到治理项与薪酬项', () => {
    const all = paths(menuFromRoutes(routes, has(['chat:use']), false));
    expect(all).toEqual(['/platform/team']);
  });

  it('工作偏好受开关控制', () => {
    expect(paths(menuFromRoutes(routes, has(['profile:read']), false))).not.toContain('/platform/profile');
    expect(paths(menuFromRoutes(routes, has(['profile:read']), true))).toContain('/platform/profile');
  });
});

describe('审计工作台归属', () => {
  it('按动作与资源归组', () => {
    expect(workspaceOfAction('kb.tag.update', 'kb_tag')).toBe('platform');
    expect(workspaceOfAction('payroll.send', 'payroll_run')).toBe('payroll');
    expect(workspaceOfAction('user.create', 'user')).toBe('governance');
    expect(workspaceOfAction('post /api/chat/turns', 'endpoint')).toBe('common');
  });
});
