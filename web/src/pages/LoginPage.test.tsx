import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, renderApp, stubApi } from '../test-utils';
import { LoginPage } from './LoginPage';

describe('LoginPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  const signedOut = () => stubApi({ 'GET /api/me': () => jsonResponse({}, 401) });

  it('offers to sign in through Steam and shows no error at first', async () => {
    signedOut();

    renderApp(<LoginPage />, '/login');

    expect(await screen.findByRole('link', { name: /sign in through steam/i })).toHaveAttribute(
      'href',
      '/api/auth/steam/login?next=%2Ffeed',
    );
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it("says Steam sign-in didn't complete when it failed", async () => {
    signedOut();

    renderApp(<LoginPage />, '/login?error=steam');

    expect(await screen.findByRole('alert')).toHaveTextContent(/didn't complete/i);
  });

  it('explains a rate limit as "too many attempts, wait a moment", not as a failure of Steam', async () => {
    signedOut();

    renderApp(<LoginPage />, '/login?error=rate-limited');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/too many sign-in attempts/i);
    expect(alert).toHaveTextContent(/wait a minute/i);
    expect(alert).not.toHaveTextContent(/didn't complete/i);
  });
});
