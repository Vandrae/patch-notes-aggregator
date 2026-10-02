package com.vandrae.patchnotes.fetch.internal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollScheduleTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Duration MIN = Duration.ofMinutes(15);
    private static final Duration MAX = Duration.ofHours(24);

    private static FetchProperties.Schedule config(double jitter) {
        return new FetchProperties.Schedule(MIN, MAX, 4, jitter, Duration.ofMinutes(5), Duration.ofHours(6));
    }

    /** No jitter, so the arithmetic is exact. */
    private final PollSchedule exact = new PollSchedule(config(0), () -> 0.0);

    private Duration waitAfterSuccess(PollSchedule schedule, Duration sincePatch) {
        return Duration.between(NOW, schedule.afterSuccess(NOW, NOW.minus(sincePatch)));
    }

    // ------------------------------------------------------------------ after a successful poll

    @Test
    void aQuarterOfTheTimeSinceTheLastPatchNoteIsTheWait() {
        assertThat(waitAfterSuccess(exact, Duration.ofHours(2))).isEqualTo(Duration.ofMinutes(30));
        assertThat(waitAfterSuccess(exact, Duration.ofDays(1))).isEqualTo(Duration.ofHours(6));
        assertThat(waitAfterSuccess(exact, Duration.ofDays(2))).isEqualTo(Duration.ofHours(12));
    }

    @Test
    void aFreshPatchNoteGivesTheShortestWaitSoAGameThatWasQuietSnapsBackToFrequentChecks() {
        assertThat(waitAfterSuccess(exact, Duration.ZERO)).isEqualTo(MIN);
        assertThat(waitAfterSuccess(exact, Duration.ofMinutes(20))).isEqualTo(MIN);
        // a patch dated in the future (clock skew, a scheduled post) must not produce a negative wait
        assertThat(waitAfterSuccess(exact, Duration.ofHours(-5))).isEqualTo(MIN);
    }

    @Test
    void quietGamesAreCheckedOnceADayAtMost() {
        assertThat(waitAfterSuccess(exact, Duration.ofDays(4))).isEqualTo(MAX);
        assertThat(waitAfterSuccess(exact, Duration.ofDays(30))).isEqualTo(MAX);
        assertThat(waitAfterSuccess(exact, Duration.ofDays(3650))).isEqualTo(MAX);
    }

    @Test
    void aGameWithNoPatchNotesAtAllGoesStraightToTheCap() {
        assertThat(Duration.between(NOW, exact.afterSuccess(NOW, null))).isEqualTo(MAX);
    }

    @Test
    void jitterOnlyEverShortensTheWaitSoTheCapIsAPromiseAndTheFloorHolds() {
        PollSchedule jittery = new PollSchedule(config(0.1), new Random(7)::nextDouble);
        for (int days = 0; days <= 400; days++) {
            for (int minutes : new int[]{0, 7, 59}) {
                Duration wait = waitAfterSuccess(jittery, Duration.ofDays(days).plusMinutes(minutes));
                assertThat(wait).isLessThanOrEqualTo(MAX).isGreaterThanOrEqualTo(MIN);
            }
        }
        // and it really does spread games out: a thousand quiet games do not all land on the same instant
        long distinct = new Random(1).doubles(1000).mapToObj(r -> new PollSchedule(config(0.1), () -> r))
                .map(s -> s.afterSuccess(NOW, null)).distinct().count();
        assertThat(distinct).isGreaterThan(900);
    }

    @Test
    void jitterNeverGoesBelowTheShortestWait() {
        PollSchedule maxJitter = new PollSchedule(config(0.9), () -> 0.999999);
        assertThat(waitAfterSuccess(maxJitter, Duration.ZERO)).isEqualTo(MIN);
    }

    // ------------------------------------------------------------------ after a failed poll

    @Test
    void failuresBackOffByDoublingUpToTheLimit() {
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, 1))).isEqualTo(Duration.ofMinutes(5));
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, 2))).isEqualTo(Duration.ofMinutes(10));
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, 3))).isEqualTo(Duration.ofMinutes(20));
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, 6))).isEqualTo(Duration.ofMinutes(160));
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, 7))).isEqualTo(Duration.ofHours(5).plusMinutes(20));
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, 8))).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void aHugeNumberOfFailuresStaysAtTheLimitInsteadOfOverflowing() {
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, 1_000))).isEqualTo(Duration.ofHours(6));
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, Integer.MAX_VALUE))).isEqualTo(Duration.ofHours(6));
        assertThat(Duration.between(NOW, exact.afterFailure(NOW, 0))).isEqualTo(Duration.ofMinutes(5)); // treated as the first
    }

    @Test
    void failureWaitsAreAlsoSpreadButNeverBelowTheFirstBackoff() {
        PollSchedule maxJitter = new PollSchedule(config(0.5), () -> 0.999999);
        assertThat(Duration.between(NOW, maxJitter.afterFailure(NOW, 1))).isEqualTo(Duration.ofMinutes(5));
        assertThat(Duration.between(NOW, maxJitter.afterFailure(NOW, 8))).isBetween(Duration.ofHours(3), Duration.ofHours(6));
    }

    // ------------------------------------------------------------------ other

    @Test
    void aGameWithNothingToPollIsLookedAtAgainAtTheCap() {
        assertThat(Duration.between(NOW, exact.afterNothingToPoll(NOW))).isEqualTo(MAX);
    }

    @Test
    void theCapComesFromOneMethodSoASlowerTierForDormantGamesCanBeAddedLater() {
        // what a future "dormant" tier would look like: no patch note for a year means a week between checks
        PollSchedule tiered = new PollSchedule(config(0), () -> 0.0) {
            @Override
            Duration maxIntervalFor(Instant latestPatchAt, Instant now) {
                boolean dormant = latestPatchAt == null || latestPatchAt.isBefore(now.minus(Duration.ofDays(365)));
                return dormant ? Duration.ofDays(7) : super.maxIntervalFor(latestPatchAt, now);
            }
        };

        assertThat(Duration.between(NOW, tiered.afterSuccess(NOW, null))).isEqualTo(Duration.ofDays(7));
        assertThat(waitAfterSuccess(tiered, Duration.ofDays(500))).isEqualTo(Duration.ofDays(7));
        assertThat(waitAfterSuccess(tiered, Duration.ofDays(200))).isEqualTo(MAX); // not dormant: still daily
        assertThat(waitAfterSuccess(tiered, Duration.ofHours(2))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void settingsThatMakeNoSenseAreRejectedAtStartup() {
        assertThatThrownBy(() -> new FetchProperties.Schedule(MAX, MIN, 4, 0.1, MIN, MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FetchProperties.Schedule(MIN, MAX, 0, 0.1, MIN, MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FetchProperties.Pacing(0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
