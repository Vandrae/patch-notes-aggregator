import { hueFor, initials } from '../format';

/** Steam's small capsule images are 231x87; a "wide" tile keeps that shape so rows don't jump when an image arrives. */
const WIDE_RATIO = 231 / 87;

/**
 * A generated tile standing in for cover art: initials on a colour that is stable per game.
 * @param fill take the whole size of the parent instead of a fixed size (the big picture on a Discover tile)
 */
export function GameTile({ name, size = 44, wide = false, fill = false }: { name: string; size?: number; wide?: boolean; fill?: boolean }) {
  const hue = hueFor(name);
  return (
    <span
      className="game-tile"
      aria-hidden="true"
      style={{
        width: fill ? '100%' : wide ? Math.round(size * WIDE_RATIO) : size,
        height: fill ? '100%' : size,
        fontSize: fill ? '2.6rem' : size * 0.38,
        borderRadius: fill ? 0 : undefined,
        background: `linear-gradient(135deg, hsl(${hue} 60% 42%), hsl(${(hue + 40) % 360} 62% 34%))`,
      }}
    >
      {initials(name)}
    </span>
  );
}
