import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { FILTER_OPTIONS, jsonResponse, renderApp, stubApi } from './test-utils';
import type { Game, GamesPage, User } from './types';

const game: Game = {
  id: 7,
  name: 'Deadlock',
  sourceType: 'STEAM_NEWS',
  steamAppId: 1422450,
  shortDescription: 'A multiplayer game.',
  imageUrl: null,
  iconUrl: null,
  genres: [],
  rating: null,
  ageRating: null,
};
const page: GamesPage = { content: [game], page: { size: 12, number: 0, totalElements: 1, totalPages: 1 } };
const me: User = { id: 1, steamId: '76561198000000001', personaName: 'Tester', avatarUrl: null };

const catalog = {
  'GET /api/catalog/status': () => jsonResponse({ syncing: false, loadingDetails: false, games: 1, detailsLoaded: 1, lastFullSyncAt: '2026-10-01T00:00:00Z' }),
  'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS),
  'GET /api/games': () => jsonResponse(page),
};

describe('guest mode', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('lets a guest open Discover without being sent to sign in, and invites them to sign in', async () => {
    stubApi({ 'GET /api/me': () => jsonResponse({}, 401), 'GET /api/watchlist': () => jsonResponse({}, 401), ...catalog });
    renderApp(<App />, '/discover');

    expect(await screen.findByRole('heading', { name: 'Discover games' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Deadlock' })).toBeInTheDocument();
    // the header offers sign-in, and only the tab a guest can use
    expect(screen.getByRole('link', { name: 'Sign in with Steam' })).toHaveAttribute('href', `/api/auth/steam/login?next=${encodeURIComponent('/discover')}`);
    expect(screen.getByRole('link', { name: 'Discover' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Feed' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Watchlist' })).toBeNull();
    // and each game's Watch button leads to sign-in instead
    expect(await screen.findByRole('link', { name: 'Sign in with Steam to watch Deadlock' })).toBeInTheDocument();
  });

  it('still sends a guest who asks for their feed to the sign-in page, and brings them back after', async () => {
    stubApi({ 'GET /api/me': () => jsonResponse({}, 401) });
    renderApp(<App />, '/feed');

    expect(await screen.findByRole('heading', { name: 'Never miss a patch' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /sign in through steam/i })).toHaveAttribute('href', `/api/auth/steam/login?next=${encodeURIComponent('/feed')}`);
    expect(screen.getByRole('link', { name: 'Browse as a guest' })).toHaveAttribute('href', '/discover');
  });

  it('shows a signed-in person the full navigation and no sign-in button', async () => {
    stubApi({ 'GET /api/me': () => jsonResponse(me), 'GET /api/watchlist': () => jsonResponse([]), ...catalog });
    renderApp(<App />, '/discover');

    expect(await screen.findByRole('link', { name: 'Account: Tester' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Feed' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Watchlist' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in with Steam' })).toBeNull();
  });
});
