import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FILTER_OPTIONS, jsonResponse, renderApp, stubApi } from '../test-utils';
import type { Game } from '../types';
import { GameBadges } from './GameBadges';

const game = (over: Partial<Game>): Game => ({
  id: 1,
  name: 'Elden Ring',
  sourceType: 'STEAM_NEWS',
  steamAppId: 1245620,
  shortDescription: null,
  imageUrl: null,
  iconUrl: null,
  genres: [],
  rating: null,
  ...over,
});

describe('GameBadges', () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows Steam's rating words with the percentage, and the genres by their readable names", async () => {
    stubApi({ 'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS) });
    renderApp(
      <GameBadges
        game={game({
          genres: ['ACTION', 'MASSIVELY_MULTIPLAYER'],
          rating: { score: 8, label: 'Very Positive', percentPositive: 92 },
        })}
      />,
    );

    expect(await screen.findByText('Massively Multiplayer')).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();
    const rating = screen.getByText('Very Positive');
    expect(rating.closest('.badge')).toHaveClass('badge-good');
    expect(rating.closest('.badge')).toHaveTextContent('Very Positive · 92%');
    expect(rating.closest('.badge')).toHaveAttribute('title', '92% of user reviews are positive');
  });

  it('colours mixed and negative ratings differently', () => {
    stubApi({ 'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS) });
    const { container } = renderApp(
      <>
        <GameBadges game={game({ rating: { score: 5, label: 'Mixed', percentPositive: 55 } })} />
        <GameBadges game={game({ rating: { score: 2, label: 'Very Negative', percentPositive: 12 } })} />
      </>,
    );

    expect(container.querySelector('.badge-mixed')).toHaveTextContent('Mixed');
    expect(container.querySelector('.badge-bad')).toHaveTextContent('Very Negative');
  });

  it('shows nothing for a game with no rating and no genres, and the raw code if a genre name is unknown', async () => {
    stubApi({ 'GET /api/catalog/filters': () => jsonResponse(FILTER_OPTIONS) });
    const { container } = renderApp(<GameBadges game={game({})} />);
    expect(container.querySelector('.badges')).toBeNull();

    renderApp(<GameBadges game={game({ genres: ['SPORTS'] })} />);
    expect(await screen.findByText('SPORTS')).toBeInTheDocument();
  });
});
