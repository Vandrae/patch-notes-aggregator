package com.vandrae.patchnotes.catalog;

import com.vandrae.patchnotes.catalog.internal.Game;
import com.vandrae.patchnotes.catalog.internal.GameRepository;
import com.vandrae.patchnotes.catalog.internal.GameMetadataStore;
import com.vandrae.patchnotes.externalapi.SteamApiException;
import com.vandrae.patchnotes.externalapi.SteamChartEntry;
import com.vandrae.patchnotes.externalapi.SteamRateLimitedException;
import com.vandrae.patchnotes.externalapi.SteamStoreClient;
import com.vandrae.patchnotes.externalapi.SteamStoreItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The details job against a faked Steam. Fixture games use app ids 700,000,000-709,999,999; every other game in the
 * shared test database is stamped as already fetched first, so a run only ever touches these.
 */
@SpringBootTest
@ActiveProfiles("test")
class CatalogMetadataServiceTest {

    private static final long BASE_ID = 700_000_000L;
    private static final long POPULAR_NO_REVIEWS = BASE_ID + 1;   // like Valve's Deadlock: huge player count, zero reviews
    private static final long REVIEWED = BASE_ID + 2;             // plenty of reviews, not on the chart
    private static final long NO_STORE_PAGE = BASE_ID + 3;        // delisted: Steam returns nothing for it

    @Autowired CatalogMetadataService metadata;
    @Autowired GameRepository games;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SteamStoreClient steam;

    @BeforeEach
    void fixtures() {
        when(steam.isConfigured()).thenReturn(true);
        jdbc.update("DELETE FROM game WHERE steam_app_id BETWEEN ? AND ?", BASE_ID, BASE_ID + 9_999_999);
        for (long id : List.of(POPULAR_NO_REVIEWS, REVIEWED, NO_STORE_PAGE)) {
            games.save(new Game("Fixture " + id, id, SourceType.STEAM_NEWS));
        }
        // everything else in the shared database counts as already done, so runs only see the fixtures
        jdbc.update("UPDATE game SET metadata_synced_at = CURRENT_TIMESTAMP WHERE steam_app_id IS NULL OR steam_app_id NOT BETWEEN ? AND ?",
                BASE_ID, BASE_ID + 9_999_999);
        when(steam.getMostPlayedGames()).thenReturn(List.of(new SteamChartEntry(POPULAR_NO_REVIEWS, 100_000)));
        when(steam.getStoreItems(anyCollection())).thenAnswer(invocation -> storeAnswer(invocation.getArgument(0)));
    }

    private static Map<Long, SteamStoreItem> storeAnswer(Collection<Long> ids) {
        Map<Long, SteamStoreItem> answer = new HashMap<>();
        if (ids.contains(POPULAR_NO_REVIEWS)) {
            answer.put(POPULAR_NO_REVIEWS, new SteamStoreItem(POPULAR_NO_REVIEWS, "Early development.", "steam/apps/1/a/capsule_231x87.jpg?t=1", POPULAR_NO_REVIEWS + "/" + "a".repeat(40) + ".jpg", 0, 0, null, List.of(9L)));
        }
        if (ids.contains(REVIEWED)) {
            answer.put(REVIEWED, new SteamStoreItem(REVIEWED, "<b>Gather</b> &amp; build.  <br>In a   big world.", "steam/apps/2/b/capsule_231x87.jpg?t=2", null, 500, 8, 91, List.of(19L, 122L, 3859L)));
        }
        return answer; // NO_STORE_PAGE is deliberately absent
    }

    private Map<String, Object> row(long appId) {
        return jdbc.queryForMap("SELECT short_description, image_path, icon_path, review_count, peak_players, popularity, metadata_synced_at "
                + "FROM game WHERE steam_app_id = ?", appId);
    }

    @Test
    void storesDescriptionImageAndAPopularityThatCombinesReviewsWithChartPlayers() {
        MetadataResult result = metadata.tryEnrich().orElseThrow();

        assertThat(result.processed()).isEqualTo(3);
        assertThat(result.withDetails()).isEqualTo(2);
        assertThat(result.failedBatches()).isZero();

        var reviewed = row(REVIEWED);
        assertThat(reviewed.get("short_description")).isEqualTo("Gather & build. In a big world."); // markup + entities + spacing cleaned
        assertThat(reviewed.get("image_path")).isEqualTo("steam/apps/2/b/capsule_231x87.jpg?t=2");
        assertThat(((Number) reviewed.get("popularity")).longValue()).isEqualTo(500);

        // zero reviews, but 100,000 players on the chart: 0 + 10 * 100,000
        var popular = row(POPULAR_NO_REVIEWS);
        assertThat(((Number) popular.get("review_count")).intValue()).isZero();
        assertThat(((Number) popular.get("peak_players")).intValue()).isEqualTo(100_000);
        assertThat(((Number) popular.get("popularity")).longValue()).isEqualTo(1_000_000);
        assertThat(((Number) popular.get("popularity")).longValue()).isGreaterThan(((Number) reviewed.get("popularity")).longValue());
    }

