import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { EmptyState, ErrorState, SkeletonList } from '../components/States';
import { GameMeta } from '../components/GameMeta';
import { GameThumb } from '../components/GameThumb';
import { WatchButton } from '../components/WatchButton';
import { useDebounced, useDocumentTitle } from '../hooks';
import { useCatalogStatus, useGames } from '../queries';

export function DiscoverPage() {
  useDocumentTitle('Discover');
  const [query, setQuery] = useState('');
  const q = useDebounced(query.trim());
  const queryClient = useQueryClient();

  const { data: catalog } = useCatalogStatus();
  const importing = catalog?.syncing ?? false;
  const loadingDetails = catalog?.loadingDetails ?? false;
  const busy = importing || loadingDetails;
  const games = useGames(q, busy);

  // when an import (or the details that follow it) finishes, refresh the search once more so the final order shows
  const wasBusy = useRef(false);
  useEffect(() => {
    if (wasBusy.current && !busy) {
      void queryClient.invalidateQueries({ queryKey: ['games'] });
    }
    wasBusy.current = busy;
  }, [busy, queryClient]);

  const results = games.data?.pages.flatMap((p) => p.content) ?? [];
  const total = games.data?.pages[0]?.page.totalElements;
  const neverImported = catalog && !catalog.syncing && catalog.lastFullSyncAt === null;

  return (
    <>
      <div className="page-head">
        <h1>Discover games</h1>
        <p className="muted">Search the catalog and follow the games you play.</p>
      </div>

      {importing && (
        <p className="notice notice-info" role="status">
          Importing the Steam catalog… {catalog?.games.toLocaleString()} games so far. This takes a minute or two the
          first time; results appear here as they arrive.
        </p>
      )}
      {!importing && loadingDetails && catalog && (
        <p className="notice notice-info" role="status">
          Loading covers, descriptions and popularity… {catalog.detailsLoaded.toLocaleString()} of{' '}
          {catalog.games.toLocaleString()} games. Steam limits how fast this can go, so the first time it takes a while
          (the best-known games come first). Results are ordered by popularity and get more accurate as it finishes.
        </p>
      )}
      {neverImported && (
        <p className="notice notice-info" role="status">
          The full Steam catalog hasn't been imported yet, so only {catalog.games.toLocaleString()}{' '}
          {catalog.games === 1 ? 'game is' : 'games are'} searchable. The server needs a Steam API key to import it.
        </p>
      )}

      <div className="search">
        <label htmlFor="game-search" className="visually-hidden">
          Search games
        </label>
        <input
          id="game-search"
          type="search"
          placeholder="Search by name…"
          autoComplete="off"
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      {games.isPending && <SkeletonList rows={3} label="Loading games" />}
      {games.isError && <ErrorState message="We couldn't search the catalog." onRetry={() => void games.refetch()} />}

      {!games.isPending && !games.isError && results.length === 0 && (
        <EmptyState title="No games found">
          {q
            ? importing
              ? `Nothing matches "${q}" yet. The catalog is still being imported, so try again in a minute.`
              : `Nothing matches "${q}". Try a different spelling or a shorter name.`
            : 'The catalog is empty.'}
        </EmptyState>
      )}

      {results.length > 0 && (
        <>
          <p className="muted result-count" aria-live="polite">
            {total?.toLocaleString()} {total === 1 ? 'game' : 'games'}
            {q ?` matching "${q}" · ranked by relevance and popularity` : ' · most popular first'}
          </p>
          <ul className="list card">
            {results.map((game) => (
              <li key={game.id} className="row row-game">
                <GameThumb game={game} />
                <div className="row-main">
                  <span className="row-title">{game.name}</span>
                  {game.shortDescription && <p className="row-desc">{game.shortDescription}</p>}
                  <GameMeta game={game} />
                </div>
                <WatchButton game={game} />
              </li>
            ))}
          </ul>
        </>
      )}

      {games.hasNextPage && (
        <div className="more">
          <button type="button" className="btn" disabled={games.isFetchingNextPage} onClick={() => void games.fetchNextPage()}>
            {games.isFetchingNextPage ? 'Loading…' : 'Show more'}
          </button>
        </div>
      )}
    </>
  );
}
