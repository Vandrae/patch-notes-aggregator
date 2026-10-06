import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api';
import { ArticleCard } from '../components/ArticleCard';
import { GameBadges } from '../components/GameBadges';
import { GameMeta } from '../components/GameMeta';
import { EmptyState, ErrorState, SkeletonList } from '../components/States';
import { WatchButton } from '../components/WatchButton';
import { fullDate, timeAgo } from '../format';
import { useDocumentTitle } from '../hooks';
import { keys, useGame, useGameActivity, useGamePatchNotes } from '../queries';
import type { Game } from '../types';

/** One game: its cover, who follows it, and the history of its patch notes. */
export function GamePage() {
  const { id } = useParams();
  // anything that is not a plain positive number cannot be a game
  const gameId = id !== undefined && /^[0-9]{1,15}$/.test(id) ? Number(id) : 0;
  const game = useGame(gameId);
  useDocumentTitle(game.data?.name ?? 'Game');

  if (gameId === 0 || (game.error instanceof ApiError && game.error.status === 404)) {
    return (
      <EmptyState
        title="Game not found"
        action={
          <Link className="btn btn-primary" to="/discover">
            Find games
          </Link>
        }
      >
        That game isn't in the catalog.
      </EmptyState>
    );
  }
  if (game.isPending) return <SkeletonList rows={2} label="Loading the game" />;
  if (game.isError) return <ErrorState message="We couldn't load this game." onRetry={() => void game.refetch()} />;

  return (
    <>
      <Hero game={game.data} />
      <PatchHistory gameId={gameId} />
    </>
  );
}

function Hero({ game }: { game: Game }) {
  const { data: activity } = useGameActivity(game.id);
  const [broken, setBroken] = useState(false);
  // images come from Steam's CDN; never render anything but a plain https URL into an <img>
  const cover = game.imageUrl?.startsWith('https://') && !broken ? game.imageUrl : undefined;

  return (
    <section className="card game-hero" aria-labelledby="game-title">
      {cover && (
        <img
          className="game-cover"
          src={cover}
          alt=""
          decoding="async"
          referrerPolicy="no-referrer"
          onError={() => setBroken(true)}
        />
      )}
      <div className="game-hero-body">
        <div className="game-hero-head">
          <h1 id="game-title">{game.name}</h1>
          <WatchButton game={game} />
        </div>
        {game.shortDescription && <p className="game-desc">{game.shortDescription}</p>}
        <GameBadges game={game} />
        {activity && (
          <p className="muted small game-facts">
            {activity.watcherCount === 0
              ? 'Nobody follows this game yet'
              : `${activity.watcherCount.toLocaleString()} ${activity.watcherCount === 1 ? 'person follows' : 'people follow'} this game`}
            {activity.latestPatchAt && (
              <>
                {' · '}latest patch notes{' '}
                <time dateTime={activity.latestPatchAt} title={fullDate(activity.latestPatchAt)}>
                  {timeAgo(activity.latestPatchAt)}
                </time>
              </>
            )}
          </p>
        )}
        <GameMeta game={game} />
      </div>
    </section>
  );
}

function PatchHistory({ gameId }: { gameId: number }) {
  const history = useGamePatchNotes(gameId);
  const first = history.data?.pages[0];
  const items = history.data?.pages.flatMap((p) => p.items) ?? [];
  const queryClient = useQueryClient();
  const total = first?.totalItems;

  // the newest-patch date in the header comes from a separate request: when notes arrive (or change), ask again
  useEffect(() => {
    if (total !== undefined) void queryClient.invalidateQueries({ queryKey: keys.gameActivity(gameId) });
  }, [total, gameId, queryClient]);

  return (
    <section aria-labelledby="history-heading">
      <div className="page-head">
        <h2 id="history-heading">Patch notes</h2>
        {first && first.totalItems > 0 && (
          <p className="muted">
            {first.totalItems} {first.totalItems === 1 ? 'note' : 'notes'}, newest first
          </p>
        )}
      </div>

      {history.isPending && <SkeletonList label="Loading patch notes" />}
      {history.isError && <ErrorState message="We couldn't load the patch notes." onRetry={() => void history.refetch()} />}

      {first?.emptyState?.reason === 'NOT_TRACKED' && (
        <EmptyState title="Not tracked yet">
          Nobody follows this game yet, so its patch notes haven't been fetched. Follow it to start tracking them.
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
            <ArticleCard key={item.articleId} item={item} showGame={false} />
          ))}
        </div>
      )}

      {history.hasNextPage && (
        <div className="more">
          <button type="button" className="btn" disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>
            {history.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </section>
  );
}
