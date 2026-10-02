package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.externalapi.SteamApiException;
import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.externalapi.SteamNewsItem;
import com.vandrae.patchnotes.externalapi.SteamRateLimitedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * The per-game poll state: the JDBC store, the tracker that updates it, and the fetch service that calls the tracker after
 * every fetch. Fixture app ids start at 970,000,000.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FetchStateTest.FixedClock.class)
class FetchStateTest {

    private static final long BASE_ID = 970_000_000L;
    static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired FetchStateStore store;
    @Autowired PollTracker tracker;
    @Autowired ArticleFetchService fetcher;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SteamNewsClient steam;

    private long steamGame;
    private long otherGame;
    private long customGame;

    @BeforeEach
    void fixtures() {
        String mine = "(SELECT id FROM game WHERE steam_app_id >= ? AND steam_app_id < ? OR name LIKE 'Fetchstate %')";
        jdbc.update("DELETE FROM article WHERE game_id IN " + mine, BASE_ID, BASE_ID + 1_000);
        jdbc.update("DELETE FROM game_fetch_state WHERE game_id IN " + mine, BASE_ID, BASE_ID + 1_000);
        jdbc.update("DELETE FROM game WHERE steam_app_id >= ? AND steam_app_id < ? OR name LIKE 'Fetchstate %'", BASE_ID, BASE_ID + 1_000);
        steamGame = game("Fetchstate Steam", BASE_ID + 1, "STEAM_NEWS");
        otherGame = game("Fetchstate Other", BASE_ID + 2, "STEAM_NEWS");
        customGame = game("Fetchstate Custom", null, "CUSTOM");
    }

