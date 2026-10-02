import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Game } from '../types';
import { GameMeta } from './GameMeta';

const game: Game = {
  id: 1,
  name: 'Deadlock',
  sourceType: 'STEAM_NEWS',
  steamAppId: 1422450,
  shortDescription: null,
  imageUrl: null,
  iconUrl: null,
  genres: [],
  rating: null,
  ageRating: null,
};

describe('GameMeta', () => {
  it('ends the "View on Steam" link with the Steam logo instead of an arrow', () => {
    render(<GameMeta game={game} />);

    const link = screen.getByRole('link', { name: /view on steam/i });
    expect(link).toHaveAttribute('href', 'https://store.steampowered.com/app/1422450');
    expect(link.querySelectorAll('svg.steam-logo')).toHaveLength(1);
    expect(link.textContent).not.toContain('↗');
    // the logo comes after the words
    const children = Array.from(link.children).map((c) => c.tagName.toLowerCase());
    expect(children.indexOf('svg')).toBeGreaterThan(children.indexOf('span'));
  });

  it('says where non-Steam games get their notes from', () => {
    render(<GameMeta game={{ ...game, steamAppId: null }} />);
    expect(screen.getByText(/patch notes from the publisher/i)).toBeInTheDocument();
  });
});
