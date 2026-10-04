import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AnnounceContext } from '../hooks';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { jsonResponse, renderApp, stubApi } from '../test-utils';
import type { Game, WatchlistItem } from '../types';
import { WatchButton } from './WatchButton';

const game: Game = { id: 1, name: 'RuneScape: Dragonwilds', sourceType: 'STEAM_NEWS', steamAppId: 1374490, shortDescription: null, imageUrl: null, iconUrl: null, genres: [], rating: null, ageRating: null };

describe('WatchButton', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
  });
  afterEach(() => vi.unstubAllGlobals());

  it('flips immediately when clicked, before the server answers', async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    let watchlist: WatchlistItem[] = [];
    const { calls } = stubApi({
      'GET /api/watchlist': () => jsonResponse(watchlist),
      'PUT /api/watchlist/1': async () => {
        await gate; // the server is slow
        watchlist = [{ game, addedAt: '2026-10-01T00:00:00Z' }];
        return jsonResponse(null, 201);
      },
    });
    renderApp(<WatchButton game={game} />);

    const button = await screen.findByRole('button', { name: /^watch runescape/i });
    expect(button).toHaveAttribute('aria-pressed', 'false');
    await userEvent.click(button);

    // optimistic: already shows "Watching" while the request is still in flight
    expect(await screen.findByRole('button', { name: /stop watching/i })).toHaveAttribute('aria-pressed', 'true');
    release();
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT' && c.headers['X-XSRF-TOKEN'] === 't')).toBe(true));
  });

  it('snaps back and tells the user when the server refuses', async () => {
    stubApi({
      'GET /api/watchlist': () => jsonResponse([]),
      'PUT /api/watchlist/1': () => jsonResponse({}, 500),
    });
    renderApp(<WatchButton game={game} />);

    await userEvent.click(await screen.findByRole('button', { name: /^watch runescape/i }));

    await waitFor(() => expect(screen.getByRole('button', { name: /^watch runescape/i })).toHaveAttribute('aria-pressed', 'false'));
  });

  it('unwatches a followed game with a DELETE', async () => {
    let watchlist: WatchlistItem[] = [{ game, addedAt: '2026-09-01T00:00:00Z' }];
    const { calls } = stubApi({
      'GET /api/watchlist': () => jsonResponse(watchlist),
      'DELETE /api/watchlist/1': () => {
        watchlist = [];
        return jsonResponse(null, 204);
      },
    });
    renderApp(<WatchButton game={game} />);

    await userEvent.click(await screen.findByRole('button', { name: /stop watching/i }));

    await waitFor(() => expect(calls.some((c) => c.method === 'DELETE')).toBe(true));
    expect(await screen.findByRole('button', { name: /^watch runescape/i })).toBeInTheDocument();
  });

  describe('when the server refuses for a reason the person can act on', () => {
    const refuse = (status: number, detail: string) =>
      stubApi({
        'GET /api/watchlist': () => jsonResponse([]),
        'PUT /api/watchlist/1': () => jsonResponse({ status, detail }, status),
      });

    const click = async () => {
      const announce = vi.fn();
      renderApp(
        <AnnounceContext.Provider value={announce}>
          <WatchButton game={game} />
        </AnnounceContext.Provider>,
      );
      await userEvent.click(await screen.findByRole('button', { name: /^watch runescape/i }));
      return announce;
    };

    it('shows "List full" on the button and announces the full explanation', async () => {
      refuse(409, 'Your list is full: you can follow up to 500 games. Remove one to add another.');

      const announce = await click();

      expect(await screen.findByRole('button', { name: /^watch runescape/i })).toHaveTextContent('List full');
      expect(announce).toHaveBeenCalledWith(expect.stringContaining('up to 500 games'));
    });

    it('shows "Slow down" when following too quickly', async () => {
      refuse(429, "You're doing that too quickly. Please wait 12 seconds and try again.");

      const announce = await click();

      expect(await screen.findByRole('button', { name: /^watch runescape/i })).toHaveTextContent('Slow down');
      expect(announce).toHaveBeenCalledWith(expect.stringContaining('too quickly'));
    });

    it('goes back to "+ Watch" after a few seconds', async () => {
      refuse(429, 'Slow.');
      await click();
      const button = await screen.findByRole('button', { name: /^watch runescape/i });
      expect(button).toHaveTextContent('Slow down');

      await waitFor(() => expect(button).toHaveTextContent('+ Watch'), { timeout: 6000 });
    }, 10000);
  });
});
