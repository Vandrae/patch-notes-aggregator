import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FILTER_OPTIONS, jsonResponse, renderApp, stubApi } from '../test-utils';
import type { Game } from '../types';
import { GameCard } from './GameCard';

const game = (over: Partial<Game>): Game => ({
  id: 7,
  name: 'Elden Ring',
  sourceType: 'STEAM_NEWS',
  steamAppId: 1245620,
  shortDescription: 'A fantasy action RPG.',
  imageUrl: 'https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1245620/capsule_231x87.jpg?t=1',
  iconUrl: null,
  genres: ['ACTION', 'RPG'],
  rating: { score: 9, label: 'Very Positive', percentPositive: 92 },
  ageRating: null,
  ...over,
});

const render = (g: Game) => {
  stubApi({
    'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS),
    'GET /api/watchlist': () => jsonResponse([]),
  });
  return renderApp(<ul><GameCard game={g} /></ul>);
};

describe('GameCard', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('is a picture with a details panel: name link, rating, genres by name, Steam link and a Watch button', async () => {
    const { container } = render(game({}));

    expect(container.querySelector('img.game-thumb-tile')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Elden Ring' })).toHaveAttribute('href', '/games/7');
    const stats = await waitFor(() => container.querySelector('.tile-stats')!);
    expect(stats).toHaveTextContent('Very Positive · 92%');
    expect(await within(container.querySelector('.tile-stats') as HTMLElement).findByText('Action, RPG')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /view on steam/i })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Watch Elden Ring' })).toBeInTheDocument();
    // the panel is only faded out, never removed, so keyboards and screen readers reach all of it
    expect(container.querySelector('.tile-details')).toBeInTheDocument();
  });

  it("shows the description instead of stats for a game that has neither a rating nor genres, such as one that is not on Steam", () => {
    const { container } = render(
      game({ name: 'Roblox', steamAppId: null, sourceType: 'CUSTOM', genres: [], rating: null, shortDescription: 'The online platform.' }),
    );

    expect(container.querySelector('.tile-stats')).toBeNull();
    expect(container.querySelector('.tile-desc')).toHaveTextContent('The online platform.');
    expect(screen.getByText('Patch notes from the publisher')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /view on steam/i })).toBeNull();
  });
});
