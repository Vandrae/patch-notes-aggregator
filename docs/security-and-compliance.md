# Security, privacy and compliance

What the app does to keep sessions, secrets and visitors safe, and how it stands against the terms of the services it uses.

## Security headers

Every profile sends, on every response: a strict **Content-Security-Policy** (scripts, styles, fonts and
connections only from the app's own origin, images also from Steam's CDN, no inline script, no eval, no framing, no plugins),
`Referrer-Policy: no-referrer`, a `Permissions-Policy` that switches off camera, microphone, geolocation, payment and USB,
`Cross-Origin-Opener-Policy: same-origin`, plus Spring Security's defaults (`X-Content-Type-Options`, `X-Frame-Options: DENY`,
no caching of API responses, and `Strict-Transport-Security` for one year on https requests). The policy was checked in a
browser against every page (feed, Discover, watchlist, account) with no violations.

## Rate limits

Three things cost something that a stranger could run up: starting a sign-in, finishing one (the app asks Steam to confirm
it), and following games (each new game gets fetched from Steam). Each has a limit, hand-written as an in-memory token bucket
(a burst allowance that refills steadily; no extra dependency, and it forgets everything on restart):

| What | Limited per | Default (burst, then per minute) | When refused |
|---|---|---|---|
| Starting a sign-in (`GET /api/auth/steam/login`) | client address | 20, 20 | redirect to `/login?error=rate-limited`, with `Retry-After` |
| Finishing a sign-in (`GET /api/auth/steam/callback`) | client address | 10, 10 | the same redirect |
| Checks sent to Steam, from all sign-ins together | the whole app | 60, 1200 | the same redirect (applied after the cheap local checks, so forged callbacks cannot use it up) |
| Following or unfollowing a game (`PUT`/`DELETE /api/watchlist/{id}`) | signed-in user | 60, 60 | `429` problem document with `Retry-After` |
| Games on one list | signed-in user | 500 in total | `409` with a plain explanation |

Reading is never limited. The browser shows "Too many sign-in attempts" on the login page and "Slow down" / "List full" on the
Watch button. Change a limit with, for example, `APP_SECURITY_RATE_LIMIT_LOGIN_BURST=5` or `APP_WATCHLIST_MAX_GAMES=200` (the
keys are `app.security.rate-limit.{login,callback,watch,steam-checks}.{burst,per-minute}`, `app.security.rate-limit.enabled`
and `app.security.rate-limit.max-tracked-keys`; memory is bounded because a bucket is dropped once it has refilled).

**Which address is "the client"?** The limiter only ever uses the address Tomcat reports for the connection. Behind Caddy
(`server.forward-headers-strategy=native`, the `prod` default) Tomcat replaces it with the visitor's address from
`X-Forwarded-For`, reading from the right and only through private-network proxies, so a header a visitor sends cannot give
them a fresh allowance. This is tested over real HTTP (`ClientAddressBehindProxyTest`, `ClientAddressDirectTest`), because
mock requests cannot show it. IPv6 visitors are grouped by /64 (one home network), since one person has billions of addresses.
Refusals are counted in the `patchnotes.ratelimit.refused{rule}` metric and logged as one summary line a minute, without addresses.

## What is logged

The app writes no request log (the `prod` profile switches Tomcat's access log off explicitly, and a test
checks it), Caddy's access log is not enabled in the `Caddyfile`, and the root log level is `INFO`. The lines that do exist are
about what the app is doing, not about who asked: poll and sync summaries, game names, and the internal number of a user who
signed in. Nothing logs a session cookie, a JWT, a SteamID, an IP address, or the parameters of a sign-in response (rejected
sign-ins say only why, for example "return_to does not match this login attempt"; rate-limit refusals are a count a minute).
The two config objects that hold secrets (`SteamProperties`, `JwtProperties`) print `<set>` instead of the secret, so even
logging one by mistake leaks nothing. If you ever turn on an access log, drop the query string for `/api/auth/**`, whose
callback carries Steam's one-time signature, and do not lower the log level to `DEBUG` on a live site: that is where HTTP
clients start logging URLs.

## Steam's terms and the content shown

The [Steam Web API Terms of Use](https://steamcommunity.com/dev/apiterms) are short, and this is how the app stands against
each point that applies (read them yourself before you deploy; this is a summary, not legal advice):

| Term (paraphrased) | What the app does |
|---|---|
| At most 100,000 API calls a day | The poller is adaptive (see [adaptive polling](architecture.md#adaptive-polling)), so a watched game costs between 1 and 96 calls a day, and only games somebody watches are polled. There is **no daily counter**: the protections are the schedule, the 5 requests per second pacing, standing down for 30 seconds when Steam answers 429, and the cap of 500 games per person. Check `patchnotes.fetch.polls` if you ever suspect you are near the limit. |
| Only fetch Steam data when users ask for it | Nothing is fetched for a game until somebody watches it, and a game nobody watches loses its schedule. The catalog import is the one exception: a daily job that lists Steam's games so they can be searched. |
| Show Valve branding (name, logo, links) where the API is used | The footer of every signed-in page says "Powered by Steam", the Steam logo marks Steam-sourced games, and sign-in uses Steam's own page. The footer links "Steam" to the Steam store. |
| Do not suggest the app is endorsed by or affiliated with Valve | The footer says "Not affiliated with Valve, Riot Games, Roblox or Mojang", and the [privacy page](../web/src/pages/PrivacyPage.tsx) carries Riot's own non-endorsement notice and the trademark notices. |
| Keep the API key confidential | The key is read from `STEAM_API_KEY` (never committed; `.env` is git-ignored and `.dockerignore`d) and is sent only to Steam, only on the catalog and profile calls (the news calls are keyless). It is in the URL, and Spring's I/O errors quote the URL, so Steam failures are described by kind and HTTP status only (`SteamHttp.describe`); tests make a network error that quotes the URL and check the key appears in no exception, stack trace or log line. See [What is logged](#what-is-logged). |
| Post a privacy policy for any non-public data | The app stores only public data: your SteamID, public display name and avatar address, and the games you follow. No email, no password, no IP addresses (the rate limiter keeps them in memory only, briefly). The public `/privacy` page (linked from the sign-in page and the footer) says this in plain words, and the account page deletes everything. |

### Publishers' content

Patch notes belong to the games' publishers. The app stores only a plain-text excerpt of at most 280
characters, plus the title, date, and a hash of the full text (used to notice silent edits), never the full post and never its
images. Each note links back to the original post on Steam, which opens in a new tab with `rel="noopener noreferrer"`. Game
artwork is loaded from Steam's own CDN rather than copied. Excerpts are rendered as text, never as HTML. Games that are not on Steam get the same treatment from the publisher's own public feed: a plain-text excerpt, the date, and a link to the publisher's post (which must be a plain https address, or the post is skipped).
