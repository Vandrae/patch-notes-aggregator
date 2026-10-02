package com.vandrae.patchnotes.fetch.internal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two places create a game's poll-schedule row: the fetch that runs right after somebody starts watching the game (inside
 * the event listener's transaction), and the poller's next tick, which adds a row for every newly watched game. On a busy
 * MySQL they can do it at the same moment, for the same game or for different new games, and several fetches can finish
 * together. This lines 300 pairs up (each pair released together by a barrier, eight threads at a time) and requires that
 * no write ever fails. Before the schedule-row writes were changed this failed in many of the 600 writes with MySQL's
 * "Deadlock found when trying to get lock". H2 serialises such writes, so only a real MySQL can show the problem.
 * Fixture app ids start at 981,000,000.
 */
@SpringBootTest(properties = "app.fetch.polling-enabled=false") // no background poller: this test is the only writer
@ActiveProfiles({"test", "mysql"})
@Testcontainers(disabledWithoutDocker = true)
class MySqlFetchStateConcurrencyTest {

    private static final long BASE_ID = 981_000_000L;
    private static final int GAMES = 300;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = MYSQL.getJdbcUrl();
        registry.add("MYSQL_URL", () -> url + (url.contains("?") ? "&" : "?") + "rewriteBatchedStatements=true");
        registry.add("MYSQL_USER", MYSQL::getUsername);
        registry.add("MYSQL_PASSWORD", MYSQL::getPassword);
    }

    @Autowired FetchStateStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void creatingTheSameScheduleRowFromTheFetchListenerAndThePollerAtOnceNeverFailsEitherOne() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < GAMES; i++) {
            String name = "Mysqlrace " + i;
            jdbc.update("INSERT INTO game (name, name_search, steam_app_id, source_type, created_at) "
                    + "VALUES (?, ?, ?, 'STEAM_NEWS', CURRENT_TIMESTAMP)", name, name.toLowerCase(), BASE_ID + i);
            ids.add(jdbc.queryForObject("SELECT id FROM game WHERE steam_app_id = ?", Long.class, BASE_ID + i));
        }
        TransactionTemplate listenerTransaction = new TransactionTemplate(transactions);
        listenerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); // as @ApplicationModuleListener does

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> work = new ArrayList<>();
        try {
            for (long id : ids) {
                CyclicBarrier together = new CyclicBarrier(2);
                // the fetch listener: records the outcome of its fetch in a transaction that commits a little later
                work.add(pool.submit(() -> {
                    together.await();
                    listenerTransaction.executeWithoutResult(status -> {
                        Instant now = Instant.now();
                        store.recordSuccess(id, now, now.minusSeconds(3 * 3600), now.plusSeconds(2700));
                        pause(2); // the rest of the listener's work before it commits
                    });
                    return null;
                }));
                // the poller's reconcile step: starts tracking a newly watched game
                work.add(pool.submit(() -> {
                    together.await();
                    store.track(Set.of(id), Instant.now());
                    return null;
                }));
            }
            List<String> failures = new ArrayList<>();
            for (Future<?> future : work) {
                try {
                    future.get(120, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failures.add((e.getCause() == null ? e : e.getCause()).toString());
                }
            }
            assertThat(failures).as("failures out of %d concurrent writes", work.size()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
        // and every game ended up with exactly one schedule row that records the fetch
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_fetch_state WHERE game_id IN (SELECT id FROM game WHERE steam_app_id >= ?)",
                Integer.class, BASE_ID)).isEqualTo(GAMES);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_fetch_state WHERE last_polled_at IS NOT NULL "
                + "AND game_id IN (SELECT id FROM game WHERE steam_app_id >= ?)", Integer.class, BASE_ID)).isEqualTo(GAMES);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
