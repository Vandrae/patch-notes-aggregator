// Mirrors the JSON the Spring Boot API returns.

export interface User {
  id: number;
  /** A string on purpose: a 64-bit SteamID is larger than JavaScript's safe integer range. */
  steamId: string;
  personaName: string;
  avatarUrl: string | null;
}

export interface Rating {
  /** Steam's level, 1 (Overwhelmingly Negative) to 9 (Overwhelmingly Positive). */
  score: number;
  label: string;
  percentPositive: number | null;
}

export interface FilterOptions {
  genres: { code: string; label: string }[];
  /** "this rating or better" choices, lowest first */
  ratings: { minRating: number; label: string }[];
  /** ESRB age ratings, youngest audience first */
  ageRatings: { code: string; label: string }[];
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
  /** Standard Steam genres as codes (e.g. "RPG"); empty until details have been fetched. */
  genres: string[];
  /** User-review rating; null for a game without reviews (or before details are fetched). */
  rating: Rating | null;
  /** ESRB age rating code (e.g. "TEEN"); null when Steam shows none, which is common. */
  ageRating: string | null;
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

/** NOT_TRACKED: only on a game's own page, when nobody follows the game, so its patch notes were never fetched. */
export type EmptyReason = 'NO_WATCHLIST' | 'NO_ARTICLES_YET' | 'NO_MATCHING_GAMES' | 'NOT_TRACKED';

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

/** What is going on around one game, for its page. A count of followers, never who. */
export interface GameActivity {
  watcherCount: number;
  /** When the newest stored patch note was published; absent when there is none. */
  latestPatchAt?: string;
  patchNoteCount: number;
}