    @Test
    void storesTheReviewRatingAndOnlyTheStandardGenresAmongTheTags() {
        metadata.tryEnrich();

        var reviewed = jdbc.queryForMap("SELECT review_score, percent_positive FROM game WHERE steam_app_id = ?", REVIEWED);
        assertThat(((Number) reviewed.get("review_score")).intValue()).isEqualTo(8);
        assertThat(((Number) reviewed.get("percent_positive")).intValue()).isEqualTo(91);
        assertThat(genres(REVIEWED)).containsExactlyInAnyOrder("ACTION", "RPG"); // 3859 is a free-form tag, not a genre

        var popular = jdbc.queryForMap("SELECT review_score, percent_positive FROM game WHERE steam_app_id = ?", POPULAR_NO_REVIEWS);
        assertThat(((Number) popular.get("review_score")).intValue()).isZero();   // no reviews yet: no rating
        assertThat(popular.get("percent_positive")).isNull();
        assertThat(genres(POPULAR_NO_REVIEWS)).containsExactly("STRATEGY");
        assertThat(genres(NO_STORE_PAGE)).isEmpty();
    }

    @Test
    void aRefreshReplacesGenresInsteadOfAccumulatingThem() {
        metadata.tryEnrich();
        jdbc.update("UPDATE game SET metadata_synced_at = NULL WHERE steam_app_id = ?", REVIEWED);
        when(steam.getStoreItems(anyCollection())).thenReturn(Map.of(REVIEWED,
                new SteamStoreItem(REVIEWED, "x", null, null, 10, 6, 72, List.of(122L, 701L))));

        metadata.tryEnrich();

        assertThat(genres(REVIEWED)).containsExactlyInAnyOrder("RPG", "SPORTS"); // ACTION was dropped by Steam
    }

    private List<String> genres(long appId) {
        return jdbc.queryForList("SELECT genre FROM game_genre gg JOIN game g ON g.id = gg.game_id WHERE g.steam_app_id = ?",
                String.class, appId);
    }

    @Test
    void storesTheSquareIconPathAndLeavesItEmptyForGamesWithoutOne() {
        metadata.tryEnrich();

        assertThat(row(POPULAR_NO_REVIEWS).get("icon_path")).isEqualTo(POPULAR_NO_REVIEWS + "/" + "a".repeat(40) + ".jpg");
        assertThat(row(REVIEWED).get("icon_path")).isNull();       // the store gave this one no icon
        assertThat(row(NO_STORE_PAGE).get("icon_path")).isNull();  // and this one has no store page at all
    }

    @Test
    void aGameWithoutAStorePageIsStampedSoItIsNotRequestedForever() {
        metadata.tryEnrich();

        var noPage = row(NO_STORE_PAGE);
        assertThat(noPage.get("short_description")).isNull();
        assertThat(noPage.get("image_path")).isNull();
        assertThat(noPage.get("metadata_synced_at")).isNotNull();
        assertThat(metadata.pending()).isZero();
    }

    @Test
    void theNextRunOnlyFetchesGamesThatChangedOnSteam() {
        metadata.tryEnrich();
        when(steam.getStoreItems(anyCollection())).thenAnswer(invocation -> storeAnswer(invocation.getArgument(0)));
        // what the catalog sync does when Steam reports a game as modified:
        jdbc.update("UPDATE game SET metadata_synced_at = NULL WHERE steam_app_id = ?", REVIEWED);

        metadata.tryEnrich();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> requested = ArgumentCaptor.forClass(Collection.class);
        verify(steam, atLeastOnce()).getStoreItems(requested.capture());
        assertThat(requested.getAllValues().getLast()).containsExactly(REVIEWED);
    }

    @Test
    void gamesThatLeaveTheChartFallBackToTheirReviewCount() {
        metadata.tryEnrich();
        when(steam.getMostPlayedGames()).thenReturn(List.of()); // the game dropped out of the top 100

        metadata.tryEnrich();

        var popular = row(POPULAR_NO_REVIEWS);
        assertThat(((Number) popular.get("peak_players")).intValue()).isZero();
        assertThat(((Number) popular.get("popularity")).longValue()).isZero();
    }

    @Test
    void aFailedBatchLeavesItsGamesPendingAndTheNextRunFinishesThem() {
        when(steam.getStoreItems(anyCollection())).thenThrow(new SteamApiException("Steam store items request failed (HTTP 502)", null));

        MetadataResult failed = metadata.tryEnrich().orElseThrow();

        assertThat(failed.failedBatches()).isEqualTo(1);
        assertThat(failed.processed()).isZero();
        assertThat(metadata.pending()).isEqualTo(3);                         // nothing was marked as done

        // doAnswer(...).when(...) because when(mock.call()) would invoke the previous stub, which throws
        doAnswer(invocation -> storeAnswer(invocation.getArgument(0))).when(steam).getStoreItems(anyCollection());
        MetadataResult retry = metadata.tryEnrich().orElseThrow();

        assertThat(retry.processed()).isEqualTo(3);
        assertThat(metadata.pending()).isZero();
    }

