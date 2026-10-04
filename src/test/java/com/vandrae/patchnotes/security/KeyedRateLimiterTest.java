package com.vandrae.patchnotes.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyedRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicInteger rejections = new AtomicInteger();

    private void passTime(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    /** burst tokens, refilled at perMinute per minute, on a clock the test controls. */
    private KeyedRateLimiter limiter(int burst, int perMinute, int maxKeys) {
        return new KeyedRateLimiter("test", burst, perMinute, maxKeys, nanos::get, rejections::incrementAndGet);
    }

    @Test
    void aBurstIsAllowedAndThenRequestsAreRefusedUntilATokenComesBack() {
        var limiter = limiter(3, 60, 1_000); // 3 at once, then one a second

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a").allowed()).as("request %d", i + 1).isTrue();
        }
        var refused = limiter.tryAcquire("a");

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isBetween(Duration.ofMillis(900), Duration.ofMillis(1_100));
        passTime(Duration.ofMillis(1_050));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse(); // only one token had come back
    }

    @Test
    void theWaitReportedIsHowLongUntilARequestWouldSucceed() {
        var limiter = limiter(1, 6, 1_000); // one every ten seconds
        limiter.tryAcquire("a");

        passTime(Duration.ofSeconds(4));
        var refused = limiter.tryAcquire("a");

        assertThat(refused.retryAfter()).isBetween(Duration.ofMillis(5_900), Duration.ofMillis(6_100));
        passTime(refused.retryAfter());
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void anIdleBucketOnlyRefillsToTheBurstNotBeyond() {
        var limiter = limiter(3, 60, 1_000);
        limiter.tryAcquire("a");
        passTime(Duration.ofHours(5));

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("a").allowed()) allowed++;
        }

        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void everyKeyHasItsOwnBucket() {
        var limiter = limiter(1, 1, 1_000);

        assertThat(limiter.tryAcquire("203.0.113.1").allowed()).isTrue();
        assertThat(limiter.tryAcquire("203.0.113.1").allowed()).isFalse();
        assertThat(limiter.tryAcquire("203.0.113.2").allowed()).isTrue(); // somebody else is unaffected
    }

    @Test
    void theLongRunRateIsHeldToTheConfiguredRate() {
        var limiter = limiter(5, 60, 1_000); // one a second, burst 5
        int allowed = 0;

        for (int i = 0; i < 600; i++) { // ten requests a second for a minute
            passTime(Duration.ofMillis(100));
            if (limiter.tryAcquire("a").allowed()) allowed++;
        }

        assertThat(allowed).isBetween(60, 66); // 60 refilled in the minute plus the burst's few unspent tokens
        assertThat(rejections.get()).isEqualTo(600 - allowed);
    }

    @Test
    void memoryIsBoundedEvenWhenEveryRequestComesFromANewKey() {
        var limiter = limiter(3, 60, 100);

        int allowed = 0;
        for (int i = 0; i < 10_000; i++) {
            if (limiter.tryAcquire("random-" + i).allowed()) allowed++;
        }

        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(101); // the cap, plus the one shared overflow bucket
        // keys beyond the cap share one bucket, so the flood is limited too instead of each key getting a fresh allowance
        assertThat(allowed).isLessThan(500);
    }

    @Test
    void bucketsThatHaveFullyRefilledAreForgottenSoTheMapShrinksAgain() {
        var limiter = limiter(3, 60, 100);
        for (int i = 0; i < 80; i++) {
            limiter.tryAcquire("client-" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(80);

        passTime(Duration.ofMinutes(10)); // all of them are full again, so they carry no information
        for (int i = 0; i < 30; i++) {
            limiter.tryAcquire("newcomer-" + i); // crossing half the cap triggers the clean-up
        }

        assertThat(limiter.trackedKeys()).isLessThan(60);
    }

    @Test
    void aRefusalIsCountedForTheMetricsButAllowedRequestsAreNot() {
        var limiter = limiter(2, 60, 1_000);

        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(rejections.get()).isZero();
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");

        assertThat(rejections.get()).isEqualTo(2);
    }

    @Test
    void nonsenseSettingsAreRejectedAtStartup() {
        assertThatThrownBy(() -> limiter(0, 60, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(5, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter(5, 60, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manyThreadsNeverGetMoreThanTheBurstFromOneKey() throws Exception {
        var limiter = limiter(50, 1, 1_000); // the clock does not move, so exactly 50 tokens exist
        var allowed = new AtomicInteger();
        var threads = new Thread[16];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    if (limiter.tryAcquire("shared").allowed()) allowed.incrementAndGet();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();

        assertThat(allowed.get()).isEqualTo(50);
    }
}
