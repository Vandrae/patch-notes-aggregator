package com.vandrae.patchnotes.catalog;

import com.vandrae.patchnotes.externalapi.SteamStoreClient;
import com.vandrae.patchnotes.externalapi.SteamStoreItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Steam allows roughly one details request every three seconds, so the job must space its requests and never run two
 * at once. This uses its own database and a 150 ms spacing so the behaviour is visible without a real three-second wait.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pacing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.catalog.metadata.min-request-interval=150ms"})
@ActiveProfiles("test")
class CatalogMetadataPacingTest {

    private static final long BASE_ID = 5_000_000L;
    private static final int GAMES = 450; // 3 requests of up to 200

    @Autowired CatalogMetadataService metadata;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SteamStoreClient steam;

    @Test
    void sendsOneRequestAtATimeWithTheConfiguredSpacingBetweenThem() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.batchUpdate("INSERT INTO game (name, name_search, steam_app_id, source_type, created_at) VALUES (?, ?, ?, 'STEAM_NEWS', ?)",
                IntStream.range(0, GAMES).boxed().toList(), GAMES, (ps, i) -> {
                    ps.setString(1, "Paced " + i);
                    ps.setString(2, "paced " + i);
                    ps.setLong(3, BASE_ID + i);
                    ps.setObject(4, now);
                });
        jdbc.update("UPDATE game SET metadata_synced_at = CURRENT_TIMESTAMP WHERE steam_app_id < ?", BASE_ID); // e.g. the seeded game

        when(steam.isConfigured()).thenReturn(true);
        when(steam.getMostPlayedGames()).thenReturn(List.of());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        List<Long> startTimes = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(steam.getStoreItems(anyCollection())).thenAnswer(invocation -> {
            startTimes.add(System.nanoTime());
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Collection<Long> ids = invocation.getArgument(0);
                assertThat(ids.size()).isLessThanOrEqualTo(SteamStoreClient.MAX_ITEMS_PER_REQUEST);
                Map<Long, SteamStoreItem> answer = new HashMap<>();
                ids.forEach(id -> answer.put(id, new SteamStoreItem(id, "d", null, null, 1)));
                return answer;
            } finally {
                inFlight.decrementAndGet();
            }
        });

        long started = System.nanoTime();
        MetadataResult result = metadata.tryEnrich().orElseThrow();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result.processed()).isEqualTo(GAMES);
        assertThat(startTimes).hasSize(3);                                  // 450 games, 200 per request
        assertThat(maxInFlight.get()).as("never two requests at once").isEqualTo(1);
        for (int i = 1; i < startTimes.size(); i++) {
            Duration gap = Duration.ofNanos(startTimes.get(i) - startTimes.get(i - 1));
            assertThat(gap).as("gap before request " + (i + 1)).isGreaterThanOrEqualTo(Duration.ofMillis(140));
        }
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(290));    // two gaps of 150 ms
    }
}
