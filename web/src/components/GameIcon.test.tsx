import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { GameIcon } from './GameIcon';

const ICON = 'https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/730/8dbc71957312bbd3baea65848b545be9eae2a355.jpg';

describe('GameIcon', () => {
  it("shows the game's square Steam icon at the requested size, decorative and lazy-loaded", () => {
    const { container } = render(<GameIcon name="Counter-Strike 2" iconUrl={ICON} size={32} />);

    const img = container.querySelector('img')!;
    expect(img).toHaveAttribute('src', ICON);
    expect(img).toHaveAttribute('width', '32');
    expect(img).toHaveAttribute('height', '32');
    expect(img).toHaveAttribute('alt', ''); // the name is always beside it
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('referrerpolicy', 'no-referrer');
  });

  it('falls back to the generated initials tile when the game has no icon yet', () => {
    const { container } = render(<GameIcon name="Counter-Strike 2" iconUrl={null} size={32} />);

    expect(container.querySelector('img')).toBeNull();
    const tile = container.querySelector('.game-tile')!;
    expect(tile).toHaveTextContent('CS');
    expect(tile).toHaveStyle({ width: '32px', height: '32px' }); // square, same footprint as the icon
  });

  it('falls back to the tile if the icon fails to load', () => {
    const { container } = render(<GameIcon name="Counter-Strike 2" iconUrl={ICON} />);

    fireEvent.error(container.querySelector('img')!);

    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('.game-tile')).toBeInTheDocument();
  });

  it('never renders a non-https URL into an <img>', () => {
    for (const iconUrl of ['http://evil.test/x.jpg', 'javascript:alert(1)', 'data:image/svg+xml,<svg/>', '//evil.test/x.jpg']) {
      const { container, unmount } = render(<GameIcon name="X Game" iconUrl={iconUrl} />);
      expect(container.querySelector('img'), iconUrl).toBeNull();
      unmount();
    }
  });
});
