package com.vandrae.patchnotes.fetch.internal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * When to look at a game again. Pure arithmetic with no database or clock of its own, so the rules are easy to test.
 *
 * <p><b>After a successful poll</b> the wait follows how recently the game published a patch note:
 * {@code wait = (time since its newest patch note) / ageDivisor}, kept between {@code minInterval} and the cap.
 * A game patched an hour ago is checked again within the hour; one patched a week ago, daily; one with no patch notes
 * at all (or none in view) goes straight to the cap. A new patch note therefore snaps a quiet game back to frequent
 * checks on its own, with no counters to maintain. The wait is then shortened by a random amount (up to
 * {@code jitter}) so that games do not all fall due in the same minute.
 *
 * <p><b>After a failed poll</b> the wait doubles with every consecutive failure, up to {@code maxFailureBackoff}.
 */
@Component
class PollSchedule {

    private final FetchProperties.Schedule config;
    /** A number in [0, 1): how much of the allowed jitter to apply. */
    private final DoubleSupplier randomness;

    @Autowired
    PollSchedule(FetchProperties properties) {
        this(properties.schedule(), () -> ThreadLocalRandom.current().nextDouble());
    }

    PollSchedule(FetchProperties.Schedule config, DoubleSupplier randomness) {
        this.config = config;
        this.randomness = randomness;
    }

    /**
     * The longest this game may go unchecked.
     *
     * <p>This is the single place where a slower tier would plug in: a game with no patch note for a year or more could
     * be given a week here, while everything else keeps the daily cap. Today there is one tier.
     */
    Duration maxIntervalFor(Instant latestPatchAt, Instant now) {
        return config.maxInterval();
    }

    /** @param latestPatchAt publication time of the game's newest patch note, or null if it has none */
    Instant afterSuccess(Instant now, Instant latestPatchAt) {
        Duration max = maxIntervalFor(latestPatchAt, now);
        Duration wanted = latestPatchAt == null ? max : Duration.between(latestPatchAt, now).dividedBy(config.ageDivisor());
        Duration wait = shorten(clamp(wanted, config.minInterval(), max), config.minInterval());
        return now.plus(wait);
    }

    /** @param consecutiveFailures failures in a row INCLUDING the one that just happened (at least 1) */
    Instant afterFailure(Instant now, int consecutiveFailures) {
        int doublings = Math.clamp(consecutiveFailures - 1, 0, 30);
        Duration wanted = config.failureBackoff().multipliedBy(1L << doublings);
        Duration wait = shorten(min(wanted, config.maxFailureBackoff()), config.failureBackoff());
        return now.plus(wait);
    }

    /** For a game with nothing to poll (no source adapter): look again at the cap, not as a failure. */
    Instant afterNothingToPoll(Instant now) {
        return now.plus(config.maxInterval());
    }

    private Duration shorten(Duration wait, Duration floor) {
        long millis = wait.toMillis();
        long cut = (long) (millis * config.jitter() * randomness.getAsDouble());
        return Duration.ofMillis(Math.max(Math.min(floor.toMillis(), millis), millis - cut));
    }

    private static Duration clamp(Duration value, Duration low, Duration high) {
        return value.compareTo(low) < 0 ? low : min(value, high);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
