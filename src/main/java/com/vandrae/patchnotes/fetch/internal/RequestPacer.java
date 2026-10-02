package com.vandrae.patchnotes.fetch.internal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Spaces outgoing requests evenly ({@code requestsPerSecond}) so a large batch of due games cannot hit Steam in a burst.
 * When Steam does answer "too many requests" the spacing doubles (up to 16 times the normal gap) and then eases back
 * by a tenth after every request that succeeds, so the poller finds its own sustainable rate.
 */
@Component
class RequestPacer {

    private static final long MAX_SLOWDOWN = 16;

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final long baseSpacingNanos;
    private final long maxSpacingNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private long spacingNanos;
    private long nextSlotNanos;

    @Autowired
    RequestPacer(FetchProperties properties) {
        this(properties.pacing().requestsPerSecond(), System::nanoTime, duration -> Thread.sleep(duration));
    }

    RequestPacer(double requestsPerSecond, LongSupplier nanoTime, Sleeper sleeper) {
        this.baseSpacingNanos = (long) (1_000_000_000L / requestsPerSecond);
        this.maxSpacingNanos = baseSpacingNanos * MAX_SLOWDOWN;
        this.spacingNanos = baseSpacingNanos;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
        this.nextSlotNanos = nanoTime.getAsLong();
    }

    /** Blocks until it is this caller's turn to send a request. */
    synchronized void awaitTurn() throws InterruptedException {
        long now = nanoTime.getAsLong();
        long wait = nextSlotNanos - now;
        if (wait > 0) {
            sleeper.sleep(Duration.ofNanos(wait));
        }
        nextSlotNanos = Math.max(now, nextSlotNanos) + spacingNanos;
    }

    /** Steam asked us to slow down. */
    synchronized void slowDown() {
        spacingNanos = Math.min(maxSpacingNanos, spacingNanos * 2);
    }

    /** A request went through: ease back toward the normal rate. */
    synchronized void recover() {
        spacingNanos = Math.max(baseSpacingNanos, spacingNanos * 9 / 10);
    }

    synchronized Duration currentSpacing() {
        return Duration.ofNanos(spacingNanos);
    }
}
