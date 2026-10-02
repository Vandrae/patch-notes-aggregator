import type { Game } from '../types';
import { useFilterOptions } from '../queries';

/** Steam's review colours: blue-green for positive, amber for mixed, red for negative. */
function tone(score: number): 'good' | 'mixed' | 'bad' {
  return score >= 7 ? 'good' : score >= 5 ? 'mixed' : 'bad';
}

/** The game's review rating ("Very Positive · 85%"), its ESRB age rating ("Mature 17+") and its genres, as small tags under the description. */
export function GameBadges({ game }: { game: Game }) {
  const { data: options } = useFilterOptions();
  const labels = new Map(options?.genres.map((g) => [g.code, g.label]));
  const genres = game.genres ?? [];
  const ageLabel = game.ageRating ? (options?.ageRatings.find((a) => a.code === game.ageRating)?.label ?? game.ageRating) : null;
  if (!game.rating && genres.length === 0 && !ageLabel) return null;

  return (
    <div className="tags">
      {game.rating && (
        <span
          className={`tag tag-rating tag-${tone(game.rating.score)}`}
          title={game.rating.percentPositive == null ? undefined : `${game.rating.percentPositive}% of user reviews are positive`}
        >
          {game.rating.label}
          {game.rating.percentPositive != null && <span className="tag-percent"> · {game.rating.percentPositive}%</span>}
        </span>
      )}
      {ageLabel && (
        <span className="tag tag-age" title="ESRB age rating">
          {ageLabel}
        </span>
      )}
      {genres.map((code) => (
        <span key={code} className="tag">
          {labels.get(code) ?? code}
        </span>
      ))}
    </div>
  );
}
