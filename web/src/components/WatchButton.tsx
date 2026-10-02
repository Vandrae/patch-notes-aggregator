import { useAnnounce } from '../hooks';
import { useToggleWatch, useWatchlist } from '../queries';
import type { Game } from '../types';

export function WatchButton({ game }: { game: Game }) {
  const { data: watchlist } = useWatchlist();
  const toggle = useToggleWatch();
  const announce = useAnnounce();
  const watching = watchlist?.some((item) => item.game.id === game.id) ?? false;

  const onClick = () => {
    const watch = !watching;
    toggle.mutate(
      { game, watch },
      {
        onSuccess: () => announce(watch ? `Now watching ${game.name}` : `Stopped watching ${game.name}`),
        onError: () => announce(`Couldn't update ${game.name}. Please try again.`),
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
      {watching ? '✓ Watching' : '+ Watch'}
    </button>
  );
}
