import { Link } from 'react-router-dom';
import { GameBadges } from '../components/GameBadges';
import { GameMeta } from '../components/GameMeta';
import { GameThumb } from '../components/GameThumb';
import { EmptyState, ErrorState, SkeletonList } from '../components/States';
import { WatchButton } from '../components/WatchButton';
import { fullDate } from '../format';
import { useDocumentTitle } from '../hooks';
import { useWatchlist } from '../queries';

export function WatchlistPage() {
  useDocumentTitle('Watchlist');
  const { data, isPending, isError, refetch } = useWatchlist();

  return (
    <>
      <div className="page-head">
        <h1>Your watchlist</h1>
        {data && data.length > 0 && <p className="muted">{data.length} {data.length === 1 ? 'game' : 'games'}</p>}
      </div>

      {isPending && <SkeletonList rows={2} label="Loading your watchlist" />}
      {isError && <ErrorState message="We couldn't load your watchlist." onRetry={() => void refetch()} />}

      {data && data.length === 0 && (
        <EmptyState
          title="You're not watching any games yet"
          action={
            <Link className="btn btn-primary" to="/discover">
              Find games
            </Link>
          }
        >
          Games you follow are listed here.
        </EmptyState>
      )}

      {data && data.length > 0 && (
        <ul className="list card">
          {data.map(({ game, addedAt }) => (
            <li key={game.id} className="row row-game">
              <GameThumb game={game} />
              <div className="row-main">
                <Link className="row-title" to={`/games/${game.id}`}>
                  {game.name}
                </Link>
                {game.shortDescription && <p className="row-desc">{game.shortDescription}</p>}
                <GameBadges game={game} />
                <GameMeta game={game} />
                <span className="muted small">
                  Following since {fullDate(addedAt)} ·{' '}
                  <Link to={`/feed?game=${game.id}`}>Show in feed</Link>
                </span>
              </div>
              <WatchButton game={game} />
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
