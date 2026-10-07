import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FILTER_OPTIONS, jsonResponse, renderApp, stubApi } from '../test-utils';
import type { FeedPage as FeedPageData, Game, WatchlistItem } from '../types';
import { FeedPage } from './FeedPage';

const dragonwilds: Game = { id: 1, name: 'RuneScape: Dragonwilds', sourceType: 'STEAM_NEWS', steamAppId: 1374490, shortDescription: null, imageUrl: null, iconUrl: null, genres: [], rating: null, ageRating: null };
const eldenRing: Game = { id: 2, name: 'Elden Ring', sourceType: 'STEAM_NEWS', steamAppId: 1245620, shortDescription: null, imageUrl: null, iconUrl: null, genres: [], rating: null, ageRating: null };

const watch = (...games: Game[]): WatchlistItem[] => games.map((game) => ({ game, addedAt: '2026-09-01T00:00:00Z' }));

const page = (overrides: Partial<FeedPageData>): FeedPageData => ({
  items: [],
  page: 0,
  size: 10,
  totalItems: 0,
  totalPages: 0,
  ...overrides,
});

/** The filters sit behind a "Filters" button that starts closed: this is what a person does to reach them. */

const openFilters = async () => userEvent.click(await screen.findByRole('button', { name: /^filters/i }));
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

  describe('genre and rating filters', () => {
    const eldenRpg: Game = {
      ...eldenRing,
      genres: ['ACTION', 'RPG'],
      rating: { score: 9, label: 'Overwhelmingly Positive', percentPositive: 96 },
    };
    const dragonwildsMmo: Game = {
      ...dragonwilds,
      genres: ['MASSIVELY_MULTIPLAYER'],
      rating: { score: 6, label: 'Mostly Positive', percentPositive: 74 },
    };

    const item = (gameId: number, gameName: string) => ({
      articleId: gameId,
      gameId,
      gameName,
      gameIconUrl: null,
      title: `${gameName} patch`,
      url: `https://store.steampowered.com/news/${gameId}`,
      summary: 'Fixes.',
      type: 'PATCH_NOTES' as const,
      publishedAt: '2026-09-29T10:11:15Z',
    });

    const idleCatalog = { games: 1, syncing: false, lastFullSyncAt: null, detailsLoaded: 1, loadingDetails: false };

    const stubFeed = (feed: (url: string) => Response) =>
      stubApi({
        'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS),
        'GET /api/catalog/status': () => jsonResponse(idleCatalog),
        'GET /api/watchlist': () => jsonResponse(watch(dragonwildsMmo, eldenRpg)),
        'GET /api/feed': feed,
      });

    const eldenFeed = () => jsonResponse(page({ totalItems: 1, totalPages: 1, items: [item(2, 'Elden Ring')] }));

    it('asks the API for notes of watched games with the chosen genre and rating', async () => {
      const { calls } = stubFeed(eldenFeed);
      renderApp(<FeedPage />);

      await openFilters();
      await userEvent.click(await screen.findByRole('button', { name: 'RPG' }));
      await userEvent.selectOptions(screen.getByRole('combobox', { name: /rating/i }), 'Very Positive or better');

      await waitFor(() => expect(calls.some((c) => c.url.includes('genre=RPG') && c.url.includes('minRating=8'))).toBe(true));
    });

    it('narrows the per-game chips to the watched games that pass the filter', async () => {
      stubFeed(eldenFeed);
      renderApp(<FeedPage />, '/feed?genre=ACTION');

      const group = await screen.findByRole('group', { name: /filter by game/i });
      expect(group).toHaveTextContent('Elden Ring');
      expect(group).not.toHaveTextContent('RuneScape: Dragonwilds');
    });

    it('keeps the genre and rating when a game is picked, and the game when they change', async () => {
      const { calls } = stubFeed(eldenFeed);
      renderApp(<FeedPage />, '/feed?genre=ACTION');

      const group = await screen.findByRole('group', { name: /filter by game/i });
      await userEvent.click(within(group).getByRole('button', { name: 'Elden Ring' }));
      await waitFor(() => expect(calls.some((c) => c.url.includes('gameId=2') && c.url.includes('genre=ACTION'))).toBe(true));

      // a filter the selected game still passes keeps it selected
      await openFilters();
      await userEvent.selectOptions(screen.getByRole('combobox', { name: /rating/i }), 'Very Positive or better');
      await waitFor(() => expect(calls.some((c) => c.url.includes('gameId=2') && c.url.includes('minRating=8'))).toBe(true));
    });

    it('narrows by age rating too: the request carries it and the game chips follow', async () => {
      const mature: Game = { ...eldenRpg, ageRating: 'MATURE' };
      const teen: Game = { ...dragonwildsMmo, ageRating: 'TEEN' };
      const { calls } = stubApi({
        'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS),
        'GET /api/catalog/status': () => jsonResponse(idleCatalog),
        'GET /api/watchlist': () => jsonResponse(watch(teen, mature)),
        'GET /api/feed': eldenFeed,
      });
      renderApp(<FeedPage />, '/feed?age=MATURE');

      const group = await screen.findByRole('group', { name: /filter by game/i });
      expect(group).toHaveTextContent('Elden Ring');
      expect(group).not.toHaveTextContent('RuneScape: Dragonwilds');
      expect(calls.some((c) => c.url.includes('/api/feed') && c.url.includes('age=MATURE'))).toBe(true);
    });

    it('drops the selected game when a new filter would exclude it', async () => {
      const { calls } = stubFeed(() => jsonResponse(page({ totalItems: 1, totalPages: 1, items: [item(1, 'RuneScape: Dragonwilds')] })));
      renderApp(<FeedPage />, '/feed?game=1');

      await openFilters();
      await userEvent.click(await screen.findByRole('button', { name: 'RPG' })); // Dragonwilds is not an RPG
      await waitFor(() => expect(calls.some((c) => c.url.includes('genre=RPG') && !c.url.includes('gameId='))).toBe(true));
    });

    it('says so when no watched game matches, and clearing the filters shows the feed again', async () => {
      stubFeed((url) =>
        url.includes('genre=')
          ? jsonResponse(page({ emptyState: { reason: 'NO_MATCHING_GAMES', message: 'None of the games you are watching match these filters.' } }))
          : eldenFeed(),
      );
      renderApp(<FeedPage />, '/feed?genre=SPORTS');

      expect(await screen.findByRole('heading', { name: /none of your games match/i })).toBeInTheDocument();
      expect(screen.getByText(/none of the games you watch match these filters/i)).toBeInTheDocument();

      await userEvent.click(screen.getAllByRole('button', { name: /clear filters/i })[0]);
      expect(await screen.findByRole('link', { name: /elden ring patch/i })).toBeInTheDocument();
    });

    it('offers no filters to someone who is not watching anything yet', async () => {
      stubApi({
        'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS),
        'GET /api/catalog/status': () => jsonResponse(idleCatalog),
        'GET /api/watchlist': () => jsonResponse([]),
        'GET /api/feed': () => jsonResponse(page({ emptyState: { reason: 'NO_WATCHLIST', message: '' } })),
      });
      renderApp(<FeedPage />);

      await screen.findByRole('heading', { name: /follow a game to get started/i });
      expect(screen.queryByRole('group', { name: /filter by genre/i })).toBeNull();
    });
  });
});
