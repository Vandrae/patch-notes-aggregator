package com.vandrae.patchnotes.catalog;

import com.vandrae.patchnotes.externalapi.SteamApp;
import com.vandrae.patchnotes.externalapi.SteamAppPage;
import com.vandrae.patchnotes.externalapi.SteamChartEntry;
import com.vandrae.patchnotes.externalapi.SteamStoreItem;
import com.vandrae.patchnotes.externalapi.SteamStoreClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Real-world scale: the Steam catalog is ~150,000 games across three 50,000-row pages. This imports a synthetic one
 * of that size into its own in-memory database (so it can't slow down or pollute any other test) and checks that
 * importing it, re-importing it, and searching it all stay fast. The time limits are deliberately generous: they
 * catch a regression to something like a table scan per game or one insert per round trip, not normal variation.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:scale;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class CatalogScaleTest {

    /** Steam tag ids of the ten genres, handed out round-robin so every genre has ~15,000 games. */
    private static final List<Long> GENRE_TAGS = List.of(19L, 21L, 597L, 492L, 128L, 699L, 122L, 599L, 701L, 9L);
    /** Steam age-rating codes handed out round-robin: a fifth of the games each. */
    private static final List<String> AGE_CODES = List.of("e", "e10", "t", "m", "ao");
    private static final int PAGE = 50_000;
    private static final int PAGES = 3;

    @Autowired CatalogSyncService sync;
    @Autowired CatalogService catalog;
    @Autowired CatalogMetadataService metadata;
    @MockitoBean SteamStoreClient steam;

    private static SteamAppPage page(int pageIndex) {
        List<SteamApp> apps = new ArrayList<>(PAGE);
        IntStream.range(0, PAGE).forEach(i -> {
            int n = pageIndex * PAGE + i + 1_000;
            apps.add(new SteamApp(n, "Synthetic Game %06d".formatted(n), 1_700_000_000L));
        });
        boolean more = pageIndex < PAGES - 1;
        return new SteamAppPage(apps, more, apps.getLast().appId());
    }

    @Test
    void importsReimportsAndSearches150kGamesQuickly() {
        when(steam.isConfigured()).thenReturn(true);
        when(steam.getAppList(anyLong(), any())).thenAnswer(invocation -> {
            long cursor = invocation.getArgument(0);
            int pageIndex = cursor == 0 ? 0 : (int) ((cursor - 1_000) / PAGE) + 1;
            return page(pageIndex);
        });

        long startedImport = System.nanoTime();
        SyncResult first = sync.trySync().orElseThrow();
        Duration importTime = Duration.ofNanos(System.nanoTime() - startedImport);

        assertThat(first.inserted()).isEqualTo(PAGE * PAGES);
        assertThat(sync.status().games()).isGreaterThanOrEqualTo(PAGE * PAGES);
        System.out.printf("SCALE: imported %,d games in %d ms%n", first.inserted(), importTime.toMillis());
        assertThat(importTime).as("import of 150k games").isLessThan(Duration.ofSeconds(60));

        // a second full pass over identical data must be a no-op (and quick: this is the nightly path)
        long startedAgain = System.nanoTime();
        SyncResult again = sync.trySync().orElseThrow();
        Duration againTime = Duration.ofNanos(System.nanoTime() - startedAgain);
        assertThat(again.inserted()).isZero();
        assertThat(again.updated()).isZero();
        assertThat(again.unchanged()).isEqualTo(PAGE * PAGES);
        System.out.printf("SCALE: re-sync of %,d unchanged games in %d ms%n", again.unchanged(), againTime.toMillis());
        assertThat(againTime).as("no-op re-sync").isLessThan(Duration.ofSeconds(60));

        // searching: an exact name, a prefix, and a contains-match that has to look at every row
        assertSearch("synthetic game 075000", 1, Duration.ofMillis(1500));
        assertSearch("synthetic game 0750", 100, Duration.ofMillis(1500));
        assertSearch("ame 07500", 10, Duration.ofSeconds(3));

        // ---- details (description, cover image, popularity) for every game, 200 per request, a few requests at once
        when(steam.getMostPlayedGames()).thenReturn(List.of(new SteamChartEntry(1_000 + 75_000, 250_000)));
        when(steam.getStoreItems(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            Map<Long, SteamStoreItem> answer = new HashMap<>();
            for (long id : ids) {
                answer.put(id, new SteamStoreItem(id, "A description of game " + id,
                        "steam/apps/%d/abc/capsule_231x87.jpg?t=1".formatted(id),
                        "%d/%040x.jpg".formatted(id, id), (int) (id % 1000),
                        (int) (id % 10), (int) (id % 10) == 0 ? null : 50 + (int) (id % 10) * 5,
                        List.of(GENRE_TAGS.get((int) (id % GENRE_TAGS.size()))), AGE_CODES.get((int) (id % AGE_CODES.size()))));
            }
            return answer;
        });
        long startedDetails = System.nanoTime();
        MetadataResult details = metadata.tryEnrich().orElseThrow();
        Duration detailsTime = Duration.ofNanos(System.nanoTime() - startedDetails);
        System.out.printf("SCALE: details for %,d games in %d ms%n", details.processed(), detailsTime.toMillis());
        assertThat(details.processed()).isGreaterThanOrEqualTo(PAGE * PAGES);
        assertThat(details.failedBatches()).isZero();
        assertThat(metadata.pending()).isZero();
        assertThat(detailsTime).as("details for 150k games").isLessThan(Duration.ofSeconds(60));

        // ---- worst-case searches: every game matches, so all 150k rows must be ordered by relevance then popularity
        var broad = timed("search \"synthetic\" (matches all 150,000, sorted by popularity)", () -> catalog.search("synthetic", 0, 20));
        assertThat(broad.page().getTotalElements()).isEqualTo(PAGE * PAGES);
        assertThat(broad.took()).as("broad search").isLessThan(Duration.ofSeconds(4));
        // the game on the chart (250,000 players * 10) outranks every game that only has reviews
        assertThat(broad.page().getContent().getFirst().name()).isEqualTo("Synthetic Game %06d".formatted(1_000 + 75_000));
        assertThat(broad.page().getContent().getFirst().imageUrl()).startsWith("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/");
        assertThat(broad.page().getContent().getFirst().shortDescription()).startsWith("A description of game ");

        var browse = timed("browse with no query (all 150,000, sorted by popularity)", () -> catalog.search("", 0, 20));
        assertThat(browse.took()).as("browse").isLessThan(Duration.ofSeconds(4));
        assertThat(browse.page().getContent().getFirst().name()).isEqualTo("Synthetic Game %06d".formatted(1_000 + 75_000));

        // ---- the same worst cases with genre and rating filters on (each genre has 15,000 games; level 9 is every tenth id)
        var rpg = timed("browse, genre RPG", () -> catalog.search("", new GameFilter(java.util.Set.of(Genre.RPG), 0, null), 0, 20));
        assertThat(rpg.page().getTotalElements()).isEqualTo(PAGE * PAGES / 10);
        assertThat(rpg.took()).as("browse by genre").isLessThan(Duration.ofSeconds(4));

        var rated = timed("search \"synthetic\", rating 9+", () -> catalog.search("synthetic", new GameFilter(null, 9, null), 0, 20));
        assertThat(rated.page().getTotalElements()).isEqualTo(PAGE * PAGES / 10);
        assertThat(rated.page().getContent()).allSatisfy(g -> assertThat(g.rating().score()).isEqualTo(9));
        assertThat(rated.took()).as("search by rating").isLessThan(Duration.ofSeconds(4));

        var teen = timed("browse, age TEEN or MATURE", () -> catalog.search("",
                new GameFilter(null, 0, java.util.Set.of(AgeRating.TEEN, AgeRating.MATURE)), 0, 20));
        assertThat(teen.page().getTotalElements()).isEqualTo(PAGE * PAGES / 5 * 2);
        assertThat(teen.took()).as("browse by age rating").isLessThan(Duration.ofSeconds(4));

        var both = timed("search \"synthetic\", genres RPG+RACING, rating 5+",
                () -> catalog.search("synthetic", new GameFilter(java.util.Set.of(Genre.RPG, Genre.RACING), 5, null), 0, 20));
        assertThat(both.page().getTotalElements()).isEqualTo(PAGE * PAGES / 5); // RPG is level 6, RACING level 5: both pass
        assertThat(both.took()).as("search by genre and rating").isLessThan(Duration.ofSeconds(4));
    }

    private record Timed(org.springframework.data.domain.Page<GameSummary> page, Duration took) {
    }

    private Timed timed(String label, java.util.function.Supplier<org.springframework.data.domain.Page<GameSummary>> search) {
        long started = System.nanoTime();
        var page = search.get();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        System.out.printf("SCALE: %s -> %d ms%n", label, took.toMillis());
        return new Timed(page, took);
    }

    private void assertSearch(String query, long expectedTotal, Duration limit) {
        long started = System.nanoTime();
        var page = catalog.search(query, 0, 20);
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        System.out.printf("SCALE: search \"%s\" -> %d results in %d ms%n", query, page.getTotalElements(), took.toMillis());
        assertThat(page.getTotalElements()).as(query).isEqualTo(expectedTotal);
        assertThat(took).as("search \"" + query + "\"").isLessThan(limit);
    }
}
