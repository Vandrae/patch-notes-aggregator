package com.vandrae.patchnotes;

import com.vandrae.patchnotes.catalog.CatalogMetadataService;
import com.vandrae.patchnotes.catalog.CatalogService;
import com.vandrae.patchnotes.catalog.CatalogSyncService;
import com.vandrae.patchnotes.catalog.GameFilter;
import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.catalog.AgeRating;
import com.vandrae.patchnotes.catalog.Genre;
import com.vandrae.patchnotes.catalog.SyncResult;
import com.vandrae.patchnotes.externalapi.SteamApp;
import com.vandrae.patchnotes.externalapi.SteamAppPage;
import com.vandrae.patchnotes.externalapi.SteamChartEntry;
import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.externalapi.SteamNewsItem;
import com.vandrae.patchnotes.externalapi.SteamStoreClient;
import com.vandrae.patchnotes.externalapi.SteamStoreItem;
import com.vandrae.patchnotes.users.UserAccounts;
import com.vandrae.patchnotes.users.UserSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The application against a REAL MySQL 8.4, started in a throwaway container. Everything else in the test suite runs on H2
 * in MySQL compatibility mode, which accepts some SQL that MySQL rejects and the other way round; this class runs the same
 * kinds of operations (Flyway migrations and Hibernate's schema check, the catalog import and search, the event registry, the
 * watchlist and feed API, account deletion, and the background poller) on the database that production uses.
 *
 * <p>The profiles are the production database set-up: {@code mysql} reads MYSQL_URL / MYSQL_USER / MYSQL_PASSWORD, which the
 * container supplies. The poller is switched on with a one-second tick so that its SQL runs here too.
 *
 * <p>Skipped (not failed) when Docker is not available, so {@code ./mvnw test} still works on a machine without it; CI has
 * Docker. Fixture Steam app ids start at 980,000,000.
 */
