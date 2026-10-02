package com.vandrae.patchnotes.fetch.internal;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Keeps each watched game's poll state up to date after every fetch, whether the scheduler or somebody pressing "Watch"
 * triggered it, so a game that was just fetched on demand is not polled again straight away.
 */
@Service
class PollTracker {

    private final FetchStateStore store;
    private final PollSchedule schedule;
    private final Clock clock;

    PollTracker(FetchStateStore store, PollSchedule schedule, Clock clock) {
        this.store = store;
        this.schedule = schedule;
        this.clock = clock;
    }

    /** @param newestPatchInBatch publication time of the newest patch note in what was just fetched, or null if there was none */
    void succeeded(long gameId, Instant newestPatchInBatch) {
        Instant now = clock.instant();
        // the fetched page may not reach back to the newest patch note we already know, so never move backwards
        Instant known = store.find(gameId).map(FetchStateStore.State::latestPatchAt).orElse(null);
        Instant latest = known == null ? newestPatchInBatch
                : newestPatchInBatch == null || known.isAfter(newestPatchInBatch) ? known : newestPatchInBatch;
        store.recordSuccess(gameId, now, latest, schedule.afterSuccess(now, latest));
    }

    void failed(long gameId, Throwable error) {
        Instant now = clock.instant();
        int failures = store.find(gameId).map(FetchStateStore.State::consecutiveFailures).orElse(0) + 1;
        store.recordFailure(gameId, now, failures, describe(error), schedule.afterFailure(now, failures));
    }

    /** The game has no source to poll (for example a custom game without an adapter): not a failure, just nothing to do. */
    void nothingToPoll(long gameId) {
        Instant now = clock.instant();
        Instant latest = store.find(gameId).map(FetchStateStore.State::latestPatchAt).orElse(null);
        store.recordSuccess(gameId, now, latest, schedule.afterNothingToPoll(now));
    }

    /** Exception type and message only: Steam client errors are written never to contain the API key. */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : error.getClass().getSimpleName() + ": " + message;
    }
}
