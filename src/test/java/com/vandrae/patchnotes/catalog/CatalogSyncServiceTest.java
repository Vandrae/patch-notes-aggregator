package com.vandrae.patchnotes.catalog;

import com.vandrae.patchnotes.catalog.internal.CatalogSyncStateRepository;
import com.vandrae.patchnotes.externalapi.SteamApiException;
import com.vandrae.patchnotes.externalapi.SteamApp;
import com.vandrae.patchnotes.externalapi.SteamAppPage;
import com.vandrae.patchnotes.externalapi.SteamStoreClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sync against a faked Steam. Fixture games use app ids >= 900,000,000 so they never collide with other tests. */
@SpringBootTest
@ActiveProfiles("test")
class CatalogSyncServiceTest {

    private static final long BASE_ID = 900_000_000L;

    @Autowired CatalogSyncService sync;
    @Autowired CatalogService catalog;
    @Autowired CatalogSyncStateRepository stateRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SteamStoreClient steam;

    @BeforeEach
    void cleanSlate() {
        when(steam.isConfigured()).thenReturn(true);
        stateRepository.deleteAll();
        // only this test's own range: other tests keep fixtures of their own (some with watchers) at higher ids
        jdbc.update("DELETE FROM game WHERE steam_app_id BETWEEN ? AND ?", BASE_ID, BASE_ID + 999_999);
    }

    private static SteamApp app(long offset, String name, long modified) {
        return new SteamApp(BASE_ID + offset, name, modified);
    }

