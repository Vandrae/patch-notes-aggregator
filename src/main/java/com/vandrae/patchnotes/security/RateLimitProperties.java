package com.vandrae.patchnotes.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on how fast one client may use the endpoints that cost the most or can be abused.
 *
 * @param enabled        turn all of it off (tests and local experiments only)
 * @param maxTrackedKeys most clients tracked per limit at once; beyond this, new clients share one bucket (bounds memory)
 * @param login          starting a sign-in, per client address
 * @param callback       Steam sending the browser back, per client address. Each one that looks plausible makes the server
 *                       call Steam to check it, so this is the strictest
 * @param watch          following or unfollowing a game, per signed-in user. Every followed game is polled, so a runaway
 *                       script could otherwise spend the app's Steam request budget
 * @param steamChecks    ALL sign-in checks sent to Steam, summed over every client: the ceiling that still holds when an
 *                       attacker spreads requests over many addresses
 */
@ConfigurationProperties("app.security.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100000") int maxTrackedKeys,
        Rule login,
        Rule callback,
        Rule watch,
        Rule steamChecks) {

    /** A token bucket: {@code burst} requests at once, refilled at {@code perMinute} a minute. */
    public record Rule(int burst, int perMinute) {
        public Rule {
            if (burst < 1 || perMinute < 1) {
                throw new IllegalArgumentException("app.security.rate-limit: burst and per-minute must both be at least 1");
            }
        }
    }

    public RateLimitProperties {
        if (maxTrackedKeys < 2) {
            throw new IllegalArgumentException("app.security.rate-limit.max-tracked-keys must be at least 2");
        }
        login = login == null ? new Rule(20, 20) : login;
        callback = callback == null ? new Rule(10, 10) : callback;
        watch = watch == null ? new Rule(60, 60) : watch;
        steamChecks = steamChecks == null ? new Rule(60, 1_200) : steamChecks; // 20 a second, far above any real sign-in rate
    }

    static RateLimitProperties disabled() {
        return new RateLimitProperties(false, 100_000, null, null, null, null);
    }
}
