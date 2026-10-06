import { expect, test } from '@playwright/test';
import { navLink, signIn } from './support';

// Named so it runs first (files run in name order): while nobody follows the game, its page has no patch notes yet.
test("a game's own page: not tracked until somebody follows it, then its history appears", async ({ page }) => {
  await signIn(page);

  // reach the page from search, by the game's name
  await navLink(page, 'Discover').click();
  await page.getByRole('searchbox', { name: 'Search games' }).fill('dragonwilds');
  await page.getByRole('link', { name: 'RuneScape: Dragonwilds' }).click();
  await expect(page).toHaveURL(/\/games\/\d+$/);
  await expect(page.getByRole('heading', { level: 1, name: 'RuneScape: Dragonwilds' })).toBeVisible();

  // nobody follows it yet, so nothing was ever fetched, and the page says so instead of looking broken
  await expect(page.locator('.game-facts').getByText(/Nobody follows this game yet/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Not tracked yet' })).toBeVisible();

  // follow it from here: the count updates and the patch notes arrive on their own
  await page.getByRole('button', { name: /^watch runescape/i }).click();
  await expect(page.getByText(/1 person follows this game/)).toBeVisible();
  const patch = page.getByRole('link', { name: /1\.0\.0\.6 is now Live!/ });
  await expect(patch).toBeVisible({ timeout: 45_000 });
  await expect(page.getByText('Fixed a crash when opening the e2e map.')).toBeVisible();
  await expect(page.getByText('Our review roundup')).toHaveCount(0); // press coverage is not a patch note
  await expect(page.getByText(/latest patch notes/)).toBeVisible();

  // a reload of this address (the server has to serve the app at /games/<id>) keeps working
  await page.reload();
  await expect(page.getByRole('heading', { level: 1, name: 'RuneScape: Dragonwilds' })).toBeVisible();
  await expect(patch).toBeVisible();

  // from the feed, a card's game name leads back here
  await navLink(page, 'Feed').click();
  await expect(page.getByRole('link', { name: /1\.0\.0\.6 is now Live!/ })).toBeVisible({ timeout: 15_000 });
  await page.getByRole('link', { name: 'RuneScape: Dragonwilds', exact: true }).click();
  await expect(page).toHaveURL(/\/games\/\d+$/);

  // leave things as found for the tests after this one
  await page.getByRole('button', { name: /stop watching runescape/i }).click();
  await expect(page.locator('.game-facts').getByText(/Nobody follows this game yet/)).toBeVisible();
});

test('a game that is not in the catalog says so', async ({ page }) => {
  await signIn(page);

  await page.goto('/games/999999999');

  await expect(page.getByRole('heading', { name: 'Game not found' })).toBeVisible();
  await page.getByRole('link', { name: 'Find games' }).click();
  await expect(page).toHaveURL(/\/discover$/);
});
