import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FILTER_OPTIONS, jsonResponse, renderApp, stubApi } from '../test-utils';
import type { FeedItem, FeedPage, Game, GameActivity, WatchlistItem } from '../types';
import { GamePage } from './GamePage';

const game: Game = {
  id: 7,
  name: 'RuneScape: Dragonwilds',
  sourceType: 'STEAM_NEWS',
  steamAppId: 1374490,
  shortDescription: 'Survive, craft and explore.',
  imageUrl: 'https://cdn.example/header.jpg',
  iconUrl: null,
  genres: ['RPG'],
  rating: null,
  ageRating: null,
};

function note(n: number): FeedItem {
  return {
    articleId: n,
    gameId: 7,
    gameName: game.name,
    gameIconUrl: null,
    title: `Patch ${n}`,
    url: `https://store.example/news/${n}`,
    summary: `Summary ${n}`,
    type: 'PATCH_NOTES',
    publishedAt: '2026-09-01T00:00:00Z',
  };
}

function history(items: FeedItem[], totalItems = items.length, page = 0, totalPages = 1): FeedPage {
  return { items, page, size: 10, totalItems, totalPages };
}

const ACTIVITY: GameActivity = { watcherCount: 12, latestPatchAt: '2026-09-01T00:00:00Z', patchNoteCount: 2 };

function show(route = '/games/7') {
  return renderApp(
    <Routes>
      <Route path="/games/:id" element={<GamePage />} />
    </Routes>,
    route,
  );
}

