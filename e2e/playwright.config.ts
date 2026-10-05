import { defineConfig, devices } from '@playwright/test';

const APP = `http://localhost:${process.env.E2E_APP_PORT ?? 8089}`;
const STEAM = `http://localhost:${process.env.FAKE_STEAM_PORT ?? 9099}`;

export default defineConfig({
  testDir: './tests',
  // One browser at a time: the tests share one app, one database and one sign-in allowance, and the last file
  // (the rate limit) deliberately uses it up.
  workers: 1,
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: APP,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    { command: 'node fake-steam.mjs', url: `${STEAM}/__health`, reuseExistingServer: !process.env.CI },
    // the app needs the fake Steam only once somebody signs in or watches a game, so the two may start together
    { command: 'node start-app.mjs', url: `${APP}/actuator/health`, timeout: 180_000, reuseExistingServer: !process.env.CI },
  ],
});
