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

  // 1. the feed (tall enough to get past the filter chips and show several notes)
  await page.setViewportSize({ width: 1100, height: 1500 });
  await navLink(page, 'Feed').click();
  await expect(page.getByRole('heading', { name: 'Your patch notes' })).toBeVisible();
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

  // 3. finding games: search with covers, ratings and genres
  await navLink(page, 'Discover').click();
  await page.setViewportSize({ width: 1100, height: 1300 });
  await page.getByRole('searchbox', { name: 'Search games' }).fill('witcher');
  // wait for the results OF THIS SEARCH: the unfiltered list is already on screen and must not be photographed by mistake
  await expect(page.getByText(/matching "witcher"/)).toBeVisible();
  await expect(page.getByRole('listitem').first()).toContainText(/witcher/i);
  await picturesLoaded(page);
  await page.screenshot({ path: `${OUT}/discover.png` });

  // 4. a game's own page: cover, followers and its history
  await follow(page, PAGE_GAME);
  await page.setViewportSize({ width: 1100, height: 1500 });
  await navLink(page, 'Watchlist').click();
  await page.getByRole('link', { name: exactly(PAGE_GAME) }).click();
  await expect(page.getByRole('heading', { level: 1, name: PAGE_GAME })).toBeVisible();
  await expect(page.locator('article').first()).toBeVisible();
  await picturesLoaded(page);
  await page.screenshot({ path: `${OUT}/game.png` });
});
