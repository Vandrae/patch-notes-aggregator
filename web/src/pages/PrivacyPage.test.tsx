import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from '../App';
import { jsonResponse, renderApp, stubApi } from '../test-utils';

describe('PrivacyPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('can be read without signing in, and says what is kept and how to delete it', async () => {
    const { calls } = stubApi({ 'GET /api/me': () => jsonResponse({}, 401) });

    renderApp(<App />, '/privacy');

    expect(await screen.findByRole('heading', { name: 'Privacy', level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /what we keep/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Account' })).toHaveAttribute('href', '/account');
    // not bounced to the sign-in page
    expect(calls.every((c) => !c.url.includes('/login'))).toBe(true);
    expect(screen.queryByRole('button', { name: /sign in/i })).toBeNull();
  });

  it('is linked from the sign-in page', async () => {
    stubApi({ 'GET /api/me': () => jsonResponse({}, 401) });

    renderApp(<App />, '/login');

    expect(await screen.findByRole('link', { name: 'Privacy' })).toHaveAttribute('href', '/privacy');
  });

  it('opens Steam from the footer in a new tab, without nofollow, and links to privacy', async () => {
    stubApi({
      'GET /api/me': () => jsonResponse({ id: 1, steamId: 1, personaName: 'Gabe', avatarUrl: null }),
      'GET /api/feed': () => jsonResponse({ items: [], page: 0, size: 20, totalPages: 0, totalItems: 0, emptyState: null }),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/catalog/filters': () => jsonResponse({ genres: [], ratings: [], ageRatings: [] }),
    });

    renderApp(<App />, '/account');

    const footer = await screen.findByRole('contentinfo');
    const steam = footer.querySelector('a[href="https://store.steampowered.com/"]');
    expect(steam).not.toBeNull();
    expect(steam).toHaveAttribute('target', '_blank');
    expect(steam?.getAttribute('rel')).toContain('noopener');
    expect(steam?.getAttribute('rel')).not.toContain('nofollow');
    expect(footer.querySelector('a[href="/privacy"]')).not.toBeNull();
  });
});