    private Integer fixtureCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM game WHERE steam_app_id BETWEEN ? AND ?", Integer.class, BASE_ID, BASE_ID + 999_999);
    }

    // ------------------------------------------------------------------ first import

    @Test
    void theFirstRunImportsEverythingAndCleansUpJunk() {
        when(steam.getAppList(eq(0L), isNull())).thenReturn(new SteamAppPage(List.of(
                app(1, "  Alpha Quest  ", 100),          // stray whitespace, as seen in real Steam data
                app(2, "", 100),                          // blank names exist in the real list
                app(3, "   ", 100),
                app(4, "Beta™ Blast", 100)), true, BASE_ID + 4));
        when(steam.getAppList(eq(BASE_ID + 4), isNull())).thenReturn(new SteamAppPage(List.of(
                app(5, "Gamma", 100),
                app(5, "Gamma Final", 100)), false, BASE_ID + 5)); // the same app twice in one page

        SyncResult result = sync.trySync().orElseThrow();

        assertThat(result.full()).isTrue();
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(3);   // Alpha, Beta, Gamma
        assertThat(result.skipped()).isEqualTo(3);    // two blanks + the repeated Gamma
        assertThat(fixtureCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT name FROM game WHERE steam_app_id = ?", String.class, BASE_ID + 1)).isEqualTo("Alpha Quest");
        assertThat(jdbc.queryForObject("SELECT name FROM game WHERE steam_app_id = ?", String.class, BASE_ID + 5)).isEqualTo("Gamma Final");
        // the display name keeps the trademark sign; the searchable form doesn't
        assertThat(jdbc.queryForObject("SELECT name FROM game WHERE steam_app_id = ?", String.class, BASE_ID + 4)).isEqualTo("Beta™ Blast");
        assertThat(jdbc.queryForObject("SELECT name_search FROM game WHERE steam_app_id = ?", String.class, BASE_ID + 4)).isEqualTo("beta blast");
        assertThat(jdbc.queryForObject("SELECT source_type FROM game WHERE steam_app_id = ?", String.class, BASE_ID + 1)).isEqualTo("STEAM_NEWS");
    }

    @Test
    void theNewGamesAreSearchableImmediately() {
        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(List.of(
                app(10, "Zyxel Odyssey®", 5)), false, BASE_ID + 10));

        sync.trySync();

        assertThat(catalog.search("zyxel odyssey", 0, 10).getContent()).extracting(GameSummary::name).containsExactly("Zyxel Odyssey®");
    }

    // ------------------------------------------------------------- later runs

    @Test
    void laterRunsAreIncrementalAndAskSteamOnlyForWhatChanged() {
        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(List.of(app(20, "Delta", 1)), false, BASE_ID + 20));
        assertThat(sync.trySync().orElseThrow().full()).isTrue();
        Instant firstStarted = stateRepository.findAll().getFirst().getLastSyncStartedAt();

        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(List.of(), false, 0));
        SyncResult second = sync.trySync().orElseThrow();

        assertThat(second.full()).isFalse();
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(steam, org.mockito.Mockito.atLeastOnce()).getAppList(anyLong(), since.capture());
        assertThat(since.getAllValues().getLast()).isEqualTo(firstStarted);
    }

    @Test
    void runningAgainWithTheSameDataChangesNothing() {
        List<SteamApp> apps = List.of(app(30, "Epsilon", 1), app(31, "Zeta", 1));
        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(apps, false, BASE_ID + 31));
        sync.trySync();

        SyncResult again = sync.trySync().orElseThrow();

        assertThat(again.inserted()).isZero();
        assertThat(again.updated()).isZero();
        assertThat(again.unchanged()).isEqualTo(2);
        assertThat(fixtureCount()).isEqualTo(2);
    }

    @Test
    void renamesAndNewTimestampsUpdateTheExistingRow() {
        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(List.of(app(40, "Eta Online", 1)), false, BASE_ID + 40));
        sync.trySync();
        Long idBefore = jdbc.queryForObject("SELECT id FROM game WHERE steam_app_id = ?", Long.class, BASE_ID + 40);

        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(List.of(app(40, "Eta Online: Reborn", 2)), false, BASE_ID + 40));
        SyncResult result = sync.trySync().orElseThrow();

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.inserted()).isZero();
        // same row (so watchlists and articles pointing at it survive a rename), found under the new name only
        assertThat(jdbc.queryForObject("SELECT id FROM game WHERE steam_app_id = ?", Long.class, BASE_ID + 40)).isEqualTo(idBefore);
        assertThat(catalog.search("eta online reborn", 0, 10).getContent()).hasSize(1);
    }

    // ------------------------------------------------------------- failure and overlap

    @Test
    void aFailureHalfwayKeepsTheProgressAndTheNextRunFinishesTheJob() {
        when(steam.getAppList(eq(0L), isNull())).thenReturn(new SteamAppPage(List.of(app(50, "Theta"), app(51, "Iota")), true, BASE_ID + 51));
        when(steam.getAppList(eq(BASE_ID + 51), isNull())).thenThrow(new SteamApiException("Steam app list request failed (HTTP 502)", null));

        assertThatThrownBy(() -> sync.trySync()).isInstanceOf(SteamApiException.class);

        assertThat(fixtureCount()).isEqualTo(2);                           // page 1 was kept
        assertThat(sync.status().lastFullSyncAt()).isNull();               // but the import is not marked complete
        assertThat(sync.isSyncing()).isFalse();                            // and the lock was released

        when(steam.getAppList(eq(BASE_ID + 51), isNull())).thenReturn(new SteamAppPage(List.of(app(52, "Kappa")), false, BASE_ID + 52));
        SyncResult retry = sync.trySync().orElseThrow();

        assertThat(retry.full()).isTrue();                                 // still a full import, since the last one never finished
        assertThat(retry.unchanged()).isEqualTo(2);                        // page 1 was recognised, not duplicated
        assertThat(retry.inserted()).isEqualTo(1);
        assertThat(fixtureCount()).isEqualTo(3);
        assertThat(sync.status().lastFullSyncAt()).isNotNull();
    }

    @Test
    void onlyOneSyncRunsAtATime() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(steam.getAppList(anyLong(), any())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new SteamAppPage(List.of(app(60, "Lambda")), false, BASE_ID + 60);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<SyncResult>> first = executor.submit(() -> sync.trySync());
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(sync.isSyncing()).isTrue();
            assertThat(sync.status().syncing()).isTrue();
            assertThat(sync.trySync()).as("a second request while one runs").isEmpty();

            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(sync.isSyncing()).isFalse();
    }

    @Test
    void reportsHowManyGamesAreSearchable() {
        long before = sync.status().games();
        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(List.of(app(70, "Mu"), app(71, "Nu")), false, BASE_ID + 71));

        sync.trySync();

        assertThat(sync.status().games()).isEqualTo(before + 2);
    }

    private static SteamApp app(long offset, String name) {
        return app(offset, name, 1);
    }
}
