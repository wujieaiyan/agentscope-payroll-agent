import { test, expect } from '@playwright/test';

const unauthenticated = {
  status: 401,
  contentType: 'application/json',
  body: JSON.stringify({ status: 'ERROR', message: '未登录或会话已过期' }),
};

test('未登录访问受保护页面跳转登录页', async ({ page }) => {
  await page.route('**/api/auth/me', (route) => route.fulfill(unauthenticated));

  await page.goto('/chat');

  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByRole('heading', { name: '协同智能体' })).toBeVisible();
  await expect(page.getByLabel('用户名')).toBeVisible();
  await expect(page.getByLabel('密码')).toBeVisible();
});

test('登录失败展示后端错误信息', async ({ page }) => {
  await page.route('**/api/auth/me', (route) => route.fulfill(unauthenticated));
  await page.route('**/api/auth/login', (route) =>
    route.fulfill({
      status: 400,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'ERROR', message: '用户名或密码错误' }),
    })
  );

  await page.goto('/login');
  await page.getByLabel('用户名').fill('admin');
  await page.getByLabel('密码').fill('wrong-password');
  await page.getByRole('button', { name: '登录' }).click();

  await expect(page.locator('.login-error')).toHaveText('用户名或密码错误');
  await expect(page.getByLabel('密码')).toHaveValue('');
});
