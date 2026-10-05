import { expect, test } from '@playwright/test';
import { signIn } from './support';

test('the privacy page can be read without signing in', async ({ page }) => {
  await page.goto('/privacy');

  await expect(page.getByRole('heading', { name: 'Privacy', level: 1 })).toBeVisible();
  await expect(page).toHaveURL(/\/privacy$/);
});

test('a sign-in response that did not come from this login attempt is refused', async ({ page }) => {
  // arrives with no sign-in having been started in this browser: no state cookie, and nothing Steam issued
  await page.goto('/api/auth/steam/callback?openid.ns=http://specs.openid.net/auth/2.0&openid.mode=id_res');

  await expect(page).toHaveURL(/\/login\?error=steam/);
  await expect(page.getByRole('alert')).toContainText("didn't complete");
  const me = await page.request.get('/api/me');
  expect(me.status()).toBe(401);
});

test('a genuine sign-in response cannot be replayed after signing out', async ({ page }) => {
  const callbacks: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/auth/steam/callback')) callbacks.push(request.url());
  });
  await signIn(page);
  expect(callbacks).toHaveLength(1);
  await page.getByRole('link', { name: /^Account/ }).click();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login/);

  await page.goto(callbacks[0]); // exactly the address Steam sent the browser to

  await expect(page).toHaveURL(/\/login\?error=steam/);
  expect((await page.request.get('/api/me')).status()).toBe(401);
});

test('a sign-in that asks to land on another site stays on this one', async ({ page }) => {
  await signIn(page, undefined, '/login?next=https%3A%2F%2Fevil.test%2Fphish');

  await expect(page).toHaveURL(/^http:\/\/localhost:\d+\/feed$/);
});

test('API calls need a session, and writes need the anti-forgery header', async ({ page }) => {
  expect((await page.request.get('/api/watchlist')).status()).toBe(401);

  await signIn(page);
  expect((await page.request.get('/api/watchlist')).status()).toBe(200);
  // the browser's session cookie is sent, but no X-XSRF-TOKEN: refused, as a forged cross-site request would be
  expect((await page.request.put('/api/watchlist/1')).status()).toBe(403);
});
