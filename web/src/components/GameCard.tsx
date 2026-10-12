import { Link } from 'react-router-dom';
import { useFilterOptions } from '../queries';
import type { Game } from '../types';
import { GameMeta } from './GameMeta';
import { GameThumb } from './GameThumb';
import { WatchButton } from './WatchButton';

/** Steam's review colours: blue-green for positive, amber for mixed, red for negative. */
function tone(score: number): 'good' | 'mixed' | 'bad' {
  return score >= 7 ? 'good' : score >= 5 ? 'mixed' : 'bad';
}

/**
 * A game on the Discover page: just its cover picture until the mouse comes over it (or keyboard focus reaches it),
 * then a dark panel with its details, like the "latest updates" panel on a game's banner. The panel is always in the page,
 * only faded out, so screen readers and keyboard users reach everything; on a touch screen, which cannot hover, a name
 * and Watch button strip stays visible instead. The name is a link that stretches over the whole card.
 */
export function GameCard({ game }: { game: Game }) {
  const { data: options } = useFilterOptions();
  const genreLabels = new Map(options?.genres.map((g) => [g.code, g.label]));
  const genres = (game.genres ?? []).map((code) => genreLabels.get(code) ?? code).slice(0, 3);
  const rating = game.rating;
  const hasStats = rating != null || genres.length > 0;

  return (
    <li className="tile">
      <div className="tile-art">
        <GameThumb game={game} variant="tile" />
      </div>
      <div className="tile-details">
        <Link className="tile-title" to={`/games/${game.id}`}>
          {game.name}
        </Link>
        {hasStats ? (
          <dl className="tile-stats">
            {rating && (
              <div>
                <dt>Rating</dt>
                <dd className={`tile-rating tile-rating-${tone(rating.score)}`}>
                  {rating.label}
                  {rating.percentPositive != null && ` · ${rating.percentPositive}%`}
                </dd>
              </div>
            )}
            {genres.length > 0 && (
              <div>
                <dt>Genres</dt>
                <dd>{genres.join(', ')}</dd>
              </div>
            )}
          </dl>
        ) : (
          game.shortDescription && <p className="tile-desc">{game.shortDescription}</p>
        )}
        <div className="tile-foot">
          <div className="tile-foot-text">
            <span className="tile-cta" aria-hidden="true">
              View patch notes »
            </span>
            <GameMeta game={game} />
          </div>
          <WatchButton game={game} />
        </div>
      </div>
    </li>
  );
}
