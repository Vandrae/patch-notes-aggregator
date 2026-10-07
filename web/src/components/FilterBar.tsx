import { useId, useState } from 'react';
import { type Filters, isFiltering, NO_FILTERS } from '../filters';
import { useFilterOptions } from '../queries';

/**
 * A "Filters" button that opens genre chips, age-rating chips (pick any number of each: a game matches if it has at least
 * one of the chosen) and a "this review rating or better" menu. Shared by Discover and the feed. Closed by default, because
 * together the controls fill most of a phone's first screen and the patch notes are what people came for; while it is closed
 * the button shows how many filters are on and each one appears as a chip that removes it. Renders nothing until the option
 * lists have loaded; the pages work without it.
 */
export function FilterBar({ filters, onChange }: { filters: Filters; onChange: (next: Filters) => void }) {
  const { data: options } = useFilterOptions();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  if (!options) return null;

  const toggleGenre = (code: string) =>
    onChange({
      ...filters,
      genres: filters.genres.includes(code) ? filters.genres.filter((g) => g !== code) : [...filters.genres, code],
    });

  const toggleAge = (code: string) =>
    onChange({
      ...filters,
      ages: filters.ages.includes(code) ? filters.ages.filter((a) => a !== code) : [...filters.ages, code],
    });

  const ratingLabel = (minRating: number) => {
    const label = options.ratings.find((r) => r.minRating === minRating)?.label ?? `Rating ${minRating}`;
    return minRating < 9 ? `${label} or better` : label;
  };

  // what is switched on, as removable chips for the closed state
  const active: { key: string; label: string; remove: () => void }[] = [
    ...filters.genres.map((code) => ({
      key: `genre-${code}`,
      label: options.genres.find((g) => g.code === code)?.label ?? code,
      remove: () => toggleGenre(code),
    })),
    ...filters.ages.map((code) => ({
      key: `age-${code}`,
      label: options.ageRatings.find((a) => a.code === code)?.label ?? code,
      remove: () => toggleAge(code),
    })),
    ...(filters.minRating > 0
      ? [{ key: 'rating', label: ratingLabel(filters.minRating), remove: () => onChange({ ...filters, minRating: 0 }) }]
      : []),
  ];

  return (
    <section
      className="filter-bar"
      aria-label="Filters"
      onKeyDown={(e) => {
        if (e.key === 'Escape' && open) {
          e.stopPropagation();
          setOpen(false);
          (e.currentTarget.querySelector('.filter-toggle') as HTMLElement | null)?.focus();
        }
      }}
    >
      <div className="filter-head">
        <button
          type="button"
          className="btn filter-toggle"
          aria-expanded={open}
          aria-controls={panelId}
          aria-label={active.length > 0 ? `Filters, ${active.length} active` : 'Filters'}
          onClick={() => setOpen((o) => !o)}
        >
          <svg className="filter-icon" viewBox="0 0 20 20" width="16" height="16" aria-hidden="true" focusable="false">
            <path d="M3 5h14M3 10h14M3 15h14" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" fill="none" />
            <circle cx="7" cy="5" r="2" fill="var(--surface)" stroke="currentColor" strokeWidth="1.6" />
            <circle cx="13" cy="10" r="2" fill="var(--surface)" stroke="currentColor" strokeWidth="1.6" />
            <circle cx="8" cy="15" r="2" fill="var(--surface)" stroke="currentColor" strokeWidth="1.6" />
          </svg>
          Filters
          {active.length > 0 && (
            <span className="filter-count">
              {active.length}
            </span>
          )}
          <span className="filter-chevron" aria-hidden="true">
            {open ? '▴' : '▾'}
          </span>
        </button>
        {!open &&
          active.map(({ key, label, remove }) => (
            <button key={key} type="button" className="filter-summary" aria-label={`Remove filter ${label}`} onClick={remove}>
              {label}
              <span aria-hidden="true">×</span>
            </button>
          ))}
      </div>

      <div id={panelId} className="filter-panel" hidden={!open}>
        <p className="filter-label">Genre</p>
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
        {options.ageRatings.length > 0 && (
          <>
            <p className="filter-label">Age rating</p>
            <div className="chips chips-wrap" role="group" aria-label="Filter by age rating">
              {options.ageRatings.map(({ code, label }) => (
                <button
                  key={code}
                  type="button"
                  className="chip chip-small"
                  aria-pressed={filters.ages.includes(code)}
                  onClick={() => toggleAge(code)}
                >
                  {label}
                </button>
              ))}
            </div>
          </>
        )}
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
      </div>
    </section>
  );
}
