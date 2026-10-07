# API reference

Every route is under `/api`. Open <http://localhost:8080> and use the app; this page is for people calling the API directly.

| Method | Route | Notes |
|---|---|---|
| GET | `/api/auth/steam/login?next=/path` | public; redirects to Steam. `next` must be a same-site path |
| GET | `/api/auth/steam/callback` | public; Steam returns here; verifies, creates/updates the user, sets the session cookie |
| POST | `/api/auth/logout` | clears the session cookie |
| GET / DELETE | `/api/me` | who am I (401 = signed out) / delete my account and watchlist |
| GET | `/api/games?q=&genre=&minRating=&age=&page=&size=` · `/api/games/{id}` | catalog search (name contains, case-insensitive); `genre` and `age` (ESRB, e.g. `TEEN`) are repeatable (any of), `minRating` is Steam's 1-9 review level |
| GET | `/api/catalog/filters` | the genres, review ratings and age ratings the filters offer |
| GET | `/api/games/{id}/patch-notes?page=&size=` | one game's stored patch notes, newest first, for any signed-in user (not limited to your watchlist); `emptyState` says why there are none: `NOT_TRACKED` (nobody follows the game, so it was never fetched) or `NO_ARTICLES_YET`; 404 for a game not in the catalog |
| GET | `/api/games/{id}/activity` | how many people follow the game (a count, never who), when its newest patch notes came out, and how many there are |
| GET | `/api/watchlist` | caller's watchlist |
| PUT / DELETE | `/api/watchlist/{gameId}` | idempotent add (201 new / 204 already / 409 when the list is full) and remove; 429 when done too quickly (see [rate limits](security-and-compliance.md#rate-limits)) |
| GET | `/api/feed?page=&size=&gameId=&genre=&minRating=&age=` | patch notes for watched games, newest first; `gameId` narrows to one watched game, `genre`/`minRating`/`age` to watched games that match; `emptyState` explains an empty page |

Everything except the `/api/auth/steam/**` routes, logout and `/actuator/health` requires a session. The browser session
is an HttpOnly cookie, so writes from the browser must echo the `XSRF-TOKEN` cookie in an `X-XSRF-TOKEN` header (CSRF
protection). Scripts can instead send `Authorization: Bearer <jwt>`, which needs no CSRF header. User-scoped routes take
the user from the verified token's subject, never from the URL. Steam only reveals a SteamID (no email), which is all we store
besides your display name and avatar.
