import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { Game } from './types';

/** The genre and rating filter shared by Discover and the feed. It lives in the URL, so it survives a reload and can be shared. */
export interface Filters {
  /** genre codes (e.g. "RPG"); a game matches when it has at least one. Empty = any genre. */
  genres: string[];
  /** Steam review level (1-9) a game must reach; 0 = any rating. */
  minRating: number;
}

export const NO_FILTERS: Filters = { genres: [], minRating: 0 };

export const isFiltering = (f: Filters) => f.genres.length > 0 || f.minRating > 0;

/** Query-string tail for the API, e.g. "&genre=RPG&genre=ACTION&minRating=8"; empty when not filtering. */
export function filterQuery(f: Filters): string {
  const parts = f.genres.map((g) => `&genre=${encodeURIComponent(g)}`);
  if (f.minRating > 0) parts.push(`&minRating=${f.minRating}`);
  return parts.join('');
}

/** Same rule the server applies, so the UI can narrow its own lists (like the feed's per-game chips) without a request. */
export function matchesFilters(game: Game, f: Filters): boolean {
  const genreOk = f.genres.length === 0 || (game.genres ?? []).some((g) => f.genres.includes(g));
  const ratingOk = f.minRating === 0 || (game.rating?.score ?? 0) >= f.minRating;
  return genreOk && ratingOk;
}

const GENRE_PARAM = 'genre';
const RATING_PARAM = 'rating';

/** Reads and writes the filter in the URL's query string, leaving every other parameter (like the feed's game) alone. */
export function useFilters() {
  const [params, setParams] = useSearchParams();
  const genreKey = params.getAll(GENRE_PARAM).join(',');
  const rating = params.get(RATING_PARAM);

  const filters = useMemo<Filters>(() => {
    const parsed = Number(rating);
    return {
      genres: genreKey ? genreKey.split(',') : [],
      minRating: Number.isInteger(parsed) && parsed >= 1 && parsed <= 9 ? parsed : 0,
    };
  }, [genreKey, rating]);

  const update = useCallback(
    (next: Filters, alsoDrop: string[] = []) =>
      setParams(
        (current) => {
          const out = new URLSearchParams(current);
          out.delete(GENRE_PARAM);
          out.delete(RATING_PARAM);
          alsoDrop.forEach((key) => out.delete(key));
          next.genres.forEach((g) => out.append(GENRE_PARAM, g));
          if (next.minRating > 0) out.set(RATING_PARAM, String(next.minRating));
          return out;
        },
        { replace: true },
      ),
    [setParams],
  );

  return { filters, setFilters: update };
}
