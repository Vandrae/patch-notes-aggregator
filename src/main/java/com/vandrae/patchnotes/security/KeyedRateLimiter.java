package com.vandrae.patchnotes.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A rate limiter with one token bucket per key (a client address, a user id, or one fixed key for a global limit).
 *
 * <p><b>Token bucket.</b> A bucket holds up to {@code burst} tokens and gets them back at a steady rate. Each request spends
 * one; with none left the request is refused until one has been refilled. That allows a short burst (someone clicking twice)
 * while holding the long-run rate to {@code perMinute}.
 *
 * <p><b>Bounded memory.</b> A bucket that has refilled completely says nothing a brand-new one would not, so idle buckets are
 * dropped. If an attacker still creates keys faster than that (random IPv6 addresses, say), new keys beyond {@code maxKeys}
 * share a single overflow bucket instead of growing the map without limit.
 *
 * <p>State is in memory, so each application instance counts for itself: right for the single-instance deployment this app
 * is built for. Several instances would need a shared store, or the limit applied in front of them.
 */
final class KeyedRateLimiter {

    /** The outcome of one request. {@code retryAfter} is how long until a token is available (zero when allowed). */
    record Decision(boolean allowed, Duration retryAfter) {
        static final Decision ALLOWED = new Decision(true, Duration.ZERO);
    }

    private static final Logger log = LoggerFactory.getLogger(KeyedRateLimiter.class);
    private static final String OVERFLOW_KEY = "\u0000overflow";
    private static final long PURGE_EVERY_NANOS = Duration.ofSeconds(1).toNanos();
    private static final long LOG_EVERY_NANOS = Duration.ofMinutes(1).toNanos();

    private static final class Bucket {
        private double tokens;
        private long lastNanos;

        Bucket(double tokens, long nanos) {
            this.tokens = tokens;
            this.lastNanos = nanos;
        }
    }

    private final String name;
    private final int burst;
    private final double tokensPerNano;
    private final int maxKeys;
    private final LongSupplier nanoTime;
    private final Runnable onRejected;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong rejectedSinceLog = new AtomicLong();
    private volatile long lastPurgeNanos;
    private volatile long lastLogNanos;

    KeyedRateLimiter(String name, int burst, int perMinute, int maxKeys, LongSupplier nanoTime, Runnable onRejected) {
        if (burst < 1 || perMinute < 1 || maxKeys < 2) {
            throw new IllegalArgumentException("rate limit '" + name + "': burst and per-minute rate must be positive");
        }
        this.name = name;
        this.burst = burst;
        this.tokensPerNano = perMinute / (60.0 * 1_000_000_000L);
        this.maxKeys = maxKeys;
        this.nanoTime = nanoTime;
        this.onRejected = onRejected;
        this.lastPurgeNanos = nanoTime.getAsLong();
        this.lastLogNanos = this.lastPurgeNanos;
    }

    Decision tryAcquire(String key) {
        long now = nanoTime.getAsLong();
        Bucket bucket = bucketFor(key, now);
        Decision decision;
        synchronized (bucket) {
            double refilled = bucket.tokens + Math.max(0, now - bucket.lastNanos) * tokensPerNano;
            bucket.tokens = Math.min(burst, refilled);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return Decision.ALLOWED;
            }
            long waitNanos = (long) Math.ceil((1.0 - bucket.tokens) / tokensPerNano);
            decision = new Decision(false, Duration.ofNanos(waitNanos));
        }
        rejected(now);
        return decision;
    }

    private Bucket bucketFor(String key, long now) {
        Bucket existing = buckets.get(key);
        if (existing != null) {
            return existing;
        }
        if (buckets.size() >= maxKeys / 2) {
            purgeIdle(now);
        }
        if (buckets.size() >= maxKeys) {
            return buckets.computeIfAbsent(OVERFLOW_KEY, k -> new Bucket(burst, now));
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(burst, now));
    }

    /** Drops buckets that have refilled completely: they are indistinguishable from a bucket that was never created. */
    private void purgeIdle(long now) {
        if (now - lastPurgeNanos < PURGE_EVERY_NANOS) {
            return;
        }
        lastPurgeNanos = now;
        buckets.entrySet().removeIf(entry -> {
            Bucket b = entry.getValue();
            synchronized (b) {
                return !entry.getKey().equals(OVERFLOW_KEY) && b.tokens + Math.max(0, now - b.lastNanos) * tokensPerNano >= burst;
            }
        });
    }

    /** Counts the refusal and logs one summary line a minute at most: no address, and no line per request. */
    private void rejected(long now) {
        rejectedSinceLog.incrementAndGet();
        onRejected.run();
        if (now - lastLogNanos >= LOG_EVERY_NANOS) {
            lastLogNanos = now;
            long count = rejectedSinceLog.getAndSet(0);
            log.warn("Rate limit '{}' refused {} request(s) since it last reported", name, count);
        }
    }

    int trackedKeys() {
        return buckets.size();
    }
}
