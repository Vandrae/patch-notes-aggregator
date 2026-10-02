import { hueFor, initials } from '../format';

/** Steam's small capsule images are 231x87; a "wide" tile keeps that shape so rows don't jump when an image arrives. */
const WIDE_RATIO = 231 / 87;

/** A generated tile standing in for cover art: initials on a colour that is stable per game. */
export function GameTile({ name, size = 44, wide = false }: { name: string; size?: number; wide?: boolean }) {
  const hue = hueFor(name);
  return (
    <span
      className="game-tile"
      aria-hidden="true"
      style={{
        width: wide ? Math.round(size * WIDE_RATIO) : size,
        height: size,
        fontSize: size * 0.38,
        background: `linear-gradient(135deg, hsl(${hue} 60% 42%), hsl(${(hue + 40) % 360} 62% 34%))`,
      }}
    >
      {initials(name)}
    </span>
  );
}
