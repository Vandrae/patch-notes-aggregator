import { expect, test } from '@playwright/test';

// Named zz- so it runs last: it uses up this machine's sign-in allowance (see start-app.mjs), which refills slowly.
test('starting sign-in too often is refused, and the page says to wait', async ({ page, request }) => {
  let refusedAfter = 0;
  for (let attempt = 1; attempt <= 60 && refusedAfter === 0; attempt++) {
    const response = await request.get('/api/auth/steam/login', { maxRedirects: 0 });
    expect(response.status()).toBe(302);
    if (response.headers()['location']?.endsWith('/login?error=rate-limited')) {
      refusedAfter = attempt;
      expect(Number(response.headers()['retry-after'])).toBeGreaterThan(0);
    }
  }
  expect(refusedAfter).toBeGreaterThan(1); // the first ones went through

  await page.goto('/login');
  await page.getByRole('link', { name: /sign in through steam/i }).click();

  await expect(page).toHaveURL(/\/login\?error=rate-limited/);
  await expect(page.getByRole('alert')).toContainText('Too many sign-in attempts');
});
