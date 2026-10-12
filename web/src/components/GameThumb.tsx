import { useState } from 'react';
import type { Game } from '../types';
import { safeImageSrc, wideImageSrc } from '../format';
import { GameTile } from './GameTile';

const HEIGHT = 42;
const WIDTH = Math.round(HEIGHT * (231 / 87));

/**
 * The game's cover image from Steam, or a generated tile of the same shape when there is none (details not fetched
 * yet, a game without a store page) or the image fails to load. Decorative: the game's name is always next to it.
 * @param variant "row" is the small capsule beside a name; "tile" fills its parent, for the big picture on a Discover tile
 */
export function GameThumb({ game, variant = 'row' }: { game: Game; variant?: 'row' | 'tile' }) {
  const [broken, setBroken] = useState(false);
  // a tile first tries Steam's wide header picture; if that is missing it falls back to the capsule, then to a generated tile
  const [capsuleOnly, setCapsuleOnly] = useState(false);
  // images come from Steam's CDN, or this site's own art for games that are not on Steam: nothing else goes into an <img>
  const tile = variant === 'tile';
  const capsule = safeImageSrc(game.imageUrl);
  const src = tile && !capsuleOnly ? wideImageSrc(game.imageUrl) : capsule;

  if (src && !broken) {
    return (
      <img
        className={tile ? 'game-thumb game-thumb-tile' : 'game-thumb'}
        src={src}
        alt=""
        width={tile ? 460 : WIDTH}
        height={tile ? 215 : HEIGHT}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        onError={() => (tile && !capsuleOnly && src !== capsule ? setCapsuleOnly(true) : setBroken(true))}
      />
    );
  }
  return tile ? <GameTile name={game.name} fill /> : <GameTile name={game.name} size={HEIGHT} wide />;
}
