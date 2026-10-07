import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { navLink, signIn } from '../tests/support';

// Takes the pictures in docs/screenshots/ from the real catalog and real patch notes (see start-app-with-real-catalog.mjs).
// Run with: npm run screenshots   (from e2e/, after building the jar)

const OUT = resolve(process.cwd(), '..', 'docs', 'screenshots');

// For the feed: a mix of Steam games and games that are not on Steam. Counter-Strike 2 is left out of this list on purpose:
// it patches so often that it would fill the whole first screen of the feed with itself. It is followed afterwards, for its page.
const FEED_GAMES = ['Dota 2', 'Warframe', 'Rust', 'ELDEN RING', 'Minecraft', 'League of Legends', 'VALORANT'];
const PAGE_GAME = 'Counter-Strike 2';
const NON_STEAM_PAGE_GAME = 'Minecraft';

const exactly = (name: string) => new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`, 'i');

async function follow(page: Page, name: string) {
  await navLink(page, 'Discover').click();
  await page.getByRole('searchbox', { name: 'Search games' }).fill(name);
  const row = page.getByRole('listitem').filter({ has: page.getByRole('link', { name: exactly(name) }) }).first();
  await expect(row, `"${name}" should be in the catalog`).toBeVisible();
  await row.getByRole('button', { name: /^watch /i }).click();
  await expect(row.getByRole('button', { name: /^stop watching /i })).toBeVisible();
}

/** Covers and icons come from Steam's CDN: wait until every picture on the page has really loaded. */
async function picturesLoaded(page: Page) {
  await page.waitForLoadState('networkidle');
  await page.waitForFunction(() => [...document.images].every((image) => image.complete && image.naturalWidth > 0), undefined, { timeout: 30_000 });
}

test('take the README screenshots', async ({ page, browser }) => {
  mkdirSync(OUT, { recursive: true });
  await signIn(page);

  for (const name of FEED_GAMES) await follow(page, name);

  // their real patch notes are fetched in the background: wait until the feed has a good handful
  await expect
    .poll(async () => (await (await page.request.get('/api/feed?size=1')).json()).totalItems, { timeout: 180_000, intervals: [2_000] })
    .toBeGreaterThanOrEqual(12);

  // 1. the feed: the Filters button is closed, so the notes start on the first screen
  await page.setViewportSize({ width: 1100, height: 1500 });
  await navLink(page, 'Feed').click();
  await expect(page.getByRole('heading', { name: 'Your patch notes' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Filters', exact: true })).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('article').first()).toBeVisible();
  await picturesLoaded(page);
  await page.screenshot({ path: `${OUT}/feed.png` });

  // 2. the feed on a phone
  const phone = await browser.newContext({
    storageState: await page.context().storageState(),
    viewport: { width: 390, height: 1250 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    colorScheme: 'light',
  });
  const small = await phone.newPage();
  await small.goto('/feed');
  await expect(small.locator('article').first()).toBeVisible();
  await picturesLoaded(small);
  await small.screenshot({ path: `${OUT}/feed-phone.png` });
  await phone.close();

  // 3. finding games: the most popular first, Steam covers next to the games that are not on Steam
  await navLink(page, 'Discover').click();
  await page.setViewportSize({ width: 1100, height: 1300 });
  await expect(page.getByText(/most popular first/)).toBeVisible();
  await expect(page.getByRole('link', { name: 'Roblox', exact: true })).toBeVisible();
  await picturesLoaded(page);
  await page.screenshot({ path: `${OUT}/discover.png` });

  // 4. the Filters panel open, with a genre and an age rating chosen
  const resultCount = page.locator('.result-count');
  const totalBefore = ((await resultCount.innerText()).match(/^[\d,]+/) ?? [''])[0];
  await page.getByRole('button', { name: 'Filters', exact: true }).click();
  await page.getByRole('button', { name: 'RPG', exact: true }).click();
  await page.getByRole('button', { name: 'Teen', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Filters, 2 active' })).toBeVisible();
  // the old list stays on screen, already captioned "fit your filters", until the new one arrives: wait for the NUMBER to change
  await expect(resultCount).toContainText('fit your filters');
  await expect(resultCount).not.toHaveText(new RegExp(`^${totalBefore}\\b`));
  await picturesLoaded(page);
  await page.screenshot({ path: `${OUT}/filters.png` });

  // 5. a game's own page: cover, followers and its history
  await follow(page, PAGE_GAME);
  await page.setViewportSize({ width: 1100, height: 1500 });
  await navLink(page, 'Watchlist').click();
  await page.getByRole('link', { name: exactly(PAGE_GAME) }).click();
  await expect(page.getByRole('heading', { level: 1, name: PAGE_GAME })).toBeVisible();
  await expect(page.locator('article').first()).toBeVisible();
  await picturesLoaded(page);
  await page.screenshot({ path: `${OUT}/game.png` });

  // 6. the page of a game that is not on Steam: the publisher's real notes
  await navLink(page, 'Watchlist').click();
  await page.getByRole('link', { name: exactly(NON_STEAM_PAGE_GAME) }).click();
  await expect(page.getByRole('heading', { level: 1, name: NON_STEAM_PAGE_GAME })).toBeVisible();
  await expect(page.locator('article').first()).toBeVisible();
  await picturesLoaded(page);
  await page.screenshot({ path: `${OUT}/game-minecraft.png` });
});
