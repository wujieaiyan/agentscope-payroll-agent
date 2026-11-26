import { test, expect } from '@playwright/test';
import { createSkillsMock, ME } from './support/skillsMock';

const ok = (data) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ status: 'OK', data }),
});

const apiCalls = (url) => url.pathname.startsWith('/api/');

async function mockApp(page) {
  const skills = createSkillsMock();
  await page.route(apiCalls, (route) => {
    const url = new URL(route.request().url());
    // 技能接口交给统一 mock，保证 shell 的「db 源」chip 与技能页数据一致。
    if (url.pathname.startsWith('/api/admin/skills')) return skills.handle(route);
    if (url.pathname === '/api/auth/me') return route.fulfill(ok(ME));
    if (url.pathname.startsWith('/api/admin/agents')) return route.fulfill(ok({ agents: [] }));
    if (url.pathname.startsWith('/api/kb/series')) {
      return route.fulfill(ok({ items: [], total: 0, page: 1, size: 20 }));
    }
    return route.fulfill(ok({}));
  });
  return skills;
}

const ROUTES = [
  ['chat', '/chat'],
  ['agents', '/platform/agents'],
  ['flows', '/platform/flows'],
  ['skills-list', '/platform/skills'],
  ['skills-overview', '/platform/skills/poetry-skills/overview'],
  ['skills-body', '/platform/skills/poetry-skills/body'],
  ['skills-files', '/platform/skills/poetry-skills/files'],
  ['skills-data', '/platform/skills/poetry-skills/data'],
  ['skills-versions', '/platform/skills/poetry-skills/versions'],
  ['skills-bindings', '/platform/skills/poetry-skills/bindings'],
  ['skills-editor', '/platform/skills/poetry-skills/edit'],
  ['skills-create', '/platform/skills/new'],
  ['skills-import', '/platform/skills/import'],
  ['team', '/platform/team'],
  ['kb-list', '/platform/kb'],
  ['kb-create', '/platform/kb/create'],
  ['kb-detail', '/platform/kb/1'],
  ['employees', '/payroll/employees'],
  ['reviews', '/payroll/reviews'],
  ['attendance', '/payroll/attendance'],
  ['runs', '/payroll/runs'],
  ['tasks', '/payroll/tasks'],
  ['policies', '/payroll/policies'],
  ['models', '/platform/models'],
  ['users', '/platform/users'],
  ['audit', '/platform/audit'],
  ['usage', '/platform/usage'],
  ['profile', '/platform/profile'],
];

test.describe('视觉回归', () => {
  test.use({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });

  test('登录页基线', async ({ page }) => {
    await page.clock.setFixedTime(new Date('2026-11-25T12:00:00'));
    await page.route(apiCalls, (route) =>
      route.fulfill(
        ok({ user: null, auth_enabled: true, profile_enabled: false, must_change_password: false })
      )
    );
    await page.goto('/login');
    await expect(page).toHaveURL(/\/login/);
    await expect(page.locator('form')).toBeVisible();
    await expect(page).toHaveScreenshot('login.png', { animations: 'disabled', maxDiffPixelRatio: 0.001 });
  });

  test('全部路由截图基线', async ({ page }) => {
    // 相对时间（如「1 天前」）必须固定，否则基线每天都会变。
    await page.clock.setFixedTime(new Date('2026-11-25T12:00:00'));
    await mockApp(page);
    for (const [name, path] of ROUTES) {
      await page.goto(path);
      await expect(page.locator('.shell')).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expect(page).toHaveScreenshot(`${name}.png`, {
        animations: 'disabled',
        maxDiffPixelRatio: 0.001,
      });
    }
  });

  test('技能交互态截图基线', async ({ page }) => {
    await page.clock.setFixedTime(new Date('2026-11-25T12:00:00'));
    await mockApp(page);

    await page.goto('/platform/skills/import');
    await expect(page.getByRole('heading', { name: '检查导入内容' })).toBeVisible();
    await page.getByPlaceholder('https://github.com/org/skill.git').fill(
      'https://github.com/org/poetry-skills.git'
    );
    await page.getByPlaceholder('留空按 SKILL.md 生成').fill('poetry-skills');
    await page.getByRole('button', { name: '下一步：预检' }).click();
    await expect(page.locator('.warn-bar')).toBeVisible();
    await expect(page).toHaveScreenshot('skills-import-preview.png', {
      animations: 'disabled',
      maxDiffPixelRatio: 0.001,
    });

    await page.goto('/platform/skills/poetry-skills/edit');
    await expect(page.getByRole('heading', { name: '编辑正文' })).toBeVisible();
    await page.getByRole('tab', { name: '预览' }).click();
    await expect(page.locator('.md-preview')).toBeVisible();
    await expect(page).toHaveScreenshot('skills-editor-preview.png', {
      animations: 'disabled',
      maxDiffPixelRatio: 0.001,
    });
  });

  test('1280/1440/1920 三档无横向滚动', async ({ page }) => {
    // 3 档 × 28 条路由，每条要等 networkidle，60s 默认超时不够。
    test.setTimeout(180_000);
    await mockApp(page);
    for (const width of [1280, 1440, 1920]) {
      await page.setViewportSize({ width, height: 900 });
      for (const [, path] of ROUTES) {
        await page.goto(path);
        await expect(page.locator('.shell')).toBeVisible();
        await page.waitForLoadState('networkidle');
        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth - window.innerWidth
        );
        expect(overflow, `${path} @ ${width}px`).toBeLessThanOrEqual(1);
      }
    }
  });
});
