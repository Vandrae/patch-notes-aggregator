package com.vandrae.patchnotes.fetch.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param pollingEnabled the background poller runs (tests turn it off and trigger fetches explicitly)
 * @param tick           how often the poller wakes up to look for games that are due
 * @param initialDelay   wait before the first tick after startup
 * @param batchSize      most games handled in one tick, so a tick always ends in bounded time
 * @param schedule       how long to wait before looking at a game again
 * @param pacing         how fast requests may go out, and what to do when Steam says "too many"
 */
@ConfigurationProperties("app.fetch")
public record FetchProperties(
        @DefaultValue("true") boolean pollingEnabled,
        @DefaultValue("30s") Duration tick,
        @DefaultValue("30s") Duration initialDelay,
        @DefaultValue("250") int batchSize,
        @DefaultValue Schedule schedule,
        @DefaultValue Pacing pacing) {

    /**
     * @param minInterval       the shortest wait: used right after a game published a patch
     * @param maxInterval       the longest wait, a hard promise: no game goes unchecked for longer than this
     * @param ageDivisor        the wait is the time since the game's newest patch note divided by this, within the limits
     *                          above (patched an hour ago: check soon; quiet for months: check once a day)
     * @param jitter            fraction by which a wait may be randomly SHORTENED, so games don't all become due together
     *                          (it only shortens, so {@code maxInterval} still holds)
     * @param failureBackoff    the first wait after a failed poll; it doubles with every consecutive failure
     * @param maxFailureBackoff the longest wait after repeated failures
     */
    public record Schedule(
            @DefaultValue("15m") Duration minInterval,
            @DefaultValue("24h") Duration maxInterval,
            @DefaultValue("4") int ageDivisor,
            @DefaultValue("0.1") double jitter,
            @DefaultValue("5m") Duration failureBackoff,
            @DefaultValue("6h") Duration maxFailureBackoff) {

        public Schedule {
            if (minInterval.isNegative() || minInterval.compareTo(maxInterval) > 0) {
                throw new IllegalArgumentException("app.fetch.schedule: min-interval must be between zero and max-interval");
            }
            if (ageDivisor < 1) {
                throw new IllegalArgumentException("app.fetch.schedule.age-divisor must be at least 1");
            }
            jitter = Math.clamp(jitter, 0.0, 0.9);
        }
    }

    /**
     * @param requestsPerSecond the steady request rate to Steam's news API. Measured: 100 uncached requests at about
     *                          4 per second and a burst of 100 with 10 in parallel were both answered without throttling,
     *                          so 5 is deliberately conservative.
     * @param rateLimitPause    how long the poller stands down after Steam answers HTTP 429
     */
    public record Pacing(
            @DefaultValue("5") double requestsPerSecond,
            @DefaultValue("30s") Duration rateLimitPause) {

        public Pacing {
            if (requestsPerSecond <= 0) {
                throw new IllegalArgumentException("app.fetch.pacing.requests-per-second must be positive");
            }
        }
    }
}
