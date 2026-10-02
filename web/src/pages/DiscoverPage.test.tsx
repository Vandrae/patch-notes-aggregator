import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FILTER_OPTIONS, jsonResponse, renderApp, stubApi } from '../test-utils';
import type { CatalogStatus, Game, GamesPage } from '../types';
import { DiscoverPage } from './DiscoverPage';

const deadlock: Game = { id: 7, name: 'Deadlock', sourceType: 'STEAM_NEWS', steamAppId: 1422450, shortDescription: null, imageUrl: null, iconUrl: null, genres: [], rating: null, ageRating: null };

const gamesPage = (content: Game[]): GamesPage => ({
  content,
  page: { size: 12, number: 0, totalElements: content.length, totalPages: 1 },
});

const status = (overrides: Partial<CatalogStatus>): CatalogStatus => ({
  games: 150_000,
  syncing: false,
  lastFullSyncAt: '2026-10-01T04:30:00Z',
  detailsLoaded: 150_000,
  loadingDetails: false,
  ...overrides,
});

describe('DiscoverPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('searches the catalog as you type and shows matching games', async () => {
    const { calls } = stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({})),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': (url) =>
        jsonResponse(gamesPage(url.includes('q=deadlock') ? [deadlock] : [])),
    });
    renderApp(<DiscoverPage />);

    await userEvent.type(await screen.findByRole('searchbox', { name: /search games/i }), 'deadlock');

    expect(await screen.findByText('Deadlock')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /watch deadlock/i })).toBeInTheDocument();
    // the search is debounced, so typing 8 letters is not 8 requests
    await waitFor(() => expect(calls.filter((c) => c.url.includes('q=deadlock')).length).toBe(1));
  });

  it('lets you tell same-named games apart by linking each to its Steam store page', async () => {
    const lookalike: Game = { id: 8, name: 'Deadlock', sourceType: 'STEAM_NEWS', steamAppId: 513790, shortDescription: null, imageUrl: null, iconUrl: null, genres: [], rating: null, ageRating: null };
    stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({})),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': () => jsonResponse(gamesPage([deadlock, lookalike])),
    });
    renderApp(<DiscoverPage />);

    const links = await screen.findAllByRole('link', { name: /view on steam/i });

    expect(links.map((l) => l.getAttribute('href'))).toEqual([
      'https://store.steampowered.com/app/1422450',
      'https://store.steampowered.com/app/513790',
    ]);
    expect(links[0]).toHaveAttribute('rel', expect.stringContaining('noopener'));
  });

  it('shows each game with its cover, a short description and the Steam logo link', async () => {
    const detailed: Game = {
      ...deadlock,
      shortDescription: 'Deadlock is a multiplayer game in early development.',
      imageUrl: 'https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1422450/abc/capsule_231x87.jpg?t=1',
      iconUrl: null,
    };
    stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({})),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': () => jsonResponse(gamesPage([detailed])),
    });
    const { container } = renderApp(<DiscoverPage />);

    expect(await screen.findByText('Deadlock is a multiplayer game in early development.')).toBeInTheDocument();
    expect(container.querySelector('img.game-thumb')).toHaveAttribute('src', detailed.imageUrl!);
    expect(screen.getByRole('link', { name: /view on steam/i }).querySelector('svg.steam-logo')).toBeInTheDocument();
    expect(screen.getByText(/most popular first/i)).toBeInTheDocument();
  });

  it('says results are ranked by relevance and popularity when searching', async () => {
    stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({})),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': () => jsonResponse(gamesPage([deadlock])),
    });
    renderApp(<DiscoverPage />);

    await userEvent.type(await screen.findByRole('searchbox'), 'deadlock');

    expect(await screen.findByText(/ranked by relevance and popularity/i)).toBeInTheDocument();
  });

  it('explains that covers and popularity are still loading, with progress', async () => {
    stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({ loadingDetails: true, detailsLoaded: 42_000, games: 190_000 })),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': () => jsonResponse(gamesPage([deadlock])),
    });
    renderApp(<DiscoverPage />);

    expect(await screen.findByText(/loading covers, descriptions, genres, ratings and popularity/i)).toHaveTextContent('42,000 of 190,000');
  });

  it('says so when a search matches nothing', async () => {
    stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({})),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': () => jsonResponse(gamesPage([])),
    });
    renderApp(<DiscoverPage />);

    await userEvent.type(await screen.findByRole('searchbox'), 'zzzz');

    expect(await screen.findByText(/nothing matches "zzzz"\. try a different spelling/i)).toBeInTheDocument();
  });

  it('explains that the catalog is still being imported instead of showing a mysteriously empty list', async () => {
    stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({ syncing: true, games: 42_000, lastFullSyncAt: null })),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': () => jsonResponse(gamesPage([])),
    });
    renderApp(<DiscoverPage />);

    expect(await screen.findByText(/importing the steam catalog/i)).toHaveTextContent('42,000 games so far');
    await userEvent.type(await screen.findByRole('searchbox'), 'deadlock');
    expect(await screen.findByText(/still being imported, so try again in a minute/i)).toBeInTheDocument();
  });

  it('tells the user when the full catalog was never imported', async () => {
    stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({ games: 1, lastFullSyncAt: null })),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': () => jsonResponse(gamesPage([])),
    });
    renderApp(<DiscoverPage />);

    expect(await screen.findByText(/hasn't been imported yet/i)).toHaveTextContent('only 1 game is searchable');
  });

  it('shows no notice once the catalog is complete', async () => {
    stubApi({
      'GET /api/catalog/status': () => jsonResponse(status({})),
      'GET /api/watchlist': () => jsonResponse([]),
      'GET /api/games': () => jsonResponse(gamesPage([deadlock])),
    });
    renderApp(<DiscoverPage />);

    await screen.findByText('Deadlock');
    expect(screen.queryByText(/importing the steam catalog/i)).toBeNull();
    expect(screen.queryByText(/hasn't been imported/i)).toBeNull();
  });

  describe('genre and rating filters', () => {
    const rpg: Game = {
      ...deadlock,
      id: 9,
      name: 'Elden Ring',
      steamAppId: 1245620,
      genres: ['ACTION', 'RPG'],
      rating: { score: 9, label: 'Overwhelmingly Positive', percentPositive: 96 },
    };

    const stubDiscover = (games: (url: string) => Response) =>
      stubApi({
        'GET /api/catalog/status': () => jsonResponse(status({})),
        'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS),
        'GET /api/watchlist': () => jsonResponse([]),
        'GET /api/games': games,
      });

    it('sends the chosen genres and minimum rating to the API and says the list is filtered', async () => {
      const { calls } = stubDiscover((url) => jsonResponse(gamesPage(url.includes('genre=RPG') ? [rpg] : [deadlock])));
      renderApp(<DiscoverPage />);

      await userEvent.click(await screen.findByRole('button', { name: 'RPG' }));
      await userEvent.selectOptions(screen.getByRole('combobox', { name: /rating/i }), 'Very Positive or better');

      await waitFor(() =>
        expect(calls.some((c) => c.url.includes('genre=RPG') && c.url.includes('minRating=8'))).toBe(true),
      );
      expect(await screen.findByText(/fit your filters/i)).toHaveTextContent('1 game fit your filters');
      expect(screen.getByRole('button', { name: 'RPG' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('starts with the filters from the address, so a filtered view can be reloaded or shared', async () => {
      const { calls } = stubDiscover(() => jsonResponse(gamesPage([rpg])));
      renderApp(<DiscoverPage />, '/discover?genre=ACTION&genre=RPG&rating=9');

      await screen.findByText('Elden Ring');
      expect(calls.some((c) => c.url.includes('genre=ACTION&genre=RPG&minRating=9'))).toBe(true);
      expect(await screen.findByRole('button', { name: 'Action' })).toHaveAttribute('aria-pressed', 'true');
    });

    it('sends the chosen age ratings to the API, from the address as well as from clicks', async () => {
      const { calls } = stubDiscover(() => jsonResponse(gamesPage([rpg])));
      renderApp(<DiscoverPage />, '/discover?age=MATURE');

      await screen.findByText('Elden Ring');
      expect(calls.some((c) => c.url.includes('age=MATURE'))).toBe(true);
      expect(await screen.findByRole('button', { name: 'Mature 17+' })).toHaveAttribute('aria-pressed', 'true');

      await userEvent.click(screen.getByRole('button', { name: 'Teen' }));
      await waitFor(() => expect(calls.some((c) => c.url.includes('age=MATURE&age=TEEN'))).toBe(true));
    });

    it("shows each game's rating and genres", async () => {
      stubDiscover(() => jsonResponse(gamesPage([rpg])));
      const { container } = renderApp(<DiscoverPage />);

      await screen.findByText('Elden Ring');
      await waitFor(() => expect(container.querySelector('.tags')).toHaveTextContent('Action'));
      const badges = container.querySelector('.tags');
      expect(badges).toHaveTextContent('Overwhelmingly Positive · 96%');
      expect(badges).toHaveTextContent('RPG');
    });

    it('explains an empty filtered result, and clearing the filters brings the games back', async () => {
      stubDiscover((url) => jsonResponse(gamesPage(url.includes('genre=') ? [] : [deadlock])));
      renderApp(<DiscoverPage />, '/discover?genre=RPG');

      expect(await screen.findByText(/no games fit these filters\. try removing a genre or lowering the rating/i)).toBeInTheDocument();
      await userEvent.click(screen.getByRole('button', { name: /clear filters/i }));
      expect(await screen.findByText('Deadlock')).toBeInTheDocument();
    });

    it('warns that filters only know the games loaded so far while details are still loading', async () => {
      stubApi({
        'GET /api/catalog/status': () => jsonResponse(status({ loadingDetails: true, detailsLoaded: 10, games: 100 })),
        'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS),
        'GET /api/watchlist': () => jsonResponse([]),
        'GET /api/games': () => jsonResponse(gamesPage([])),
      });
      renderApp(<DiscoverPage />, '/discover?genre=RPG');

      expect(await screen.findByText(/genres and ratings are still loading from steam/i)).toBeInTheDocument();
    });
  });
});
