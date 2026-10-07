import { useState } from 'react';
import { safeImageSrc } from '../format';
import { GameTile } from './GameTile';

/**
 * A game's small square Steam icon, or the generated initials tile when there is none (the details job hasn't reached
 * the game yet, or Steam has no icon for it) or the image fails to load. Decorative: the game's name is always beside it.
 */
export function GameIcon({ name, iconUrl, size = 32 }: { name: string; iconUrl: string | null; size?: number }) {
  const [broken, setBroken] = useState(false);
  // from Steam's CDN, or this site's own art for games that are not on Steam: nothing else goes into an <img>
  const src = safeImageSrc(iconUrl);

  if (src && !broken) {
    return (
      <img
        className="game-icon"
        src={src}
        alt=""
        width={size}
        height={size}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        onError={() => setBroken(true)}
      />
    );
  }
  return <GameTile name={name} size={size} />;
}
