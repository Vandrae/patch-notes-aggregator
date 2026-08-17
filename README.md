# Game Patch Notes Aggregator — Design Doc

## Overview

A Spring Boot REST API that lets a user search a large catalog of games, build a
personal watchlist, and get a news-feed-style view of recent patch notes for
just the games they care about. Built to demonstrate skills a lot of junior
Java portfolios skip: scheduled background jobs, third-party API integration,
data normalization across inconsistent sources, and proper auth.

## Problem being solved

There's no single API that returns patch notes for "most games." The real
landscape:

- **Steam** publishes a free, public API that covers thousands of games at
  once — a catalog endpoint (`ISteamApps/GetAppList`) and a news endpoint
  (`ISteamNews/GetNewsForApp`) that returns official updates as structured
  JSON. No scraping required.
- **Non-Steam titles** (WoW, League of Legends, Valorant, Escape from Tarkov,
  and anything else run through its own launcher) aren't covered by Steam at
  all. These need hand-written adapters — RSS parsing where available,
  HTML scraping (Jsoup) where it isn't.

So the design is a hybrid: Steam covers broad catalog + search "for free,"
and a small, growing set of custom adapters cover the specific non-Steam
games a user actually wants.

## Architecture

```
Steam app list (~150k games)
        │
        ▼
  Game catalog (cached locally, searchable)
        │
        ▼
  User watchlist (which games a user follows)


Steam News API ──┐
                  ├──► Normalizer ──► Article table ──► Feed API
Custom adapters ──┘      (maps every source into one
 (RSS / Jsoup for          common Article model)
  non-Steam games)
```

The scheduled poller only ever polls games that appear on **someone's**
watchlist — not the full catalog. Both source types (Steam News API and
custom adapters) implement the same interface and produce the same `Article`
shape, so the rest of the pipeline doesn't care which source an article
came from.

## Core entities

| Entity | Purpose |
|---|---|
| `Game` | id, name, `steamAppId` (nullable), `sourceType` enum (`STEAM_NEWS` / `CUSTOM`) |
| `User` | standard user record for auth |
| `Watchlist` | join table, `User` ↔ `Game` (many-to-many) |
| `Article` | id, game (FK), title, url (unique-ish, see dedup note below), summary, `articleType` enum (`PATCH_NOTES` for now, room to add `NEWS`/`EVENT` later), publishedAt (UTC `Instant`), fetchedAt |

## Design decisions & known edge cases

**1. Only poll what's being watched.**
The scheduled job queries `SELECT DISTINCT game_id FROM watchlist` fresh at
the start of every cycle — not a list held in memory — so it's always
correct even as users add/remove games between cycles. Polling the full
150k-game catalog on a schedule would be wasteful and would get rate-limited
fast.

**2. Identifying "patch notes" vs. other content.**
Check the source's structured category/tag field first (many RSS feeds
already separate categories like `patch-notes` from `esports` or
`community`). Fall back to keyword matching (`patch`, `update`, `hotfix`,
`notes`) only when there's no structured tag — matching a single exact
phrase like "patch notes" misses too many real variants
("Hotfix 1.2", "VALORANT 9.10 Patch Notes", etc.).

**3. Scraping fragility.**
Any adapter that scrapes raw HTML (as opposed to parsing RSS/JSON) depends
on that page's current layout. A site redesign can silently break a
selector — the adapter won't crash, it'll just quietly return zero articles.
Pollers need to log "this source returned nothing" per adapter so a broken
one is noticed quickly rather than discovered weeks later.

**4. Leave room for non-patch-note content later.**
`Article.articleType` is an enum with just `PATCH_NOTES` for now. Costs
nothing today; adding `NEWS` or `EVENT` later is a filter addition, not a
schema migration. Steam makes this easy since its news API already returns
more than just patch notes — custom adapters would need per-source work to
support it, so this stays low priority for those.

**5. Uniform date handling.**
Every source's date format is different (RSS gives RFC-822, Steam News gives
Unix timestamps, scraped HTML gives arbitrary text). The normalizer must
convert every source's date into a single UTC `Instant` before it's
persisted, so freshness checks and sorting work consistently regardless of
where the article came from.

**6. Dedup on more than just URL.**
Same URL, changed content (e.g. a studio appends a "Hotfix (Aug 18)" section
to an already-published patch notes page) shouldn't create a duplicate row
or throw a constraint violation. Longer-term fix: compare a content hash on
re-fetch and update the existing row if it changed, rather than a hard
insert-or-fail on URL. Flagged as a stretch goal, not a launch blocker —
most patch notes don't get silently edited after publishing.

**7. Job overlap protection.**
If a poll cycle runs longer than the scheduling interval, two cycles could
run concurrently — doubling external API calls and risking duplicate
inserts. Needs a simple "job already running" guard (or a library like
ShedLock) before this goes live.

**8. Rate limits and retries.**
External calls (Steam API, scraped sites) need retry-with-backoff for
transient failures, and should respect whatever rate limits each source
documents. A single network blip shouldn't mark a source as permanently
broken.

**9. Search performance at scale.**
150k+ cached rows means "search as you type" against an unindexed name
column is a full table scan. An index on `name` covers the first pass;
MySQL full-text search is worth a look once the catalog is fully loaded and
search needs to feel snappier.

**10. Auth is required for watchlists to mean anything.**
Since a watchlist is per-user, every watchlist/feed endpoint needs to be
scoped to the authenticated user via Spring Security + JWT — pulling the
current user from `@AuthenticationPrincipal`, not trusting a `userId` passed
in the URL (the difference between actually secure and only looking
secure).

**11. Empty states.**
A brand-new user with no watchlist, or a freshly-added game with no
articles yet, should render a clean "no news yet" / "add games to get
started" state — not an error, not a blank screen.

**12. Legal/ToS on scraped sources.**
Worth a quick check of each non-Steam site's terms of service and
`robots.txt` before scraping. Low risk for personal/portfolio use of public
patch notes, but good practice — and a reasonable thing to mention if asked
in an interview.

## Build order

1. Prove one data path end-to-end — pull Steam News API data for a single
   hardcoded game, parsed into plain `Article` objects. No Spring yet.
2. Spring Boot + JPA + MySQL skeleton — `Game`, `Article`, `User`,
   `Watchlist` entities, basic CRUD.
3. Spring Security with JWT — register/login, every watchlist/feed endpoint
   scoped to the authenticated user.
4. Game search + watchlist endpoints — search the cached catalog, add/remove
   games from your own watchlist.
5. Scheduled poller — query distinct games across all watchlists, fetch,
   normalize, dedupe. Build in job-overlap protection (#7) here.
6. Feed endpoint — articles for the current user's watchlist, including the
   empty-state handling (#11).
7. Custom adapters (WoW, League, Valorant, Tarkov, etc.) once the Steam path
   is solid — same normalizer, new source implementations.

Items #6, #8, #9, #10, #12 above can be layered in as each relevant piece
gets built rather than solved upfront.

![Product Search](https://github.com/Vandrae/patch-notes-aggregator/blob/e9d164473e3e8058e39730a9f1c53e9344e49067/Screenshot%202026-08-17%20041726.png)
