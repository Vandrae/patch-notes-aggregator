# Testing and continuous integration

Four layers, from fastest to most realistic. All run in CI on every push.

| Layer | What it covers | Run it |
|---|---|---|
| Backend unit and integration tests (JUnit) | module boundaries, Steam sign-in security, catalog sync, search ranking (including a 150,000-game scale test), the normalizers, rate limits, the HTTP flow | `./mvnw test` |
| Frontend tests (Vitest, Testing Library) | pages and components, with `fetch` faked | `cd web && npm test` |
| Real-MySQL tests (Testcontainers) | the production database setup, see below | part of `./mvnw test`, needs Docker |
| Browser tests (Playwright) | the packaged app in a real browser, against a fake Steam | see below |

`./mvnw package` runs the backend and frontend tests and builds the web UI into the jar; `-Dskip.frontend=true` skips the frontend.

**Tests against a real MySQL.** Most tests run on H2 in MySQL compatibility mode, which needs no setup but is not MySQL: it
accepts some SQL that MySQL rejects and serialises writes that MySQL runs concurrently. `MySqlIntegrationTest` and
`MySqlFetchStateConcurrencyTest` therefore start a throwaway MySQL 8.4 container with [Testcontainers](https://testcontainers.com)
and run the production database setup against it: every Flyway migration plus Hibernate's schema check, the catalog import
(accents, trademark signs, emoji, Japanese), search ranking and the genre / rating / age filters, watching a game through the
persisted event registry, the feed API, account deletion, and the poller. They need Docker; without it they are **skipped, not
failed**, so `./mvnw test` still works anywhere. CI always has Docker, runs them on every push, and fails if they were skipped.
They have already earned their keep: they found that two fetches finishing together, or a fetch and the poller, could
deadlock on MySQL when creating a game's schedule row (the fetch then failed until the next poll). H2 cannot show that, and
`MySqlFetchStateConcurrencyTest` reproduces it on purpose (300 racing pairs) so it cannot come back.

**Browser tests** (`e2e/`, [Playwright](https://playwright.dev) driving Chromium) run the *packaged* app, the same jar that ships,
through a real browser. Steam is replaced by a small fake (`e2e/fake-steam.mjs`) that serves the OpenID sign-in page, answers
`check_authentication` the way Steam does (it vouches only for a response it issued, and only once) and serves a news feed, so
the tests are offline and repeatable. They cover the whole journey: being sent to sign in and coming back, signing in through
Steam, finding a game, following it, its patch notes appearing in the feed (and press coverage not), the session surviving a
reload, unfollowing, signing out, and deleting the account; and the security-relevant paths: a sign-in response that was not
asked for, a genuine one replayed after signing out, a `next` that points at another site, API calls without a session or
without the anti-forgery header, and the sign-in rate limit with its on-screen message. To run them locally:

```bash
./mvnw -DskipTests package                    # builds the jar the tests start
cd e2e && npm install && npx playwright install chromium
npx playwright test                           # starts the fake Steam (port 9099) and the app (port 8089) itself
```

They use their own in-memory database and nothing in `.env`. The last test uses up the sign-in allowance on purpose, so it is
named to run last. On failure CI keeps a report with traces and screenshots as a build artifact.

**Continuous integration** (`.github/workflows/ci.yml`, on every push): the backend tests on JDK 21 (including the real-MySQL tests above), the frontend typecheck, tests
and build on Node 24, the browser tests above, then the Docker image is built and the production compose stack is started against a real MySQL 8.4
and smoke-tested through Caddy (health, the web app, a client-side route, the API rejecting anonymous calls, http being redirected
to https, the app's own port not being reachable, the security headers including HSTS, the memory limits being in force, the
`prod` profile being active, Flyway applying its migrations on MySQL, and sign-in being rate limited). A failing backend test is shown as an annotation on the run page.

## Screenshots

The pictures in the README (`docs/screenshots/`) are generated, not drawn by hand, so they can be redone whenever the UI changes.
They are not part of the test suite and need the real catalog, which a fresh checkout does not have: run the app once with
`STEAM_API_KEY` set and let it import (see [the catalog](catalog-and-search.md)), build the jar, then:

```bash
./mvnw -DskipTests package
cd e2e && npm install && npx playwright install chromium
npm run screenshots
```

What it does (`e2e/screenshots/`): copies `data/patchnotes.mv.db` to one temporary folder (replaced on every run) and starts the packaged app on the copy
(your real database is never opened; a forced stop can leave that one copy, about 200 MB, in the temp folder until the next run replaces it), with only sign-in faked by the same fake Steam the
browser tests use, so no real account is involved. It then follows a few real games, waits for their real patch notes to
arrive, and photographs the feed, the Discover list, the open filters, a game page, the page of a game that is not on Steam, and the feed at phone size in a headless browser. The patch notes are
whatever the publishers posted that day, so the pictures change a little each time.