describe('GamePage', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
  });
  afterEach(() => vi.unstubAllGlobals());

  const common = (watchlist: WatchlistItem[] = []) => ({
    'GET /api/games/7': () => jsonResponse(game),
    'GET /api/watchlist': () => jsonResponse(watchlist),
    'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS),
  });

  it('shows the game, how many follow it, and its patch notes newest first', async () => {
    stubApi({
      ...common(),
      'GET /api/games/7/activity': () => jsonResponse(ACTIVITY),
      'GET /api/games/7/patch-notes': () => jsonResponse(history([note(2), note(1)])),
    });

    show();

    expect(await screen.findByRole('heading', { level: 1, name: 'RuneScape: Dragonwilds' })).toBeInTheDocument();
    expect(screen.getByText('Survive, craft and explore.')).toBeInTheDocument();
    expect(await screen.findByText(/12 people follow this game/)).toBeInTheDocument();
    expect(screen.getByText(/latest patch notes/)).toBeInTheDocument();
    const titles = (await screen.findAllByRole('heading', { level: 3 })).map((h) => h.textContent);
    expect(titles[0]).toContain('Patch 2');
    expect(titles[1]).toContain('Patch 1');
    expect(screen.getByText('2 notes, newest first')).toBeInTheDocument();
    expect(document.title).toContain('RuneScape: Dragonwilds');
  });

  it('does not repeat the game name on every card, since the whole page is that game', async () => {
    stubApi({
      ...common(),
      'GET /api/games/7/activity': () => jsonResponse(ACTIVITY),
      'GET /api/games/7/patch-notes': () => jsonResponse(history([note(1)])),
    });

    show();

    const card = (await screen.findByRole('heading', { level: 3 })).closest('article') as HTMLElement;
    expect(within(card).queryByRole('link', { name: game.name })).toBeNull();
  });

  it('uses the singular for one follower and says so when nobody follows', async () => {
    stubApi({
      ...common(),
      'GET /api/games/7/activity': () => jsonResponse({ watcherCount: 1, patchNoteCount: 0 }),
      'GET /api/games/7/patch-notes': () => jsonResponse(history([])),
    });

    show();

    expect(await screen.findByText(/1 person follows this game/)).toBeInTheDocument();
    expect(screen.queryByText(/latest patch notes/)).toBeNull();
  });

  it('explains that an untracked game has nothing yet, and invites following it', async () => {
    stubApi({
      ...common(),
      'GET /api/games/7/activity': () => jsonResponse({ watcherCount: 0, patchNoteCount: 0 }),
      'GET /api/games/7/patch-notes': () =>
        jsonResponse({
          ...history([]),
          totalItems: 0,
          totalPages: 0,
          emptyState: { reason: 'NOT_TRACKED', message: 'Nobody follows this game yet.' },
        }),
    });

    show();

    expect(await screen.findByRole('heading', { name: 'Not tracked yet' })).toBeInTheDocument();
    expect(screen.getByText(/Follow it to start tracking/)).toBeInTheDocument();
    expect(screen.getByText('Nobody follows this game yet')).toBeInTheDocument();
  });

  it('loads older patch notes on request', async () => {
    const { calls } = stubApi({
      ...common(),
      'GET /api/games/7/activity': () => jsonResponse(ACTIVITY),
'GET /api/games/7/patch-notes': (url) =>        url.includes('page=1') ? jsonResponse(history([note(1)], 2, 1, 2)) : jsonResponse(history([note(2)], 2, 0, 2)),
    });
    show();

    await userEvent.click(await screen.findByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('Summary 1')).toBeInTheDocument();
    expect(screen.getByText('Summary 2')).toBeInTheDocument();
    expect(calls.some((c) => c.url.includes('page=1'))).toBe(true);
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('can be followed from its own page, and the count and history are asked for again', async () => {
    let followed = false;
    const { calls } = stubApi({
      ...common(),
      'GET /api/watchlist': () => jsonResponse(followed ? [{ game, addedAt: '2026-10-01T00:00:00Z' }] : []),
      'GET /api/games/7/activity': () => jsonResponse({ watcherCount: followed ? 1 : 0, patchNoteCount: 0 }),
      'GET /api/games/7/patch-notes': () => jsonResponse(history([])),
      'PUT /api/watchlist/7': () => {
        followed = true;
        return jsonResponse(null, 201);
      },
    });
    show();

    await userEvent.click(await screen.findByRole('button', { name: /^watch runescape/i }));

    expect(await screen.findByText(/1 person follows this game/)).toBeInTheDocument();
    expect(calls.filter((c) => c.url.includes('/api/games/7/activity')).length).toBeGreaterThan(1);
  });

  it('shows the newest-patch date once the first notes arrive, without a reload', async () => {
    let arrived = false;
    stubApi({
      ...common(),
      'GET /api/games/7/activity': () =>
        jsonResponse(arrived ? { watcherCount: 1, latestPatchAt: '2026-09-01T00:00:00Z', patchNoteCount: 1 } : { watcherCount: 1, patchNoteCount: 0 }),
      'GET /api/games/7/patch-notes': () => jsonResponse(arrived ? history([note(1)]) : history([])),
    });
    const { client } = show();
    expect(await screen.findByText(/1 person follows this game/)).toBeInTheDocument();
    expect(screen.queryByText(/latest patch notes/)).toBeNull();

    arrived = true; // the background fetch finished; the page's periodic re-check of the history picks it up
    await client.invalidateQueries({ queryKey: ['game-patch-notes'] });

    expect(await screen.findByText(/latest patch notes/)).toBeInTheDocument();
    expect(screen.getByText('Summary 1')).toBeInTheDocument();
  });

  it('says the game is not found for an id that is not in the catalog', async () => {
    stubApi({
      'GET /api/games/404': () => jsonResponse({ status: 404 }, 404),
      'GET /api/watchlist': () => jsonResponse([]),
    });

    show('/games/404');

    expect(await screen.findByRole('heading', { name: 'Game not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Find games' })).toHaveAttribute('href', '/discover');
  });

  it('never asks the server about an address that is not a game id', async () => {
    const { calls } = stubApi({ 'GET /api/watchlist': () => jsonResponse([]) });

    show('/games/not-a-number');

    expect(await screen.findByRole('heading', { name: 'Game not found' })).toBeInTheDocument();
    await waitFor(() => expect(calls.some((c) => c.url.includes('/api/games'))).toBe(false));
  });

  it('offers a retry when the game cannot be loaded', { timeout: 10000 }, async () => {
    stubApi({
      ...common(),
      'GET /api/games/7': () => jsonResponse({}, 500),
    });

    show();

    // a failed load is retried twice, with a growing pause, before the page gives up and offers the button
    expect(await screen.findByRole('button', { name: /try again/i }, { timeout: 6000 })).toBeInTheDocument();
  });

  it('only renders a plain https cover image', async () => {
    stubApi({
      ...common(),
      'GET /api/games/7': () => jsonResponse({ ...game, imageUrl: 'javascript:alert(1)' }),
      'GET /api/games/7/activity': () => jsonResponse(ACTIVITY),
      'GET /api/games/7/patch-notes': () => jsonResponse(history([])),
    });

    const { container } = show();

    await screen.findByRole('heading', { level: 1 });
    expect(container.querySelector('img.game-cover')).toBeNull();
  });
});
