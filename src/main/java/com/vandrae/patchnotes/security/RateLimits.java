package com.vandrae.patchnotes.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * The application's rate limits, built from {@link RateLimitProperties}. Each refusal is counted in
 * {@code patchnotes.ratelimit.refused} (tagged with the rule's name) so it shows up in the metrics.
 */
@Component
class RateLimits {

    private static final String STEAM_KEY = "steam";

    private final boolean enabled;
    private final KeyedRateLimiter login;
    private final KeyedRateLimiter callback;
    private final KeyedRateLimiter watch;
    private final KeyedRateLimiter steamChecks;

    @Autowired
    RateLimits(RateLimitProperties props, MeterRegistry meters) {
        this(props, meters, System::nanoTime);
    }

    RateLimits(RateLimitProperties props, MeterRegistry meters, LongSupplier nanoTime) {
        this.enabled = props.enabled();
        this.login = limiter("login", props.login(), props, meters, nanoTime);
        this.callback = limiter("callback", props.callback(), props, meters, nanoTime);
        this.watch = limiter("watch", props.watch(), props, meters, nanoTime);
        this.steamChecks = limiter("steam_checks", props.steamChecks(), props, meters, nanoTime);
    }

    /** No limits at all, for tests of other things. */
    static RateLimits unlimited() {
        return new RateLimits(RateLimitProperties.disabled(), new SimpleMeterRegistry());
    }

    private static KeyedRateLimiter limiter(String name, RateLimitProperties.Rule rule, RateLimitProperties props,
                                            MeterRegistry meters, LongSupplier nanoTime) {
        return new KeyedRateLimiter(name, rule.burst(), rule.perMinute(), props.maxTrackedKeys(), nanoTime,
                () -> meters.counter("patchnotes.ratelimit.refused", "rule", name).increment());
    }

    boolean enabled() {
        return enabled;
    }

    KeyedRateLimiter login() {
        return login;
    }

    KeyedRateLimiter callback() {
        return callback;
    }

    KeyedRateLimiter watch() {
        return watch;
    }

    /** May one more sign-in check be sent to Steam right now? One shared budget for every client together. */
    boolean allowSteamCheck() {
        return !enabled || steamChecks.tryAcquire(STEAM_KEY).allowed();
    }
}