    private long game(String name, Long appId, String sourceType) {
        jdbc.update("INSERT INTO game (name, name_search, steam_app_id, source_type, created_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                name, name.toLowerCase(), appId, sourceType);
        return jdbc.queryForObject("SELECT id FROM game WHERE name = ?", Long.class, name);
    }

    private Instant nextPollAt(long gameId) {
        return jdbc.queryForObject("SELECT next_poll_at FROM game_fetch_state WHERE game_id = ?", Timestamp.class, gameId)
                .toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    private static SteamNewsItem patchNote(String gid, Instant published) {
        return new SteamNewsItem(gid, "1.2.3 Patch Notes", "https://example.test/news/" + gid, true, "Dev", "[p]Fixed things.[/p]",
                "Community Announcements", published.getEpochSecond(), "steam_community_announcements", 1, 1L, List.of("patchnotes"));
    }

    private double counter(String name, String tagKey, String tagValue) {
        var c = meters.find(name).tag(tagKey, tagValue).counter();
        return c == null ? 0 : c.count();
    }

    // ------------------------------------------------------------------ the store

    @Test
    void trackingAddsOnlyGamesThatHaveNoRowYetAndLeavesExistingScheduleAlone() {
        store.track(Set.of(steamGame), NOW.minusSeconds(100));
        store.track(Set.of(steamGame, otherGame), NOW.minusSeconds(5)); // steamGame is already tracked

        assertThat(store.trackedGameIds()).contains(steamGame, otherGame);
        assertThat(nextPollAt(steamGame)).isEqualTo(NOW.minusSeconds(100));
        assertThat(nextPollAt(otherGame)).isEqualTo(NOW.minusSeconds(5));
    }

    @Test
    void untrackingForgetsAGameAndTheCascadeCleansUpWhenAGameIsDeleted() {
        store.track(Set.of(steamGame, otherGame), NOW);

        store.untrack(Set.of(steamGame));
        jdbc.update("DELETE FROM game WHERE id = ?", otherGame);

        assertThat(store.trackedGameIds()).doesNotContain(steamGame, otherGame);
    }

    @Test
    void dueGamesComeBackLongestOverdueFirstAndNotBeforeTheirTime() {
        store.track(Set.of(steamGame), NOW.minusSeconds(10));
        store.track(Set.of(otherGame), NOW.minusSeconds(500));
        store.track(Set.of(customGame), NOW.plusSeconds(60)); // not yet

        List<Long> due = store.findDue(NOW, 1_000).stream().filter(Set.of(steamGame, otherGame, customGame)::contains).toList();

        assertThat(due).containsExactly(otherGame, steamGame);
        assertThat(store.findDue(NOW, 1)).hasSize(1); // the batch limit applies
        assertThat(store.countDue(NOW)).isGreaterThanOrEqualTo(2);
        assertThat(store.oldestOverdueSeconds(NOW)).isGreaterThanOrEqualTo(500);
    }

    @Test
    void recordingASuccessCreatesTheRowIfNeededThenUpdatesItAndClearsFailures() {
        store.recordFailure(steamGame, NOW, 3, "boom", NOW.plusSeconds(60));
        assertThat(store.find(steamGame)).get().extracting(FetchStateStore.State::consecutiveFailures).isEqualTo(3);

        store.recordSuccess(steamGame, NOW, NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofHours(12)));

        var state = store.find(steamGame).orElseThrow();
        assertThat(state.consecutiveFailures()).isZero();
        assertThat(state.latestPatchAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(nextPollAt(steamGame)).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(jdbc.queryForObject("SELECT last_error FROM game_fetch_state WHERE game_id = ?", String.class, steamGame)).isNull();
    }

    @Test
    void aVeryLongErrorMessageIsCutToFitTheColumn() {
        store.recordFailure(steamGame, NOW, 1, "x".repeat(5_000), NOW.plusSeconds(60));

        assertThat(jdbc.queryForObject("SELECT last_error FROM game_fetch_state WHERE game_id = ?", String.class, steamGame))
                .hasSize(200);
    }

    // ------------------------------------------------------------------ the tracker

    @Test
    void theNewestKnownPatchNoteIsNeverForgottenWhenALaterFetchDoesNotReachBackToIt() {
        tracker.succeeded(steamGame, NOW.minus(Duration.ofDays(1)));

        tracker.succeeded(steamGame, null);                           // a fetch that saw no patch notes
        assertThat(store.find(steamGame).orElseThrow().latestPatchAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        tracker.succeeded(steamGame, NOW.minus(Duration.ofDays(5)));  // a fetch whose newest one is older
        assertThat(store.find(steamGame).orElseThrow().latestPatchAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        tracker.succeeded(steamGame, NOW.minus(Duration.ofHours(1))); // a genuinely newer one
        assertThat(store.find(steamGame).orElseThrow().latestPatchAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));
    }

    @Test
    void consecutiveFailuresBackOffFurtherEachTime() {
        tracker.failed(steamGame, new SteamApiException("down", null));
        Duration first = Duration.between(NOW, nextPollAt(steamGame));
        tracker.failed(steamGame, new SteamApiException("down", null));
        Duration second = Duration.between(NOW, nextPollAt(steamGame));

        assertThat(store.find(steamGame).orElseThrow().consecutiveFailures()).isEqualTo(2);
        assertThat(first).isBetween(Duration.ofSeconds(270), Duration.ofMinutes(5));    // 5 min, shortened by up to 10%
        assertThat(second).isBetween(Duration.ofSeconds(540), Duration.ofMinutes(10));  // 10 min, likewise
        assertThat(jdbc.queryForObject("SELECT last_error FROM game_fetch_state WHERE game_id = ?", String.class, steamGame))
                .isEqualTo("SteamApiException: down");
    }

    // ------------------------------------------------------------------ the fetch service

    @Test
    void aFetchSchedulesTheNextCheckFromHowRecentTheNewestPatchNoteIs() {
        when(steam.getNewsForApp(anyLong())).thenReturn(List.of(patchNote("a1", NOW.minus(Duration.ofHours(2)))));
        double before = counter("patchnotes.fetch.polls", "outcome", "success");

        fetcher.refreshGame(steamGame);

        var state = store.find(steamGame).orElseThrow();
        assertThat(state.latestPatchAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        // patched two hours ago: check again in a quarter of that (30 minutes), shortened by at most 10%
        assertThat(Duration.between(NOW, nextPollAt(steamGame))).isBetween(Duration.ofMinutes(27), Duration.ofMinutes(30));
        assertThat(counter("patchnotes.fetch.polls", "outcome", "success")).isEqualTo(before + 1);
    }

    @Test
    void aQuietGameWithNothingNewIsCheckedOnlyOnceADay() {
        when(steam.getNewsForApp(anyLong())).thenReturn(List.of(patchNote("old", NOW.minus(Duration.ofDays(200)))));

        fetcher.refreshGame(steamGame);

        assertThat(Duration.between(NOW, nextPollAt(steamGame))).isBetween(Duration.ofMinutes(21 * 60 + 36), Duration.ofHours(24));
    }

    @Test
    void aGameWithNoNewsFeedIsCheckedOnlyOnceADayToo() {
        when(steam.getNewsForApp(anyLong())).thenReturn(List.of()); // Steam's 403 "no feed" comes back as an empty list

        fetcher.refreshGame(steamGame);

        assertThat(store.find(steamGame).orElseThrow().latestPatchAt()).isNull();
        assertThat(Duration.between(NOW, nextPollAt(steamGame))).isBetween(Duration.ofMinutes(21 * 60 + 36), Duration.ofHours(24));
    }

    @Test
    void aFailedFetchIsRecordedAndRetriedSoonButTheErrorStillReachesTheCaller() {
        when(steam.getNewsForApp(anyLong())).thenThrow(new SteamApiException("Steam is down", null));
        double before = counter("patchnotes.fetch.polls", "outcome", "failure");

        assertThatThrownBy(() -> fetcher.refreshGame(steamGame)).isInstanceOf(SteamApiException.class);

        assertThat(store.find(steamGame).orElseThrow().consecutiveFailures()).isEqualTo(1);
        assertThat(Duration.between(NOW, nextPollAt(steamGame))).isLessThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(counter("patchnotes.fetch.polls", "outcome", "failure")).isEqualTo(before + 1);

        // and the next success wipes the slate clean
        doReturn(List.of(patchNote("b1", NOW.minus(Duration.ofHours(1)))))
                .when(steam).getNewsForApp(anyLong()); // not when(...): calling the throwing mock to stub it would throw
        fetcher.refreshGame(steamGame);
        assertThat(store.find(steamGame).orElseThrow().consecutiveFailures()).isZero();
    }

    @Test
    void whenSteamSaysSlowDownTheGameStaysDueAndIsNotCountedAsFailing() {
        store.track(Set.of(steamGame), NOW.minusSeconds(60));
        when(steam.getNewsForApp(anyLong())).thenThrow(new SteamRateLimitedException("429"));
        double before = counter("patchnotes.fetch.polls", "outcome", "rate_limited");

        assertThatThrownBy(() -> fetcher.refreshGame(steamGame)).isInstanceOf(SteamRateLimitedException.class);

        assertThat(store.find(steamGame).orElseThrow().consecutiveFailures()).isZero();
        assertThat(nextPollAt(steamGame)).isEqualTo(NOW.minusSeconds(60)); // untouched: still due
        assertThat(counter("patchnotes.fetch.polls", "outcome", "rate_limited")).isEqualTo(before + 1);
    }

    @Test
    void aGameWithNoSourceAdapterIsNotAFailureAndIsLookedAtAgainAtTheCap() {
        fetcher.refreshGame(customGame);

        assertThat(store.find(customGame).orElseThrow().consecutiveFailures()).isZero();
        assertThat(Duration.between(NOW, nextPollAt(customGame))).isEqualTo(Duration.ofHours(24));
    }
}
