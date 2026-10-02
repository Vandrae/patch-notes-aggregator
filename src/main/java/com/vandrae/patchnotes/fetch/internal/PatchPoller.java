package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.users.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled background job. Each cycle asks "which games does anyone watch?" fresh from the database
 * (design note #1), so only those games are ever polled.
 *
 * <p>The {@code running} flag is the overlap guard from design note #7: if a cycle ever outlives its
 * interval the next one is skipped rather than doubling the external calls. It is per-instance; running
 * several instances would need a shared lock (e.g. ShedLock).
 */
@Component
@ConditionalOnProperty(prefix = "app.fetch", name = "polling-enabled", havingValue = "true", matchIfMissing = true)
class PatchPoller {

    private static final Logger log = LoggerFactory.getLogger(PatchPoller.class);

    private final WatchlistService watchlist;
    private final ArticleFetchService fetcher;
    private final AtomicBoolean running = new AtomicBoolean(false);

    PatchPoller(WatchlistService watchlist, ArticleFetchService fetcher) {
        this.watchlist = watchlist;
        this.fetcher = fetcher;
    }

    @Scheduled(fixedDelayString = "${app.fetch.poll-interval:PT30M}",
            initialDelayString = "${app.fetch.initial-delay:PT30S}")
    void pollWatchedGames() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Previous poll cycle is still running, skipping this one");
            return;
        }
        try {
            List<Long> gameIds = watchlist.distinctWatchedGameIds();
            if (gameIds.isEmpty()) {
                log.debug("Poll cycle: nobody is watching any games");
                return;
            }
            int failed = 0;
            for (long gameId : gameIds) {
                try {
                    fetcher.refreshGame(gameId);
                } catch (RuntimeException e) {
                    failed++; // one game's failure (after retries) must not stop the others
                    log.error("Poll failed for game id {}: {}", gameId, e.toString());
                }
            }
            log.info("Poll cycle finished: {} games, {} failed", gameIds.size(), failed);
        } finally {
            running.set(false);
        }
    }
}
