import { expect, test } from '@playwright/test';
import { STEAM, newSteamId, navLink } from './support';

// Guest mode: somebody who has not signed in can look around (the catalog and a game's page) but not follow anything.
// Roblox comes from the test app's own list of games that are not on Steam, so no Steam key is needed.

test('a guest can browse from the sign-in page, and is invited to sign in rather than shown a broken Watch button', async ({ page }) => {
  await page.goto('/login');
  await page.getByRole('link', { name: 'Browse as a guest' }).click();

  await expect(page).toHaveURL(/\/discover$/);
  await expect(page.getByRole('heading', { name: 'Discover games' })).toBeVisible();
  // only the tab a guest can use, and a way in
  await expect(navLink(page, 'Discover')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Feed' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Sign in with Steam', exact: true })).toBeVisible();

  await page.getByRole('searchbox', { name: 'Search games' }).fill('roblox');
  const tile = page.locator('li.tile').filter({ hasText: 'Test description for Roblox.' });
  await expect(tile).toBeVisible();
  await expect(tile.getByRole('link', { name: 'Sign in with Steam to watch Roblox' })).toBeVisible();
  await expect(tile.getByRole('button')).toHaveCount(0);

  // a game's own page is open to a guest too
  await tile.getByRole('link', { name: 'Roblox', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Roblox' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Sign in with Steam to watch Roblox' })).toBeVisible();
});

test('a guest who asks for the feed is sent to sign in, and the server only lets a guest read the public pages', async ({ page }) => {
  await page.goto('/feed');
  await expect(page.getByRole('heading', { name: 'Never miss a patch' })).toBeVisible();
  await expect(page).toHaveURL(/\/login\?next=%2Ffeed/);

  expect((await page.request.get('/api/games?q=roblox')).status()).toBe(200);
  expect((await page.request.get('/api/catalog/filters')).status()).toBe(200);
  expect((await page.request.get('/api/feed')).status()).toBe(401);
  expect((await page.request.get('/api/watchlist')).status()).toBe(401);
  expect((await page.request.get('/api/me')).status()).toBe(401);
  // nothing can be changed: without the anti-forgery header it is refused, and with it a guest is still not a user
  expect((await page.request.put('/api/watchlist/1')).status()).toBe(403);
});

test('signing in from where a guest was browsing brings them back to that page, now with the full navigation', async ({ page }) => {
  await fetch(`${STEAM}/__user`, { method: 'POST', body: newSteamId() });
  await page.goto('/discover');
  await page.getByRole('link', { name: 'Sign in with Steam', exact: true }).click();

  await expect(page).toHaveURL(/\/discover$/);
  await expect(page.getByRole('link', { name: /^Account:/ })).toBeVisible();
  await expect(navLink(page, 'Feed')).toBeVisible();
  await expect(navLink(page, 'Watchlist')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Sign in with Steam', exact: true })).toHaveCount(0);
});
