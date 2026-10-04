import { useEffect, useState } from 'react';
import { ApiError } from '../api';
import { useAnnounce } from '../hooks';
import { useToggleWatch, useWatchlist } from '../queries';
import type { Game } from '../types';

const NOTICE_MS = 4000;

/** A word or two that fits where "+ Watch" was, for when the server refused for a reason the person can act on. */
function shortNotice(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return 'List full';
    if (error.status === 429) return 'Slow down';
  }
  return 'Try again';
}

export function WatchButton({ game }: { game: Game }) {
  const { data: watchlist } = useWatchlist();
  const toggle = useToggleWatch();
  const announce = useAnnounce();
  const [notice, setNotice] = useState<string | null>(null);
  const watching = watchlist?.some((item) => item.game.id === game.id) ?? false;

  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(null), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [notice]);

  const onClick = () => {
    const watch = !watching;
    setNotice(null);
    toggle.mutate(
      { game, watch },
      {
        onSuccess: () => announce(watch ? `Now watching ${game.name}` : `Stopped watching ${game.name}`),
        onError: (error) => {
          setNotice(shortNotice(error));
          announce(
            (error instanceof ApiError && error.detail) || `Couldn't update ${game.name}. Please try again.`,
          );
        },
      },
    );
  };

  return (
    <button
      type="button"
      className={watching ? 'btn btn-watching' : 'btn btn-primary'}
      aria-pressed={watching}
      aria-label={`${watching ? 'Stop watching' : 'Watch'} ${game.name}`}
      disabled={!watchlist}
      onClick={onClick}
    >
      {notice ?? (watching ? '✓ Watching' : '+ Watch')}
    </button>
  );
}