    // ------------------------------------------------------------------- Steam's rate limit

    @Test
    void whenSteamSaysTooManyRequestsItWaitsAndRetriesTheSameBatchInsteadOfSkippingIt() {
        List<Collection<Long>> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            requested.add(List.copyOf(ids));
            if (calls.incrementAndGet() <= 2) {
                throw new SteamRateLimitedException("Steam store items request was rate limited (HTTP 429)");
            }
            return storeAnswer(ids);
        }).when(steam).getStoreItems(anyCollection());

        MetadataResult result = metadata.tryEnrich().orElseThrow();

        assertThat(result.rateLimited()).isEqualTo(2);
        assertThat(result.failedBatches()).isZero();        // being throttled is not a failure
        assertThat(result.processed()).isEqualTo(3);        // nothing was lost
        assertThat(requested).hasSize(3);
        assertThat(requested.get(1)).containsExactlyInAnyOrderElementsOf(requested.get(0)); // the SAME games, asked again
        assertThat(requested.get(2)).containsExactlyInAnyOrderElementsOf(requested.get(0));
        assertThat(metadata.pending()).isZero();
    }

    @Test
    void givesUpAfterBeingThrottledTooManyTimesInARowAndKeepsItsProgress() {
        doAnswer(invocation -> {
            throw new SteamRateLimitedException("Steam store items request was rate limited (HTTP 429)");
        }).when(steam).getStoreItems(anyCollection());

        assertThatThrownBy(() -> metadata.tryEnrich())
                .isInstanceOf(SteamApiException.class)
                .hasMessageContaining("throttled")
                .hasMessageContaining("next run resumes");

        assertThat(metadata.isEnriching()).as("the lock is released after giving up").isFalse();
        assertThat(metadata.pending()).isEqualTo(3);
    }

    @Test
    void fetchesTheMostUsefulGamesFirst_chartGamesThenRecentlyUpdatedThenTheRest(@Autowired GameMetadataStore store) {
        jdbc.update("UPDATE game SET steam_last_modified = 100 WHERE steam_app_id = ?", NO_STORE_PAGE);   // oldest
        jdbc.update("UPDATE game SET steam_last_modified = 300 WHERE steam_app_id = ?", REVIEWED);        // recently updated
        jdbc.update("UPDATE game SET steam_last_modified = 50, peak_players = 100000 WHERE steam_app_id = ?", POPULAR_NO_REVIEWS); // on the chart

        var order = store.nextTargets(java.time.Instant.now().minus(java.time.Duration.ofDays(30)), 10); // the job's own staleness window

        assertThat(order).extracting(GameMetadataStore.Target::steamAppId)
                .containsExactly(POPULAR_NO_REVIEWS, REVIEWED, NO_STORE_PAGE);
    }

    @Test
    void rankingStillWorksWhenTheChartIsUnavailable() {
        when(steam.getMostPlayedGames()).thenThrow(new SteamApiException("Steam most played chart request failed (HTTP 503)", null));

        MetadataResult result = metadata.tryEnrich().orElseThrow();

        assertThat(result.processed()).isEqualTo(3);                          // details still fetched
        assertThat(((Number) row(REVIEWED).get("popularity")).longValue()).isEqualTo(500); // review-only popularity
    }

    @Test
    void reportsHowManyGamesHaveDetails() {
        long before = metadata.detailsLoaded();

        metadata.tryEnrich();

        assertThat(metadata.detailsLoaded()).isEqualTo(before + 3);
        assertThat(metadata.isEnriching()).isFalse();
    }

    // -------------------------------------------------------------- description cleaning

    @Test
    void cleansDescriptions() {
        assertThat(CatalogMetadataService.cleanDescription(null)).isNull();
        assertThat(CatalogMetadataService.cleanDescription("   ")).isNull();
        assertThat(CatalogMetadataService.cleanDescription("<p>Hello <i>there</i></p>")).isEqualTo("Hello there");
        assertThat(CatalogMetadataService.cleanDescription("Fish &amp; chips &quot;deluxe&quot;")).isEqualTo("Fish & chips \"deluxe\"");
        assertThat(CatalogMetadataService.cleanDescription("<script>alert(1)</script>Safe")).isEqualTo("Safe"); // scripts are dropped, not shown
    }

    @Test
    void cutsLongDescriptionsOnAWordBoundary() {
        String cleaned = CatalogMetadataService.cleanDescription("word ".repeat(200));

        assertThat(cleaned).endsWith("…");
        assertThat(cleaned.length()).isLessThanOrEqualTo(CatalogMetadataService.DESCRIPTION_MAX_CHARS + 1);
        assertThat(cleaned).matches("(?s)(word )*word…");
    }
}
