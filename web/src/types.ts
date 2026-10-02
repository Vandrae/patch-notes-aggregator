// Mirrors the JSON the Spring Boot API returns.

export interface User {
  id: number;
  /** A string on purpose: a 64-bit SteamID is larger than JavaScript's safe integer range. */
  steamId: string;
  personaName: string;
  avatarUrl: string | null;
}

export interface Game {
  id: number;
  name: string;
  sourceType: 'STEAM_NEWS' | 'CUSTOM';
  steamAppId: number | null;
  /** The store page's short summary as plain text; null until details have been fetched. */
  shortDescription: string | null;
  /** Wide cover image on Steam's CDN (https); null if the game has none. */
  imageUrl: string | null;
  /** Small square Steam icon (https); null if the game has none (yet). */
  iconUrl: string | null;
}

export interface WatchlistItem {
  game: Game;
  addedAt: string;
}

export interface FeedItem {
  articleId: number;
  gameId: number;
  gameName: string;
  /** The game's small square Steam icon (https); null if it has none (yet). */
  gameIconUrl: string | null;
  title: string;
  url: string;
  summary: string;
  type: 'PATCH_NOTES';
  publishedAt: string;
}

export type EmptyReason = 'NO_WATCHLIST' | 'NO_ARTICLES_YET';

export interface FeedPage {
  items: FeedItem[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  emptyState?: { reason: EmptyReason; message: string };
}

export interface GamesPage {
  content: Game[];
  page: { size: number; number: number; totalElements: number; totalPages: number };
}

export interface CatalogStatus {
  /** how many games are searchable right now */
  games: number;
  /** a catalog import is running, so the list is still filling up */
  syncing: boolean;
  /** null until the first complete import has finished */
  lastFullSyncAt: string | null;
  /** how many games have had their cover, description and popularity fetched */
  detailsLoaded: number;
  /** that fetch is running, so popularity ranking is only partly informed */
  loadingDetails: boolean;
}
