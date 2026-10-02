package com.vandrae.patchnotes.fetch.internal;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Plain-JDBC access to {@code game_fetch_state}: one row per watched game saying when it was last checked, how recent its
 * newest patch note is, and when to check it next. Statements are portable (no vendor upsert), so the same code runs on H2
 * and MySQL.
 */
@Component
class FetchStateStore {

    /** What the schedule needs to know about a game from its previous polls. */
    record State(long gameId, Instant latestPatchAt, int consecutiveFailures) {
    }

    private final JdbcTemplate jdbc;

    FetchStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Set<Long> trackedGameIds() {
        return new HashSet<>(jdbc.queryForList("SELECT game_id FROM game_fetch_state", Long.class));
    }

    /**
     * Starts tracking games that have no row yet; they are due at {@code dueAt}. Already-tracked games are left alone.
     *
     * <p>Plain inserts, one per game, each ignoring "that row already exists". The obvious single statement,
     * {@code INSERT ... SELECT ... WHERE NOT EXISTS (SELECT ... FROM the same table)}, deadlocks on MySQL: its SELECT part
     * locks the index range it reads, and a fetch that finishes at the same moment (which writes the same game's row inside
     * its own transaction) waits on that range while the insert waits on the fetch. MySQL then aborts one of them. A plain
     * insert holds no lock while it waits, so the two cannot block each other, and the loser simply finds the row there.
     */
    void track(Collection<Long> gameIds, Instant dueAt) {
        LocalDateTime due = utc(dueAt);
        for (long gameId : gameIds) {
            try {
                jdbc.update("INSERT INTO game_fetch_state (game_id, next_poll_at) VALUES (?, ?)", gameId, due);
            } catch (DuplicateKeyException alreadyTracked) {
                // a fetch that just finished created the row first: nothing left to do for this game
            }
        }
    }

    void untrack(Collection<Long> gameIds) {
        if (gameIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("DELETE FROM game_fetch_state WHERE game_id = ?", List.copyOf(gameIds), gameIds.size(),
                (ps, id) -> ps.setLong(1, id));
    }

    /** Games whose time has come, longest-overdue first. */
    List<Long> findDue(Instant now, int limit) {
        return jdbc.queryForList("SELECT game_id FROM game_fetch_state WHERE next_poll_at <= ? "
                + "ORDER BY next_poll_at, game_id LIMIT ?", Long.class, utc(now), limit);
    }

    Optional<State> find(long gameId) {
        return jdbc.query("SELECT latest_patch_at, consecutive_failures FROM game_fetch_state WHERE game_id = ?",
                (rs, i) -> {
                    Timestamp latest = rs.getTimestamp(1);
                    return new State(gameId, latest == null ? null : instant(latest), rs.getInt(2));
                }, gameId).stream().findFirst();
    }

    /** A poll worked (or there was nothing to poll): remember when, how recent the newest patch note is, and when to come back. */
    void recordSuccess(long gameId, Instant polledAt, Instant latestPatchAt, Instant nextPollAt) {
        upsert(gameId,
                "UPDATE game_fetch_state SET last_polled_at = ?, latest_patch_at = ?, next_poll_at = ?, "
                        + "consecutive_failures = 0, last_error = NULL WHERE game_id = ?",
                ps -> {
                    ps.setObject(1, utc(polledAt));
                    ps.setObject(2, latestPatchAt == null ? null : utc(latestPatchAt));
                    ps.setObject(3, utc(nextPollAt));
                    ps.setLong(4, gameId);
                },
                "INSERT INTO game_fetch_state (game_id, next_poll_at, last_polled_at, latest_patch_at) VALUES (?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, gameId);
                    ps.setObject(2, utc(nextPollAt));
                    ps.setObject(3, utc(polledAt));
                    ps.setObject(4, latestPatchAt == null ? null : utc(latestPatchAt));
                });
    }

    void recordFailure(long gameId, Instant polledAt, int consecutiveFailures, String error, Instant nextPollAt) {
        String shortError = error == null ? null : error.substring(0, Math.min(error.length(), 200));
        upsert(gameId,
                "UPDATE game_fetch_state SET last_polled_at = ?, next_poll_at = ?, consecutive_failures = ?, last_error = ? "
                        + "WHERE game_id = ?",
                ps -> {
                    ps.setObject(1, utc(polledAt));
                    ps.setObject(2, utc(nextPollAt));
                    ps.setInt(3, consecutiveFailures);
                    ps.setString(4, shortError);
                    ps.setLong(5, gameId);
                },
                "INSERT INTO game_fetch_state (game_id, next_poll_at, last_polled_at, consecutive_failures, last_error) "
                        + "VALUES (?, ?, ?, ?, ?)",
                ps -> {
                    ps.setLong(1, gameId);
                    ps.setObject(2, utc(nextPollAt));
                    ps.setObject(3, utc(polledAt));
                    ps.setInt(4, consecutiveFailures);
                    ps.setString(5, shortError);
                });
    }

    // ---- numbers for the metrics

    long countTracked() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM game_fetch_state", Long.class);
        return n == null ? 0 : n;
    }

    long countDue(Instant now) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM game_fetch_state WHERE next_poll_at <= ?", Long.class, utc(now));
        return n == null ? 0 : n;
    }

    /** How long the most overdue game has been waiting past its time; zero when nothing is due. */
    long oldestOverdueSeconds(Instant now) {
        Timestamp oldest = jdbc.queryForObject("SELECT MIN(next_poll_at) FROM game_fetch_state WHERE next_poll_at <= ?",
                Timestamp.class, utc(now));
        return oldest == null ? 0 : Math.max(0, now.getEpochSecond() - instant(oldest).getEpochSecond());
    }

    // ----

    /**
     * Update the row, or create it if the game has none yet. Two writers racing to create it end up with one row.
     *
     * <p>A row that might not exist is never updated first. On MySQL (default isolation) an UPDATE that matches nothing
     * still locks the empty index range around the missing key, so two fetches finishing at once for different new games
     * both hold that range and then each INSERT waits for the other: a deadlock, and MySQL aborts one fetch. So the cheap
     * check below (a plain read, which takes no lock) decides: a row that is there is updated, which only locks that row,
     * and one that is not is inserted straight away. If the check was stale, the insert or the update says so and the
     * other statement takes over.
     */
    private void upsert(long gameId, String update, PreparedStatementSetter updateArgs, String insert,
                        PreparedStatementSetter insertArgs) {
        if (rowExists(gameId) && jdbc.update(update, updateArgs) > 0) {
            return;
        }
        try {
            jdbc.update(insert, insertArgs);
        } catch (DuplicateKeyException raced) {
            jdbc.update(update, updateArgs);
        }
    }

    private boolean rowExists(long gameId) {
        return !jdbc.queryForList("SELECT 1 FROM game_fetch_state WHERE game_id = ?", Integer.class, gameId).isEmpty();
    }

    // LocalDateTime in UTC, matching how Hibernate stores Instants in this app (jdbc.time_zone = UTC)
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
