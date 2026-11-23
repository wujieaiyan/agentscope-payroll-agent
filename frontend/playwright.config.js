import { defineConfig } from '@playwright/test';

// 浏览器统一装在 D 盘，避免占用系统盘；已显式设置时尊重环境变量。
if (!process.env.PLAYWRIGHT_BROWSERS_PATH) {
  process.env.PLAYWRIGHT_BROWSERS_PATH = 'D:/playwright-browsers';
}

export default defineConfig({
  testDir: 'tests/e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    // 允许用本机已装浏览器（如 msedge）跑冒烟，避免 Chromium 下载受阻时无法验证。
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}),
  },
  webServer: {
    command: 'npm run dev -- --port 4173 --strictPort --host 127.0.0.1',
    url: 'http://127.0.0.1:4173/login',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
