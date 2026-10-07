import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Game } from '../types';
import { GameMeta } from './GameMeta';
import { GameThumb } from './GameThumb';

const game: Game = {
  id: 7,
  name: 'Deadlock',
  sourceType: 'STEAM_NEWS',
  steamAppId: 1422450,
  shortDescription: 'A multiplayer game in early development.',
  imageUrl: 'https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/1422450/abc/capsule_231x87.jpg?t=1',
  iconUrl: null,
  genres: [],
  rating: null,
  ageRating: null,
};

describe('GameThumb', () => {
  it("shows the game's cover image from Steam", () => {
    const { container } = render(<GameThumb game={game} />);

    const img = container.querySelector('img')!;
    expect(img).toHaveAttribute('src', game.imageUrl);
    expect(img).toHaveAttribute('alt', ''); // decorative: the name is always beside it
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('referrerpolicy', 'no-referrer');
  });

  it('falls back to a generated tile of the same shape when the game has no image', () => {
    const { container } = render(<GameThumb game={{ ...game, imageUrl: null }} />);

    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('.game-tile')).toHaveTextContent('DE');
  });

  it('falls back to the tile if the image fails to load', () => {
    const { container } = render(<GameThumb game={game} />);

    fireEvent.error(container.querySelector('img')!);

    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('.game-tile')).toBeInTheDocument();
  });

  it("draws this site's own cover art for a game that is not on Steam", () => {
    const { container } = render(<GameThumb game={{ ...game, imageUrl: '/art/roblox-cover.svg' }} />);
    expect(container.querySelector('img')).toHaveAttribute('src', '/art/roblox-cover.svg');
  });

  it('never renders a non-https image URL', () => {
    for (const imageUrl of ['http://evil.test/x.jpg', 'javascript:alert(1)', 'data:image/svg+xml,<svg/>', '//evil.test/x.jpg']) {
      const { container, unmount } = render(<GameThumb game={{ ...game, imageUrl }} />);
      expect(container.querySelector('img'), imageUrl).toBeNull();
      unmount();
    }
  });
});

describe('GameMeta', () => {
  it('links to the Steam store page with the Steam logo, hidden from screen readers', () => {
    const { container } = render(<GameMeta game={game} />);

    const link = screen.getByRole('link', { name: /view on steam/i });
    expect(link).toHaveAttribute('href', 'https://store.steampowered.com/app/1422450');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
    expect(link.querySelector('svg.steam-logo')).toBeInTheDocument();
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
    // the accessible name says which app it is, so same-named games can be told apart without sight
    expect(link).toHaveAccessibleName(/app 1422450/i);
  });

  it('has no Steam link for a game that is not on Steam', () => {
    render(<GameMeta game={{ ...game, steamAppId: null, sourceType: 'CUSTOM' }} />);

    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText(/from the publisher/i)).toBeInTheDocument();
  });
});
