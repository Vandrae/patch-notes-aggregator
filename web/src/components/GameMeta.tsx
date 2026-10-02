import type { Game } from '../types';
import { SteamLogo } from './SteamLogo';

/**
 * The grey line under a game's name. Steam has many different games with identical names (there are three called
 * "Deadlock"), so the line carries a link to the game's store page, which is how you tell them apart.
 */
export function GameMeta({ game }: { game: Game }) {
  if (game.steamAppId == null) {
    return <span className="muted small game-meta">Patch notes from the publisher</span>;
  }
  return (
    <span className="muted small game-meta">
      <a
        className="steam-link"
        href={`https://store.steampowered.com/app/${game.steamAppId}`}
        target="_blank"
        rel="noopener noreferrer"
        title={`Steam app ${game.steamAppId}`}
      >
        <span>View on Steam</span>
        <SteamLogo size={16} />
        <span className="visually-hidden"> (app {game.steamAppId}, opens in a new tab)</span>
      </a>
    </span>
  );
}
