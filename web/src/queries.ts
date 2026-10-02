import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './api';
import type { CatalogStatus, FeedPage, Game, GamesPage, User, WatchlistItem } from './types';

export const keys = {
  me: ['me'] as const,
  watchlist: ['watchlist'] as const,
  feed: (gameId?: number) => ['feed', gameId ?? 'all'] as const,
  games: (q: string) => ['games', q] as const,
  catalogStatus: ['catalog-status'] as const,
};

/** 401 means "not signed in": that is an answer, not an error, so it is never retried. */
export function useMe() {
  return useQuery({
    queryKey: keys.me,
    queryFn: () => api.get<User>('/api/me'),
    retry: false,
    staleTime: 5 * 60_000,
  });
}

export function useWatchlist() {
  return useQuery({ queryKey: keys.watchlist, queryFn: () => api.get<WatchlistItem[]>('/api/watchlist') });
}

/** After watching a game the first patch notes arrive a few seconds later, so an empty feed re-checks briefly. */
const EMPTY_FEED_POLL_MS = 4000;
const EMPTY_FEED_MAX_POLLS = 8;

export function useFeed(gameId?: number) {
  return useInfiniteQuery({
    queryKey: keys.feed(gameId),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api.get<FeedPage>(`/api/feed?size=10&page=${pageParam}${gameId ? `&gameId=${gameId}` : ''}`),
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
    refetchInterval: (query) => {
      const waiting = query.state.data?.pages[0]?.emptyState?.reason === 'NO_ARTICLES_YET';
      return waiting && query.state.dataUpdateCount < EMPTY_FEED_MAX_POLLS ? EMPTY_FEED_POLL_MS : false;
    },
  });
}

/**
 * The catalog is imported from Steam in the background, and then each game's cover, description and popularity are
 * fetched (together a few minutes on a fresh install), so while either runs the status is re-checked every few seconds.
 */
export function useCatalogStatus() {
  return useQuery({
    queryKey: keys.catalogStatus,
    queryFn: () => api.get<CatalogStatus>('/api/catalog/status'),
    refetchInterval: (query) => (query.state.data?.syncing || query.state.data?.loadingDetails ? 3000 : false),
    staleTime: 0,
  });
}

/** @param refreshWhileImporting re-run the search periodically so results appear as the import progresses */
export function useGames(q: string, refreshWhileImporting = false) {
  return useInfiniteQuery({
    queryKey: keys.games(q),
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api.get<GamesPage>(`/api/games?size=12&page=${pageParam}&q=${encodeURIComponent(q)}`),
    getNextPageParam: (last) => (last.page.number + 1 < last.page.totalPages ? last.page.number + 1 : undefined),
    placeholderData: (previous) => previous, // keep the old results on screen while the next search loads
    refetchInterval: refreshWhileImporting ? 5000 : false,
  });
}

interface ToggleVars {
  game: Game;
  watch: boolean;
}

/**
 * Watch / unwatch with an optimistic update: the button flips instantly, and snaps back if the server says no.
 */
export function useToggleWatch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ game, watch }: ToggleVars) =>
      watch ? api.put(`/api/watchlist/${game.id}`) : api.delete(`/api/watchlist/${game.id}`),
    onMutate: async ({ game, watch }) => {
      await queryClient.cancelQueries({ queryKey: keys.watchlist });
      const previous = queryClient.getQueryData<WatchlistItem[]>(keys.watchlist);
      queryClient.setQueryData<WatchlistItem[]>(keys.watchlist, (old = []) =>
        watch
          ? [{ game, addedAt: new Date().toISOString() }, ...old.filter((i) => i.game.id !== game.id)]
          : old.filter((i) => i.game.id !== game.id),
      );
      return { previous };
    },
    onError: (_error, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(keys.watchlist, context.previous);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: keys.watchlist });
      void queryClient.invalidateQueries({ queryKey: ['feed'] });
    },
  });
}