@SpringBootTest(properties = {
        "app.fetch.polling-enabled=true",
        "app.fetch.tick=PT1S",
        "app.fetch.initial-delay=PT0S"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles({"test", "mysql"}) // later profiles win, so the MySQL settings override the test profile's H2 URL
@Testcontainers(disabledWithoutDocker = true)
class MySqlIntegrationTest {

    private static final long BASE_ID = 980_000_000L;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = MYSQL.getJdbcUrl();
        // the same driver option the production profile uses, so batched inserts become real multi-row statements
        registry.add("MYSQL_URL", () -> url + (url.contains("?") ? "&" : "?") + "rewriteBatchedStatements=true");
        registry.add("MYSQL_USER", MYSQL::getUsername);
        registry.add("MYSQL_PASSWORD", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired CatalogSyncService sync;
    @Autowired CatalogMetadataService metadata;
    @Autowired CatalogService catalog;
    @Autowired UserAccounts users;
    @Autowired MeterRegistry meters;
    @MockitoBean SteamStoreClient steam;
    @MockitoBean SteamNewsClient news;

    @BeforeEach
    void steamAnswers() {
        when(steam.isConfigured()).thenReturn(true);
        // every game has one patch note, published three hours ago
        when(news.getNewsForApp(anyLong())).thenAnswer(invocation -> {
            long appId = invocation.getArgument(0);
            return List.of(new SteamNewsItem("gid-" + appId, "1.0.1 Patch Notes", "https://example.test/news/" + appId, true,
                    "Dev", "[p]Fixed things.[/p]", "Community Announcements",
                    Instant.now().minus(Duration.ofHours(3)).getEpochSecond(), "steam_community_announcements", 1, appId,
                    List.of("patchnotes")));
        });
    }

    private long insertGame(String name, long appId) {
        jdbc.update("INSERT INTO game (name, name_search, steam_app_id, source_type, created_at) VALUES (?, ?, ?, 'STEAM_NEWS', CURRENT_TIMESTAMP)",
                name, name.toLowerCase(), appId);
        return jdbc.queryForObject("SELECT id FROM game WHERE steam_app_id = ?", Long.class, appId);
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Waits for a condition, and when it never comes true says what the database looked like. CI shows a failed test's
     * message but not its logs, so the message has to carry the evidence.
     */
    private void eventually(Duration timeout, ThrowingRunnable condition) {
        try {
            await().atMost(timeout).untilAsserted(condition);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(e.getMessage() + databaseState(), e);
        }
    }

    private String databaseState() {
        String nl = System.lineSeparator();
        return nl + "--- article (game_id, external_id): " + jdbc.queryForList("SELECT game_id, external_id FROM article ORDER BY id")
                + nl + "--- event_publication: " + jdbc.queryForList("SELECT SUBSTRING(event_type, 40) AS type, status, completion_attempts, "
                + "completion_date, SUBSTRING(serialized_event, 1, 100) AS event FROM event_publication ORDER BY publication_date")
                + nl + "--- game_fetch_state: " + jdbc.queryForList("SELECT game_id, last_polled_at, next_poll_at, consecutive_failures, "
                + "last_error FROM game_fetch_state ORDER BY game_id")
                + nl + "--- watchlist_entry: " + jdbc.queryForList("SELECT user_id, game_id FROM watchlist_entry ORDER BY id");
    }

    // ------------------------------------------------------------------ the schema

    @Test
    void everyMigrationAppliesToARealMySqlAndHibernateAcceptsTheResult() {
        // reaching this point means Spring started: Flyway ran all the migrations and Hibernate's "validate" accepted the schema
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.4");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class)).isGreaterThanOrEqualTo(12);

        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class)
                .stream().map(String::toLowerCase).toList();
        assertThat(tables).contains("app_user", "game", "game_genre", "game_fetch_state", "watchlist_entry", "article",
                "catalog_sync_state", "event_publication");
    }

    // ------------------------------------------------------------------ catalog import, details, search

    private static SteamApp app(int n, String name) {
        return new SteamApp(BASE_ID + n, name, 1_790_000_000L + n);
    }

    @Test
    void theCatalogImportsWithUnicodeNamesAndSearchRanksAndFiltersTheWayItDoesOnH2() {
        // names that stress the character set and the normalizer: a trademark sign, an accent, a 4-byte emoji, Japanese with dakuten
        List<SteamApp> apps = List.of(
                app(1, "WAR!"), app(2, "War Thunder"), app(3, "Warframe"), app(4, "Total War: Arena"),
                app(5, "Pokémon Legends: Arceus"), app(6, "Caffè 😀 Nero™"),
                app(7, "ファイナルファンタジー"), app(8, "Elden Test RPG"));
        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(apps, false, BASE_ID + 8));

        SyncResult first = sync.trySync().orElseThrow();
        assertThat(first.inserted()).isEqualTo(8);
        // running it again with identical data changes nothing; a rename is an update, not a duplicate
        assertThat(sync.trySync().orElseThrow().unchanged()).isEqualTo(8);
        List<SteamApp> renamed = new java.util.ArrayList<>(apps);
        renamed.set(2, new SteamApp(BASE_ID + 3, "Warframe: Renamed", 1_790_000_999L));
        when(steam.getAppList(anyLong(), any())).thenReturn(new SteamAppPage(renamed, false, BASE_ID + 8));
        SyncResult third = sync.trySync().orElseThrow();
        assertThat(third.updated()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game WHERE steam_app_id BETWEEN ? AND ?", Integer.class, BASE_ID, BASE_ID + 100)).isEqualTo(8);

        // names survive the round trip, and the searchable form is the normalized one
        assertThat(jdbc.queryForObject("SELECT name FROM game WHERE steam_app_id = ?", String.class, BASE_ID + 6)).isEqualTo("Caffè 😀 Nero™");
        assertThat(catalog.search("caffe nero", 0, 10).getContent()).extracting(GameSummary::name).containsExactly("Caffè 😀 Nero™");
        assertThat(catalog.search("pokemon", 0, 10).getContent()).extracting(GameSummary::name).containsExactly("Pokémon Legends: Arceus");
        assertThat(catalog.search("ファイナル", 0, 10).getTotalElements()).isEqualTo(1);

        // details: reviews, ratings, genres (Steam tag ids), age ratings, and the most-played chart
        when(steam.getMostPlayedGames()).thenReturn(List.of(new SteamChartEntry(BASE_ID + 2, 150_000)));
        when(steam.getStoreItems(anyCollection())).thenAnswer(invocation -> {
            Map<Long, SteamStoreItem> answer = new HashMap<>();
            Set<Long> asked = Set.copyOf(invocation.<java.util.Collection<Long>>getArgument(0));
            if (asked.contains(BASE_ID + 2)) answer.put(BASE_ID + 2, item(BASE_ID + 2, 1_500_000, 6, 71, List.of(19L, 128L), "t"));
            if (asked.contains(BASE_ID + 3)) answer.put(BASE_ID + 3, item(BASE_ID + 3, 1_300_000, 8, 90, List.of(19L), "m"));
            if (asked.contains(BASE_ID + 4)) answer.put(BASE_ID + 4, item(BASE_ID + 4, 500, 5, 55, List.of(9L), null));
            if (asked.contains(BASE_ID + 8)) answer.put(BASE_ID + 8, item(BASE_ID + 8, 800_000, 9, 96, List.of(122L, 19L), "m"));
            return answer;
        });
        metadata.tryEnrich().orElseThrow();

        // "war": War Thunder and Warframe (popular, start with the query) beat the obscure exact match "WAR!", which beats a
        // "contains" match with few reviews: the blended relevance + log-popularity ranking, computed by MySQL
        assertThat(catalog.search("war", 0, 10).getContent()).extracting(GameSummary::name)
                .containsExactly("War Thunder", "Warframe: Renamed", "WAR!", "Total War: Arena");

        // genre, rating and age filters (the EXISTS subquery over game_genre, and IN over the age column); most popular first,
        // so Warframe (1.3 million reviews) comes before Elden Test RPG (800 thousand)
        assertThat(names(new GameFilter(Set.of(Genre.RPG), 0, Set.of()))).containsExactly("Elden Test RPG");
        assertThat(names(new GameFilter(Set.of(Genre.ACTION), 8, Set.of()))).containsExactly("Warframe: Renamed", "Elden Test RPG");
        assertThat(names(new GameFilter(Set.of(), 0, Set.of(AgeRating.TEEN)))).containsExactly("War Thunder");
        assertThat(names(new GameFilter(Set.of(Genre.ACTION), 6, Set.of(AgeRating.MATURE)))).containsExactly("Warframe: Renamed", "Elden Test RPG");
        assertThat(names(new GameFilter(Set.of(Genre.STRATEGY), 9, Set.of()))).isEmpty();
        assertThat(catalog.search("war", new GameFilter(Set.of(Genre.MASSIVELY_MULTIPLAYER), 0, Set.of()), 0, 10).getContent())
                .extracting(GameSummary::name).containsExactly("War Thunder");
    }

    private List<String> names(GameFilter filter) {
        return catalog.search("", filter, 0, 50).getContent().stream()
                .filter(g -> g.steamAppId() != null && g.steamAppId() > BASE_ID && g.steamAppId() < BASE_ID + 100)
                .map(GameSummary::name).toList();
    }

    private static SteamStoreItem item(long appId, int reviews, int score, int percent, List<Long> tags, String age) {
        return new SteamStoreItem(appId, "A description.", null, null, reviews, score, percent, tags, age);
    }

    // ------------------------------------------------------------------ the HTTP API, events and account deletion

    private RequestPostProcessor signedIn(UserSummary user) {
        return jwt().jwt(token -> token.subject(Long.toString(user.id())));
    }

    @Test
    void watchingFetchesThroughTheEventRegistryAndTheFeedFiltersAndDeletingTheAccountCleansUp() throws Exception {
        long action = insertGame("Mysqltest Action", BASE_ID + 201);
        long strategy = insertGame("Mysqltest Strategy", BASE_ID + 202);
        jdbc.update("UPDATE game SET review_score = 9, percent_positive = 96, age_rating = 'MATURE' WHERE id = ?", action);
        jdbc.update("UPDATE game SET review_score = 6, percent_positive = 74, age_rating = 'TEEN' WHERE id = ?", strategy);
        jdbc.update("INSERT INTO game_genre (game_id, genre) VALUES (?, 'ACTION'), (?, 'RPG'), (?, 'STRATEGY')", action, action, strategy);
        UserSummary user = users.findOrCreateBySteamId(76561198500000001L, "MySQL tester", null);
        RequestPostProcessor me = signedIn(user);

        mvc.perform(put("/api/watchlist/{id}", action).with(me).with(csrf())).andExpect(status().isCreated());
        mvc.perform(put("/api/watchlist/{id}", strategy).with(me).with(csrf())).andExpect(status().isCreated());
        mvc.perform(put("/api/watchlist/{id}", strategy).with(me).with(csrf())).andExpect(status().isNoContent()); // idempotent

        // watching publishes GameWatched into the persisted registry; its listener fetches each game's patch note
        eventually(Duration.ofSeconds(30), () ->
                mvc.perform(get("/api/feed").with(me)).andExpect(jsonPath("$.totalItems").value(2)));
        eventually(Duration.ofSeconds(30), () ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL", Integer.class)).isZero());

        mvc.perform(get("/api/feed").param("genre", "RPG").with(me)).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].gameId").value(action));
        mvc.perform(get("/api/feed").param("minRating", "9").with(me)).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/feed").param("age", "TEEN").with(me)).andExpect(jsonPath("$.items[0].gameId").value(strategy));
        mvc.perform(get("/api/feed").param("genre", "STRATEGY").param("minRating", "9").with(me))
                .andExpect(jsonPath("$.emptyState.reason").value("NO_MATCHING_GAMES"));
        mvc.perform(get("/api/watchlist").with(me)).andExpect(jsonPath("$.length()").value(2));

        // deleting the account removes the watchlist with it, without foreign-key trouble on MySQL
        mvc.perform(delete("/api/me").with(me).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM watchlist_entry WHERE user_id = ?", Integer.class, user.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id = ?", Integer.class, user.id())).isZero();
    }

    // ------------------------------------------------------------------ many watchers at once

    @Test
    void watchingManyGamesWhileThePollerRunsNeverLeavesAFailedEvent() throws Exception {
        // Starting to watch a game publishes an event whose listener fetches it and writes its poll schedule row, while the
        // poller (ticking every second here) adds schedule rows for newly watched games, so a burst of watches has many
        // writers on the same table at once. The poller would paper over a failed fetch by fetching the game itself, so the
        // check is on the events: none may end up failed. (MySqlFetchStateConcurrencyTest provokes the underlying race on
        // purpose; this one checks the whole path under load, and once failed with a deadlock before that race was fixed.)
        int games = 60;
        UserSummary user = users.findOrCreateBySteamId(76561198500000003L, "MySQL burst tester", null);
        RequestPostProcessor me = signedIn(user);
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < games; i++) {
            ids.add(insertGame("Mysqltest Burst " + i, BASE_ID + 400 + i));
        }
        String idList = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

        for (long id : ids) {
            mvc.perform(put("/api/watchlist/{id}", id).with(me).with(csrf())).andExpect(status().isCreated());
        }

        eventually(Duration.ofSeconds(60), () -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM article WHERE game_id IN (" + idList + ")", Integer.class)).isEqualTo(games));
        eventually(Duration.ofSeconds(60), () -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_publication WHERE status <> 'COMPLETED' OR completion_date IS NULL", Integer.class)).isZero());
    }

    // ------------------------------------------------------------------ the adaptive poller

    @Test
    void thePollerTracksPollsReschedulesAndForgetsGamesUsingMySqlsDialect() {
        long first = insertGame("Mysqltest Poll A", BASE_ID + 301);
        long second = insertGame("Mysqltest Poll B", BASE_ID + 302);
        UserSummary user = users.findOrCreateBySteamId(76561198500000002L, "MySQL poll tester", null);
        // straight into the table, so only the poller (not the "watch" event) can start tracking these games
        jdbc.update("INSERT INTO watchlist_entry (user_id, game_id, added_at) VALUES (?, ?, ?)", user.id(), first, utcNow());
        jdbc.update("INSERT INTO watchlist_entry (user_id, game_id, added_at) VALUES (?, ?, ?)", user.id(), second, utcNow());

        // the tick adds a schedule row for each, polls them, and sets the next time from the age of their newest patch note
        eventually(Duration.ofSeconds(30), () -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_fetch_state WHERE game_id IN (?, ?) AND last_polled_at IS NOT NULL "
                    + "AND next_poll_at > ? AND consecutive_failures = 0", Integer.class, first, second, utcNow())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE game_id IN (?, ?)", Integer.class, first, second)).isEqualTo(2);
        });
        // a patch note three hours old means "look again in about 45 minutes", far sooner than the daily cap
        LocalDateTime next = jdbc.queryForObject("SELECT next_poll_at FROM game_fetch_state WHERE game_id = ?", LocalDateTime.class, first);
        assertThat(Duration.between(utcNow(), next)).isBetween(Duration.ofMinutes(30), Duration.ofMinutes(46));

        // stop watching one: the next tick forgets its schedule row and leaves the other alone
        jdbc.update("DELETE FROM watchlist_entry WHERE user_id = ? AND game_id = ?", user.id(), second);
        eventually(Duration.ofSeconds(30), () -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_fetch_state WHERE game_id = ?", Integer.class, second)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_fetch_state WHERE game_id = ?", Integer.class, first)).isEqualTo(1);
        });

        // the metrics read their numbers with MySQL queries too
        assertThat(meters.get("patchnotes.fetch.tracked").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("patchnotes.fetch.due").gauge().value()).isGreaterThanOrEqualTo(0);
        assertThat(meters.get("patchnotes.fetch.lag.seconds").gauge().value()).isGreaterThanOrEqualTo(0);
    }
}
