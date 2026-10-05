import { expect, test } from '@playwright/test';
import { fakeSteamCalls, signIn } from './support';

const PATCH_TITLE = /1\.0\.0\.6 is now Live!/;

test('sign in, find a game, follow it, read its patch notes, then stop following', async ({ page }) => {
  // 1. a stranger is sent to sign in, and comes back to where they were going
  await page.goto('/feed');
  await expect(page).toHaveURL(/\/login\?next=%2Ffeed/);
  await expect(page.getByRole('heading', { name: 'Never miss a patch' })).toBeVisible();

  // 2. signing in goes out to Steam and back, and Steam was really asked to vouch for the response
  await signIn(page, undefined, '/login?next=%2Ffeed');
  await expect(page).toHaveURL(/\/feed$/);
  await expect(page.getByRole('heading', { name: 'Follow a game to get started' })).toBeVisible();
  expect((await fakeSteamCalls()).some((call) => call.kind === 'check-authentication' && call.valid === true)).toBe(true);

  // 3. find the game by name
  await page.getByRole('link', { name: 'Discover' }).click();
  await page.getByRole('searchbox', { name: 'Search games' }).fill('dragonwilds');
  const row = page.getByRole('listitem').filter({ hasText: 'RuneScape: Dragonwilds' });
  await expect(row).toBeVisible();

  // 4. follow it: the button flips at once and the choice sticks
  await row.getByRole('button', { name: /^watch runescape/i }).click();
  await expect(row.getByRole('button', { name: /stop watching/i })).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('link', { name: 'Watchlist' }).click();
  await expect(page.getByRole('listitem').filter({ hasText: 'RuneScape: Dragonwilds' })).toBeVisible();

  // 5. its patch notes arrive (fetched in the background after following) and press coverage does not
  await page.getByRole('link', { name: 'Feed' }).click();
  await expect
    .poll(
      async () => {
        await page.reload();
        return page.getByRole('link', { name: PATCH_TITLE }).count();
      },
      { timeout: 45_000, intervals: [1_000] },
    )
    .toBe(1);
  await expect(page.getByText('Fixed a crash when opening the e2e map.')).toBeVisible();
  await expect(page.getByText('Our review roundup')).toHaveCount(0);
  const link = page.getByRole('link', { name: PATCH_TITLE });
  await expect(link).toHaveAttribute('href', /store\.steampowered\.com\/news\//);
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(link).toHaveAttribute('rel', /noopener/);

  // 6. the session survives a reload
  await page.reload();
  await expect(page.getByRole('link', { name: PATCH_TITLE })).toBeVisible();

  // 7. unfollowing empties the feed again
  await page.getByRole('link', { name: 'Watchlist' }).click();
  await page.getByRole('button', { name: /stop watching runescape/i }).click();
  await page.getByRole('link', { name: 'Feed' }).click();
  await expect(page.getByRole('heading', { name: 'Follow a game to get started' })).toBeVisible();
  await expect(page.getByRole('link', { name: PATCH_TITLE })).toHaveCount(0);
});

test('signing out ends the session, and the app goes back to asking for sign-in', async ({ page }) => {
  await signIn(page);

  await page.getByRole('link', { name: /^Account/ }).click();
  await page.getByRole('button', { name: 'Sign out' }).click();

  await expect(page).toHaveURL(/\/login/);
  await page.goto('/feed');
  await expect(page).toHaveURL(/\/login\?next=%2Ffeed/);
});

test('deleting the account removes it, and signing in again starts from nothing', async ({ page }) => {
  const steamId = await signIn(page);
  await page.getByRole('link', { name: 'Discover' }).click();
  await page.getByRole('searchbox', { name: 'Search games' }).fill('dragonwilds');
  const row = page.getByRole('listitem').filter({ hasText: 'RuneScape: Dragonwilds' });
  await row.getByRole('button', { name: /^watch runescape/i }).click();
  await expect(row.getByRole('button', { name: /stop watching/i })).toBeVisible();

  await page.getByRole('link', { name: /^Account/ }).click();
  await page.getByRole('button', { name: /delete my account/i }).click();
  await page.getByRole('button', { name: /yes, delete everything/i }).click();
  await expect(page).toHaveURL(/\/login/);

  // the same Steam user signing in again gets a brand-new, empty account
  await signIn(page, steamId);
  await page.getByRole('link', { name: 'Watchlist' }).click();
  await expect(page.getByRole('heading', { name: 'Your watchlist' })).toBeVisible();
  await expect(page.getByRole('listitem').filter({ hasText: 'RuneScape: Dragonwilds' })).toHaveCount(0);
});
