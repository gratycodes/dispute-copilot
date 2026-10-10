import { defineConfig, devices } from '@playwright/test';

const port = 4174;

// e2e tests run against the built console with the API mocked from captured responses
// (e2e/fixtures). The screenshot spec instead needs the real stack; see README.
export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.CONSOLE_URL ?? `http://localhost:${port}/`,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1360, height: 900 } } }],
  webServer: process.env.CONSOLE_URL
    ? undefined
    : {
        command: `npm run build && npx vite preview --port ${port} --strictPort`,
        url: `http://localhost:${port}/`,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      },
});
