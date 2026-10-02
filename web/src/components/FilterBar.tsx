import { type Filters, isFiltering, NO_FILTERS } from '../filters';
import { useFilterOptions } from '../queries';

/**
 * Genre chips (pick any number: a game matches if it has at least one) and a "this rating or better" menu. Shared by
 * Discover and the feed. Renders nothing until the option lists have loaded; the pages work without it.
 */
export function FilterBar({ filters, onChange }: { filters: Filters; onChange: (next: Filters) => void }) {
  const { data: options } = useFilterOptions();
  if (!options) return null;

  const toggleGenre = (code: string) =>
    onChange({
      ...filters,
      genres: filters.genres.includes(code) ? filters.genres.filter((g) => g !== code) : [...filters.genres, code],
    });

  return (
    <section className="filter-bar" aria-label="Filters">
      <div className="chips chips-wrap" role="group" aria-label="Filter by genre">
        {options.genres.map(({ code, label }) => (
          <button
            key={code}
            type="button"
            className="chip chip-small"
            aria-pressed={filters.genres.includes(code)}
            onClick={() => toggleGenre(code)}
          >
            {label}
          </button>
        ))}
      </div>
      <div className="filter-row">
        <label className="filter-select">
          <span>Rating</span>
          <select value={filters.minRating} onChange={(e) => onChange({ ...filters, minRating: Number(e.target.value) })}>
            <option value={0}>Any rating</option>
            {options.ratings.map(({ minRating, label }) => (
              <option key={minRating} value={minRating}>
                {minRating < 9 ? `${label} or better` : label}
              </option>
            ))}
          </select>
        </label>
        {isFiltering(filters) && (
          <button type="button" className="link-button" onClick={() => onChange(NO_FILTERS)}>
            Clear filters
          </button>
        )}
      </div>
    </section>
  );
}
