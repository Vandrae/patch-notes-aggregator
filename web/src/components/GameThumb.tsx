import { useState } from 'react';
import type { Game } from '../types';
import { GameTile } from './GameTile';

const HEIGHT = 42;
const WIDTH = Math.round(HEIGHT * (231 / 87));

/**
 * The game's cover image from Steam, or a generated tile of the same shape when there is none (details not fetched
 * yet, a game without a store page) or the image fails to load. Decorative: the game's name is always next to it.
 */
export function GameThumb({ game }: { game: Game }) {
  const [broken, setBroken] = useState(false);
  // images come from Steam's CDN; never render anything but a plain https URL into an <img>
  const src = game.imageUrl?.startsWith('https://') ? game.imageUrl : undefined;

  if (src && !broken) {
    return (
      <img
        className="game-thumb"
        src={src}
        alt=""
        width={WIDTH}
        height={HEIGHT}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        onError={() => setBroken(true)}
      />
    );
  }
  return <GameTile name={game.name} size={HEIGHT} wide />;
}
