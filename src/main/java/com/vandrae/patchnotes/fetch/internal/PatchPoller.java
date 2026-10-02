package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.externalapi.SteamRateLimitedException;
import com.vandrae.patchnotes.users.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled background job that keeps watched games' patch notes fresh without asking Steam about every game on every
 * cycle.
 *
 * <p>Every few seconds it wakes up and does three things:
 * <ol>
 *   <li><b>Reconcile.</b> Games somebody watches get a schedule row (due immediately); games nobody watches lose theirs.
 *       So only watched games are ever polled (design note #1), and the watchlist is read fresh each time.</li>
 *   <li><b>Take the games whose time has come</b>, longest overdue first, at most one batch.</li>
 *   <li><b>Poll them at a steady pace.</b> Each fetch reschedules its own game (see {@link PollSchedule}): a game that just
 *       published a patch is checked again soon, a quiet one at most once a day. If Steam answers 429 the poller slows
 *       down and stands down for a while; the games it did not reach simply stay due.</li>
 * </ol>
 *
 * <p>The {@code running} flag is the overlap guard from design note #7: a tick that outlives its interval makes the next
 * one skip rather than double the external calls. It is per-instance; running several instances would need a shared lock
 * (a lease on the state rows, or SKIP LOCKED).
 */
@Component
@ConditionalOnProperty(prefix = "app.fetch", name = "polling-enabled", havingValue = "true", matchIfMissing = true)
class PatchPoller {

    private static final Logger log = LoggerFactory.getLogger(PatchPoller.class);

    /** What one tick did. */
    record Summary(int due, int polled, int failed, boolean rateLimited, boolean paused) {
        static final Summary PAUSED = new Summary(0, 0, 0, false, true);
    }

    private final WatchlistService watchlist;
    private final FetchStateStore state;
    private final ArticleFetchService fetcher;
    private final RequestPacer pacer;
    private final FetchProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant pausedUntil = Instant.MIN;

    PatchPoller(WatchlistService watchlist, FetchStateStore state, ArticleFetchService fetcher, RequestPacer pacer,
                FetchProperties properties, Clock clock) {
        this.watchlist = watchlist;
        this.state = state;
        this.fetcher = fetcher;
        this.pacer = pacer;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.fetch.tick:PT30S}", initialDelayString = "${app.fetch.initial-delay:PT30S}")
    void tick() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Previous poll tick is still running, skipping this one");
            return;
        }
        try {
            Summary summary = pollDueGames();
            if (summary.due() > 0) {
                log.info("Poll tick: {} due, {} polled, {} failed{}", summary.due(), summary.polled(), summary.failed(),
                        summary.rateLimited() ? ", Steam asked us to slow down" : "");
            }
        } finally {
            running.set(false);
        }
    }

    Summary pollDueGames() {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return Summary.PAUSED;
        }
        reconcile(now);

        List<Long> due = state.findDue(now, properties.batchSize());
        int polled = 0;
        int failed = 0;
        boolean rateLimited = false;
        for (long gameId : due) {
            try {
                pacer.awaitTurn();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                fetcher.refreshGame(gameId);
                polled++;
                pacer.recover();
            } catch (SteamRateLimitedException e) {
                pacer.slowDown();
                pausedUntil = clock.instant().plus(properties.pacing().rateLimitPause());
                rateLimited = true;
                log.warn("Steam rate limited the poller after {} games: standing down until {}, request spacing now {}",
                        polled, pausedUntil, pacer.currentSpacing());
                break; // the rest stay due; the game that was refused did not change its schedule
            } catch (RuntimeException e) {
                failed++; // already recorded against the game, which backs off; one failure must not stop the others
                log.error("Poll failed for game id {}: {}", gameId, e.toString());
            }
        }
        return new Summary(due.size(), polled, failed, rateLimited, false);
    }

    /** Makes the set of games with a schedule match the set somebody watches. */
    private void reconcile(Instant now) {
        Set<Long> watched = new HashSet<>(watchlist.distinctWatchedGameIds());
        Set<Long> tracked = state.trackedGameIds();

        Set<Long> toTrack = new HashSet<>(watched);
        toTrack.removeAll(tracked);
        Set<Long> toForget = new HashSet<>(tracked);
        toForget.removeAll(watched);

        state.track(toTrack, now);
        state.untrack(toForget);
        if (!toTrack.isEmpty() || !toForget.isEmpty()) {
            log.info("Poll schedule: now tracking {} newly watched games, dropped {} nobody watches ({} tracked)",
                    toTrack.size(), toForget.size(), watched.size());
        }
    }
}
