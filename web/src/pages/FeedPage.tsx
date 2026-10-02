import { Link, useSearchParams } from 'react-router-dom';
import { ArticleCard } from '../components/ArticleCard';
import { GameIcon } from '../components/GameIcon';
import { FilterBar } from '../components/FilterBar';
import { EmptyState, ErrorState, SkeletonList } from '../components/States';
import { isFiltering, matchesFilters, NO_FILTERS, useFilters } from '../filters';
import { useDocumentTitle } from '../hooks';
import { useCatalogStatus, useFeed, useWatchlist } from '../queries';

export function FeedPage() {
  useDocumentTitle('Feed');
  const [params, setParams] = useSearchParams();
  const gameId = Number(params.get('game')) || undefined;
  const { filters, setFilters } = useFilters();

  const { data: watchlist } = useWatchlist();
  const feed = useFeed(gameId, filters);
  const { data: catalog } = useCatalogStatus();

  // the filter picks games, so the chips only offer watched games that pass it
  const shown = watchlist?.filter(({ game }) => matchesFilters(game, filters)) ?? [];

  const select = (id?: number) =>
    setParams(
      (current) => {
        const out = new URLSearchParams(current);
        if (id) out.set('game', String(id));
        else out.delete('game');
        return out;
      },
      { replace: true },
    );

  // choosing a filter that the selected game doesn't pass would leave an empty feed, so that selection is dropped
  const changeFilters = (next: typeof filters) => {
    const selected = watchlist?.find(({ game }) => game.id === gameId)?.game;
    setFilters(next, selected && !matchesFilters(selected, next) ? ['game'] : []);
  };

  const first = feed.data?.pages[0];
  const items = feed.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <>
      <div className="page-head">
        <h1>Your patch notes</h1>
        {first && first.totalItems > 0 && (
          <p className="muted">
            {first.totalItems} {first.totalItems === 1 ? 'update' : 'updates'}
          </p>
        )}
      </div>

      {watchlist && watchlist.length > 0 && <FilterBar filters={filters} onChange={changeFilters} />}

      {watchlist && watchlist.length > 1 && shown.length > 0 && (
        <div className="chips" role="group" aria-label="Filter by game">
          <button type="button" className="chip" aria-pressed={!gameId} onClick={() => select()}>
            All games
          </button>
          {shown.map(({ game }) => (
            <button
              key={game.id}
              type="button"
              className="chip"
              aria-pressed={gameId === game.id}
              onClick={() => select(game.id)}
            >
              <GameIcon name={game.name} iconUrl={game.iconUrl} size={18} />
              {game.name}
            </button>
          ))}
        </div>
      )}

      {feed.isPending && <SkeletonList label="Loading your feed" />}
      {feed.isError && <ErrorState message="We couldn't load your feed." onRetry={() => void feed.refetch()} />}

      {first?.emptyState?.reason === 'NO_WATCHLIST' && (
        <EmptyState
          title="Follow a game to get started"
          action={
            <Link className="btn btn-primary" to="/discover">
              Find games
            </Link>
          }
        >
          Pick the games you play and their patch notes will show up here, newest first.
        </EmptyState>
      )}

      {first?.emptyState?.reason === 'NO_MATCHING_GAMES' && (
        <EmptyState
          title="None of your games match"
          action={
            <button type="button" className="btn" onClick={() => changeFilters(NO_FILTERS)}>
              Clear filters
            </button>
          }
        >
          {isFiltering(filters) ? 'None of the games you watch match these filters.' : first.emptyState.message}
          {catalog?.loadingDetails && ' Genres and ratings are still being loaded from Steam, so games may appear here as that finishes.'}
        </EmptyState>
      )}

      {first?.emptyState?.reason === 'NO_ARTICLES_YET' && (
        <EmptyState title="No patch notes yet">
          We're checking for the latest ones now. They'll appear here automatically as soon as they're found.
        </EmptyState>
      )}

      {items.length > 0 && (
        <div className="stack">
          {items.map((item) => (
            <ArticleCard key={item.articleId} item={item} />
          ))}
        </div>
      )}

      {feed.hasNextPage && (
        <div className="more">
          <button type="button" className="btn" disabled={feed.isFetchingNextPage} onClick={() => void feed.fetchNextPage()}>
            {feed.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </>
  );
}
