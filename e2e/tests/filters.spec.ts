import { expect, test } from '@playwright/test';
import { navLink, signIn } from './support';

// The feed's genre, age and rating filters sit behind a "Filters" button, closed by default, so the patch notes are on the
// first screen. Roblox has no genres (they come from Steam's store, and this suite has no Steam key), so choosing a genre leaves nothing that matches:
// that is the state this test uses to see the filters change what is shown. (Not Dragonwilds: following it stores its notes, and the
// game-page test needs it never to have been fetched.)

test('the feed filters are behind a Filters button that counts what is on, and Escape closes it', async ({ page }) => {
  await signIn(page);
  await navLink(page, 'Discover').click();
  await page.getByRole('searchbox', { name: 'Search games' }).fill('roblox');
  const row = page.getByRole('listitem').filter({ hasText: 'Test description for Roblox.' });
  await row.getByRole('button', { name: /^watch roblox/i }).click();
  await expect(row.getByRole('button', { name: /stop watching/i })).toBeVisible();

  await navLink(page, 'Feed').click();

  // closed: only the button, no wall of chips
  const filters = page.getByRole('button', { name: 'Filters', exact: true });
  await expect(filters).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('group', { name: 'Filter by genre' })).toBeHidden();

  // open it and choose a genre: the count appears, and (nothing here has genres) the feed says nothing matches
  await filters.click();
  await expect(page.getByRole('group', { name: 'Filter by genre' })).toBeVisible();
  await page.getByRole('button', { name: 'RPG', exact: true }).click();
  const withCount = page.getByRole('button', { name: 'Filters, 1 active' });
  await expect(withCount).toBeVisible();
  await expect(page).toHaveURL(/genre=RPG/);
  await expect(page.getByRole('heading', { name: 'None of your games match' })).toBeVisible();

  // Escape closes the panel and returns focus to the button; the choice stays, shown as a chip that removes it
  await page.keyboard.press('Escape');
  await expect(withCount).toHaveAttribute('aria-expanded', 'false');
  await expect(withCount).toBeFocused();
  const chip = page.getByRole('button', { name: 'Remove filter RPG' });
  await expect(chip).toBeVisible();

  // removing the chip clears the filter and the feed comes back to its normal state
  await chip.click();
  await expect(page).not.toHaveURL(/genre=/);
  await expect(page.getByRole('button', { name: 'Filters', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'None of your games match' })).toHaveCount(0);

  // leave things as found for the tests after this one
  await navLink(page, 'Watchlist').click();
  await page.getByRole('button', { name: /stop watching roblox/i }).click();
});
