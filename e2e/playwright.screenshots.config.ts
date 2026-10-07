import { defineConfig, devices } from '@playwright/test';

// Not part of the test suite: `npm run screenshots` regenerates the pictures in docs/screenshots/ from the real catalog.
// See screenshots/start-app-with-real-catalog.mjs for what it needs.
const APP = `http://localhost:${process.env.SCREENSHOT_APP_PORT ?? 8090}`;
const STEAM = `http://localhost:${process.env.FAKE_STEAM_PORT ?? 9099}`;

export default defineConfig({
  testDir: './screenshots',
  workers: 1,
  timeout: 300_000,
  reporter: 'list',
  use: {
    baseURL: APP,
    colorScheme: 'light',
    viewport: { width: 1100, height: 900 },
    deviceScaleFactor: 2, // crisp on high-density screens
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1100, height: 900 }, deviceScaleFactor: 2 } }],
  webServer: [
    { command: 'node fake-steam.mjs', url: `${STEAM}/__health`, reuseExistingServer: false },
    { command: 'node screenshots/start-app-with-real-catalog.mjs', url: `${APP}/actuator/health`, timeout: 240_000, reuseExistingServer: false },
  ],
});
