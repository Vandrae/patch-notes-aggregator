package com.vandrae.patchnotes.fetch.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPacerTest {

    /** A clock that only moves when the code under test sleeps or the test says so. */
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final AtomicLong slept = new AtomicLong();

    private RequestPacer pacer(double requestsPerSecond) {
        return new RequestPacer(requestsPerSecond, nanos::get, d -> {
            slept.addAndGet(d.toNanos());
            nanos.addAndGet(d.toNanos());
        });
    }

    private void passTime(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    void theFirstRequestGoesImmediatelyAndTheRestAreSpacedEvenly() throws InterruptedException {
        RequestPacer pacer = pacer(5); // one every 200 ms

        for (int i = 0; i < 11; i++) {
            pacer.awaitTurn();
        }

        // 11 requests need 10 gaps of 200 ms
        assertThat(Duration.ofNanos(slept.get())).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void aCallerThatWasSlowAnywayDoesNotWaitMore() throws InterruptedException {
        RequestPacer pacer = pacer(5);
        pacer.awaitTurn();
        passTime(Duration.ofSeconds(1)); // the previous request took longer than the gap

        pacer.awaitTurn();

        assertThat(slept.get()).isZero();
    }

    @Test
    void anIdlePeriodDoesNotBuildUpCreditForALaterBurst() throws InterruptedException {
        RequestPacer pacer = pacer(5);
        pacer.awaitTurn();
        passTime(Duration.ofHours(1));

        for (int i = 0; i < 4; i++) {
            pacer.awaitTurn();
        }

        assertThat(Duration.ofNanos(slept.get())).isEqualTo(Duration.ofMillis(600)); // 3 gaps, not 0
    }

    @Test
    void slowingDownDoublesTheGapUpToSixteenTimesAndSuccessesEaseItBack() {
        RequestPacer pacer = pacer(10); // 100 ms
        assertThat(pacer.currentSpacing()).isEqualTo(Duration.ofMillis(100));

        pacer.slowDown();
        assertThat(pacer.currentSpacing()).isEqualTo(Duration.ofMillis(200));
        pacer.slowDown();
        pacer.slowDown();
        pacer.slowDown();
        pacer.slowDown();
        pacer.slowDown();
        assertThat(pacer.currentSpacing()).isEqualTo(Duration.ofMillis(1600)); // capped at 16x

        pacer.recover();
        assertThat(pacer.currentSpacing()).isEqualTo(Duration.ofMillis(1440));
        for (int i = 0; i < 100; i++) {
            pacer.recover();
        }
        assertThat(pacer.currentSpacing()).isEqualTo(Duration.ofMillis(100)); // never faster than configured
    }

    @Test
    void theSlowedDownGapIsActuallyUsed() throws InterruptedException {
        RequestPacer pacer = pacer(10);
        pacer.awaitTurn();
        pacer.slowDown(); // now 200 ms

        pacer.awaitTurn(); // still owes the old 100 ms slot
        pacer.awaitTurn(); // and this one pays the new, longer gap

        assertThat(Duration.ofNanos(slept.get())).isEqualTo(Duration.ofMillis(300));
    }
}
