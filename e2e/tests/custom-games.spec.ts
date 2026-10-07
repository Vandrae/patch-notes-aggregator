import { expect, test } from '@playwright/test';
import { navLink, signIn } from './support';

// Games that are not on Steam (Roblox from an RSS feed, Minecraft from a help centre), served by the fake publishers.
// Named so it runs first: nobody follows these games yet, so their pages start out "not tracked".

test('a game that is not on Steam: found by name, followed, and its notes read from the publisher', async ({ page }) => {
  await signIn(page);

  await navLink(page, 'Discover').click();
  await page.getByRole('searchbox', { name: 'Search games' }).fill('roblox');
  const row = page.getByRole('listitem').filter({ hasText: 'Test description for Roblox.' });
  await expect(row).toBeVisible();
  // not on Steam, so no Steam link: the line says where the notes come from instead
  await expect(row.getByText('Patch notes from the publisher')).toBeVisible();
  await expect(row.getByRole('link', { name: /view on steam/i })).toHaveCount(0);

  // its logo, from this site, in the list: a real picture that loaded, not the lettered tile
  const thumb = row.locator('img.game-thumb');
  await expect(thumb).toHaveAttribute('src', '/art/roblox-cover.png');
  expect(await thumb.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBe(true);

  await row.getByRole('link', { name: 'Roblox', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Roblox' })).toBeVisible();
  const cover = page.locator('img.game-cover');
  await expect(cover).toHaveAttribute('src', '/art/roblox-cover.png');
  expect(await cover.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBe(true);
  await expect(page.getByRole('heading', { name: 'Not tracked yet' })).toBeVisible();

  await page.getByRole('button', { name: /^watch roblox/i }).click();

  const newest = page.getByRole('link', { name: /Release Notes for 900/ });
  await expect(newest).toBeVisible({ timeout: 45_000 });
  await expect(page.getByRole('link', { name: /Release Notes for 899/ })).toBeVisible();
  // the link goes to the publisher's own post; the summary is plain text, never markup
  await expect(newest).toHaveAttribute('href', 'https://devforum.roblox.com/t/release-notes-for-900/900');
  await expect(newest).toHaveAttribute('rel', /noopener/);
  await expect(page.getByText('Studio is faster to open').first()).toBeVisible();
  expect(await page.locator('.article-summary').first().innerHTML()).not.toContain('<'); // plain text, no markup inside

  await page.getByRole('button', { name: /stop watching roblox/i }).click();
});

test('two non-Steam games share one feed with Steam games, newest first', async ({ page }) => {
  await signIn(page);

  for (const game of ['Roblox', 'Minecraft']) {
    await navLink(page, 'Discover').click();
    await page.getByRole('searchbox', { name: 'Search games' }).fill(game.toLowerCase());
    await page.getByRole('listitem').filter({ hasText: `Test description for ${game}.` }).getByRole('button', { name: new RegExp(`^watch ${game}`, 'i') }).click();
    await expect(page.getByRole('button', { name: new RegExp(`stop watching ${game}`, 'i') })).toBeVisible();
  }

  await navLink(page, 'Feed').click();
  await expect
    .poll(async () => {
      await page.reload();
      return page.getByRole('article').count();
    }, { timeout: 45_000, intervals: [1_000] })
    .toBeGreaterThanOrEqual(3);

  const titles = await page.getByRole('article').getByRole('heading', { level: 3 }).allTextContents();
  // Roblox 900 is 3 hours old, Minecraft 99.1 is 5, Roblox 899 is 200, Minecraft 99.0 is 90: merged by date, not grouped by game
  const order = ['Release Notes for 900', 'Minecraft Java Edition - 99.1', 'Minecraft: Bedrock Edition 99.0 Hotfix Changelog', 'Release Notes for 899'];
  const positions = order.map((title) => titles.findIndex((t) => t.includes(title)));
  expect(positions.every((p) => p >= 0)).toBe(true);
  expect([...positions].sort((a, b) => a - b)).toEqual(positions);

  // each card names its game and the link goes to the publisher
  await expect(page.getByRole('link', { name: /Minecraft Java Edition - 99\.1/ })).toHaveAttribute('href', 'https://feedback.minecraft.net/hc/en-us/articles/5001');

  // leave things as found
  for (const game of ['Roblox', 'Minecraft']) {
    await navLink(page, 'Watchlist').click();
    await page.getByRole('button', { name: new RegExp(`stop watching ${game}`, 'i') }).click();
  }
});
