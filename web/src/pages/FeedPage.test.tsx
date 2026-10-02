import { screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, renderApp, stubApi } from '../test-utils';
import type { FeedPage as FeedPageData, Game, WatchlistItem } from '../types';
import { FeedPage } from './FeedPage';

const dragonwilds: Game = { id: 1, name: 'RuneScape: Dragonwilds', sourceType: 'STEAM_NEWS', steamAppId: 1374490, shortDescription: null, imageUrl: null, iconUrl: null };
const eldenRing: Game = { id: 2, name: 'Elden Ring', sourceType: 'STEAM_NEWS', steamAppId: 1245620, shortDescription: null, imageUrl: null, iconUrl: null };

const watch = (...games: Game[]): WatchlistItem[] => games.map((game) => ({ game, addedAt: '2026-09-01T00:00:00Z' }));

const page = (overrides: Partial<FeedPageData>): FeedPageData => ({
  items: [],
  page: 0,
  size: 10,
  totalItems: 0,
  totalPages: 0,
  ...overrides,
});

describe('FeedPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('invites a brand-new user to follow a game', async () => {
    stubApi({
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/feed': () =>
        jsonResponse(page({ emptyState: { reason: 'NO_WATCHLIST', message: 'You are not watching any games yet.' } })),
    });

    renderApp(<FeedPage />);

    expect(await screen.findByRole('heading', { name: /follow a game to get started/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /find games/i })).toHaveAttribute('href', '/discover');
  });

  it('shows a waiting message when games are followed but nothing is fetched yet', async () => {
    stubApi({
      'GET /api/watchlist': () => jsonResponse(watch(dragonwilds)),
      'GET /api/feed': () => jsonResponse(page({ emptyState: { reason: 'NO_ARTICLES_YET', message: 'none yet' } })),
    });

    renderApp(<FeedPage />);

    expect(await screen.findByRole('heading', { name: /no patch notes yet/i })).toBeInTheDocument();
  });

  it('renders patch notes with a safe external link, a relative date and a plain-text summary', async () => {
    stubApi({
      'GET /api/watchlist': () => jsonResponse(watch(dragonwilds)),
      'GET /api/feed': () =>
        jsonResponse(
          page({
            totalItems: 2,
            totalPages: 1,
            items: [
              {
                articleId: 1,
                gameId: 1,
                gameName: 'RuneScape: Dragonwilds',
                gameIconUrl: null,
                title: '1.0.0.6 is now Live!',
                url: 'https://store.steampowered.com/news/1',
                summary: 'Epic Games Store players are back online.',
                type: 'PATCH_NOTES',
                publishedAt: '2026-09-29T10:11:15Z',
              },
              {
                articleId: 2,
                gameId: 1,
                gameName: 'RuneScape: Dragonwilds',
                gameIconUrl: null,
                title: 'Sneaky <b>title</b>',
                url: 'javascript:alert(1)',
                summary: '<img src=x onerror=alert(1)>',
                type: 'PATCH_NOTES',
                publishedAt: '2026-09-20T10:00:00Z',
              },
            ],
          }),
        ),
    });

    const { container } = renderApp(<FeedPage />);

    const link = await screen.findByRole('link', { name: /1\.0\.0\.6 is now live/i });
    expect(link).toHaveAttribute('href', 'https://store.steampowered.com/news/1');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(screen.getByText('Epic Games Store players are back online.')).toBeInTheDocument();

    // hostile third-party content is rendered as inert text, never as markup or a javascript: link
    expect(screen.getByText('Sneaky <b>title</b>')).toBeInTheDocument();
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(container.querySelector('img')).toBeNull();
    expect(screen.queryByRole('link', { name: /sneaky/i })).toBeNull();
  });

  it("shows each game's Steam icon on its patch notes, and the initials tile for a game without one", async () => {
    const icon = 'https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/1374490/8e307e43b46f4dc3554487c491015ba2b24ff235.jpg';
    const item = (id: number, gameName: string, gameIconUrl: string | null) => ({
      articleId: id,
      gameId: id,
      gameName,
      gameIconUrl,
      title: `${gameName} 1.0 is live`,
      url: `https://store.steampowered.com/news/${id}`,
      summary: 'Fixes.',
      type: 'PATCH_NOTES' as const,
      publishedAt: '2026-09-29T10:11:15Z',
    });
    stubApi({
      'GET /api/watchlist': () => jsonResponse(watch(dragonwilds, eldenRing)),
      'GET /api/feed': () =>
        jsonResponse(page({ totalItems: 2, totalPages: 1, items: [item(1, 'RuneScape: Dragonwilds', icon), item(2, 'Elden Ring', null)] })),
    });

    const { container } = renderApp(<FeedPage />);

    await screen.findByRole('link', { name: /dragonwilds 1\.0 is live/i });
    const cards = container.querySelectorAll('article');
    expect(cards[0].querySelector('img.game-icon')).toHaveAttribute('src', icon);
    expect(cards[1].querySelector('img')).toBeNull();
    expect(cards[1].querySelector('.game-tile')).toHaveTextContent('ER');
  });

  it('puts each followed game\'s icon on its filter chip too', async () => {
    const icon = 'https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/1374490/8e307e43b46f4dc3554487c491015ba2b24ff235.jpg';
    stubApi({
      'GET /api/watchlist': () => jsonResponse(watch({ ...dragonwilds, iconUrl: icon }, eldenRing)),
      'GET /api/feed': () => jsonResponse(page({ emptyState: { reason: 'NO_ARTICLES_YET', message: '' } })),
    });

    renderApp(<FeedPage />);

    const group = await screen.findByRole('group', { name: /filter by game/i });
    await waitFor(() => expect(group.querySelectorAll('img.game-icon')).toHaveLength(1));
    expect(group.querySelector('img.game-icon')).toHaveAttribute('src', icon);
    // the icon is decorative: the chip is still named by the game alone
    expect(screen.getByRole('button', { name: 'RuneScape: Dragonwilds' })).toBeInTheDocument();
  });

  it('offers a filter per followed game once there is more than one', async () => {
    stubApi({
      'GET /api/watchlist': () => jsonResponse(watch(dragonwilds, eldenRing)),
      'GET /api/feed': () => jsonResponse(page({ emptyState: { reason: 'NO_ARTICLES_YET', message: '' } })),
    });

    renderApp(<FeedPage />);

    const group = await screen.findByRole('group', { name: /filter by game/i });
    await waitFor(() => expect(group).toHaveTextContent('Elden Ring'));
    expect(screen.getByRole('button', { name: 'All games' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('lets the user retry when the feed fails to load', async () => {
    let attempts = 0;
    stubApi({
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/feed': () => {
        attempts += 1;
        return attempts === 1
          ? jsonResponse({}, 500)
          : jsonResponse(page({ emptyState: { reason: 'NO_WATCHLIST', message: '' } }));
      },
    });

    renderApp(<FeedPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn't load your feed/i);
    screen.getByRole('button', { name: /try again/i }).click();
    expect(await screen.findByRole('heading', { name: /follow a game to get started/i })).toBeInTheDocument();
  });
});
