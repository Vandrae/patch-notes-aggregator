import type { Game } from '../types';
import { useFilterOptions } from '../queries';

/** Steam's review colours: blue-green for positive, amber for mixed, red for negative. */
function tone(score: number): 'good' | 'mixed' | 'bad' {
  return score >= 7 ? 'good' : score >= 5 ? 'mixed' : 'bad';
}

/** The game's review rating ("Very Positive · 85%") and its genres, as small tags under the description. */
export function GameBadges({ game }: { game: Game }) {
  const { data: options } = useFilterOptions();
  const labels = new Map(options?.genres.map((g) => [g.code, g.label]));
  const genres = game.genres ?? [];
  if (!game.rating && genres.length === 0) return null;

  return (
    <div className="badges">
      {game.rating && (
        <span
          className={`badge badge-rating badge-${tone(game.rating.score)}`}
          title={game.rating.percentPositive == null ? undefined : `${game.rating.percentPositive}% of user reviews are positive`}
        >
          {game.rating.label}
          {game.rating.percentPositive != null && <span className="badge-percent"> · {game.rating.percentPositive}%</span>}
        </span>
      )}
      {genres.map((code) => (
        <span key={code} className="badge">
          {labels.get(code) ?? code}
        </span>
      ))}
    </div>
  );
}
