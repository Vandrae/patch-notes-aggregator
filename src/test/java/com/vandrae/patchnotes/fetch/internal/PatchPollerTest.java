package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.externalapi.SteamApiException;
import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.externalapi.SteamRateLimitedException;
import com.vandrae.patchnotes.users.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One tick of the poller against the real schedule store and fetch service, with a clock the test moves and a stubbed
 * watchlist (so other tests' watchers in the shared database cannot interfere). Fixture app ids start at 971,000,000.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PatchPollerTest.TestClock.class)
class PatchPollerTest {

    private static final long BASE_ID = 971_000_000L;
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    static final class MutableClock extends Clock {
        private volatile Instant now = NOW;

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired FetchStateStore store;
    @Autowired ArticleFetchService fetcher;
    @Autowired FetchProperties properties;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SteamNewsClient steam;

    private final WatchlistService watchlist = mock(WatchlistService.class);
    private final RequestPacer pacer = new RequestPacer(1_000, System::nanoTime, d -> { /* no real waiting in tests */ });
    private long[] game; // game[1]..game[5]

    @BeforeEach
    void fixtures() {
        clock.set(NOW);
        jdbc.update("DELETE FROM article WHERE game_id IN (SELECT id FROM game WHERE name LIKE 'Pollertest %')");
        jdbc.update("DELETE FROM game WHERE name LIKE 'Pollertest %'");
        game = new long[6];
        for (int n = 1; n <= 5; n++) {
            String name = "Pollertest " + n;
            jdbc.update("INSERT INTO game (name, name_search, steam_app_id, source_type, created_at) "
                    + "VALUES (?, ?, ?, 'STEAM_NEWS', CURRENT_TIMESTAMP)", name, name.toLowerCase(), appId(n));
            game[n] = jdbc.queryForObject("SELECT id FROM game WHERE name = ?", Long.class, name);
        }
    }

    private static long appId(int n) {
        return BASE_ID + n;
    }

    private PatchPoller poller() {
        return poller(properties.batchSize());
    }

    private PatchPoller poller(int batchSize) {
        var bounded = new FetchProperties(true, properties.tick(), properties.initialDelay(), batchSize,
                properties.schedule(), properties.pacing());
        return new PatchPoller(watchlist, store, fetcher, pacer, bounded, clock);
    }

    private void watching(int... games) {
        when(watchlist.distinctWatchedGameIds()).thenReturn(java.util.Arrays.stream(games).mapToObj(n -> game[n]).toList());
    }

    private void due(int n, Duration offsetFromNow) {
        store.track(Set.of(game[n]), NOW.plus(offsetFromNow));
    }

    private Instant nextPollAt(int n) {
        return jdbc.queryForObject("SELECT next_poll_at FROM game_fetch_state WHERE game_id = ?", Timestamp.class, game[n])
                .toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------

    @Test
    void newlyWatchedGamesAreTrackedAndPolledOnceThenLeftAloneUntilTheirTimeComes() {
        watching(1, 2);

        var first = poller().pollDueGames();
        var second = poller().pollDueGames();

        assertThat(first.polled()).isEqualTo(2);
        assertThat(second.due()).isZero();
        verify(steam, times(1)).getNewsForApp(appId(1));
        verify(steam, times(1)).getNewsForApp(appId(2));
        assertThat(store.trackedGameIds()).containsExactlyInAnyOrder(game[1], game[2]);
        assertThat(nextPollAt(1)).isAfter(NOW);
    }

    @Test
    void gamesNobodyWatchesAnyMoreAreDroppedAndNeverPolled() {
        watching(1, 2);
        poller().pollDueGames();

        watching(2);
        clock.set(NOW.plus(Duration.ofDays(2))); // both would be due by now
        poller().pollDueGames();

        assertThat(store.trackedGameIds()).containsExactly(game[2]);
        verify(steam, times(2)).getNewsForApp(appId(2));
        verify(steam, times(1)).getNewsForApp(appId(1)); // only the first time
    }

    @Test
    void onlyGamesWhoseTimeHasComeArePolled() {
        due(1, Duration.ofHours(-1));
        due(2, Duration.ofHours(1));
        watching(1, 2);

        poller().pollDueGames();

        verify(steam).getNewsForApp(appId(1));
        verify(steam, never()).getNewsForApp(appId(2));

        clock.set(NOW.plus(Duration.ofHours(2)));
        poller().pollDueGames();

        verify(steam).getNewsForApp(appId(2));
    }

    @Test
    void aTickHandlesAtMostOneBatchLongestOverdueFirstAndTheRestStayDue() {
        due(1, Duration.ofHours(-4));
        due(2, Duration.ofHours(-2));
        due(3, Duration.ofHours(-5));
        due(4, Duration.ofHours(-1));
        due(5, Duration.ofHours(-3));
        watching(1, 2, 3, 4, 5);

        var summary = poller(3).pollDueGames();

        assertThat(summary.polled()).isEqualTo(3);
        InOrder order = inOrder(steam);
        order.verify(steam).getNewsForApp(appId(3));
        order.verify(steam).getNewsForApp(appId(1));
        order.verify(steam).getNewsForApp(appId(5));
        assertThat(store.findDue(NOW, 100)).containsExactly(game[2], game[4]);
    }

    @Test
    void oneFailingGameDoesNotStopTheOthersAndBacksOffOnItsOwn() {
        due(1, Duration.ofHours(-3));
        due(2, Duration.ofHours(-2));
        due(3, Duration.ofHours(-1));
        watching(1, 2, 3);
        when(steam.getNewsForApp(appId(2))).thenThrow(new SteamApiException("Steam news request failed", null));

        var summary = poller().pollDueGames();

        assertThat(summary.polled()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        verify(steam).getNewsForApp(appId(3)); // the game after the failing one was still polled
        assertThat(store.find(game[2]).orElseThrow().consecutiveFailures()).isEqualTo(1);
        assertThat(store.find(game[1]).orElseThrow().consecutiveFailures()).isZero();
        assertThat(nextPollAt(2)).isBefore(NOW.plus(Duration.ofMinutes(6))); // retried soon, not in a day
    }

    @Test
    void whenSteamSaysSlowDownThePollerStopsKeepsTheRestDueSlowsDownAndResumesAfterThePause() {
        due(1, Duration.ofHours(-3));
        due(2, Duration.ofHours(-2));
        due(3, Duration.ofHours(-1));
        watching(1, 2, 3);
        when(steam.getNewsForApp(appId(2))).thenThrow(new SteamRateLimitedException("429"));
        PatchPoller poller = poller();
        Duration normalSpacing = pacer.currentSpacing();

        var first = poller.pollDueGames();

        assertThat(first.rateLimited()).isTrue();
        assertThat(first.polled()).isEqualTo(1);
        verify(steam, never()).getNewsForApp(appId(3));                        // not even tried
        assertThat(store.find(game[2]).orElseThrow().consecutiveFailures()).isZero(); // not blamed on the game
        assertThat(store.findDue(NOW, 100)).containsExactly(game[2], game[3]);       // both still due
        assertThat(pacer.currentSpacing()).isGreaterThan(normalSpacing);

        // during the pause nothing is sent at all
        clock.set(NOW.plusSeconds(10));
        assertThat(poller.pollDueGames().paused()).isTrue();
        verify(steam, times(1)).getNewsForApp(appId(2));

        // afterwards it picks up where it left off
        clock.set(NOW.plus(properties.pacing().rateLimitPause()).plusSeconds(1));
        doReturn(List.of()).when(steam).getNewsForApp(anyLong());
        var resumed = poller.pollDueGames();

        assertThat(resumed.paused()).isFalse();
        assertThat(resumed.polled()).isEqualTo(2);
        verify(steam).getNewsForApp(appId(3));
    }

    @Test
    void aGameJustFetchedOnDemandWhenSomebodyStartedWatchingItIsNotPolledAgainAtOnce() {
        watching(1);
        fetcher.refreshGame(game[1]); // what the GameWatched listener does

        var summary = poller().pollDueGames();

        assertThat(summary.due()).isZero();
        verify(steam, times(1)).getNewsForApp(appId(1));
    }

    @Test
    void aTickWithNothingToDoIsHarmless() {
        watching();

        var summary = poller().pollDueGames();

        assertThat(summary).isEqualTo(new PatchPoller.Summary(0, 0, 0, false, false));
        poller().tick(); // the scheduled entry point also copes
    }
}
