package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.Genre;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Plain-JDBC reads and batch writes for the metadata job (it touches up to ~190,000 rows per run). */
@Component
public class GameMetadataStore {

    public record Target(long id, long steamAppId) {
    }

    /** What was learned about one game. {@code null} description/image means the store has none. */
    public record Details(long steamAppId, String shortDescription, String imagePath, String iconPath, int reviewCount,
                          int reviewScore, Integer percentPositive, Set<Genre> genres) {
    }

    private final JdbcTemplate jdbc;

    GameMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next games whose details were never fetched or are older than {@code staleBefore}, MOST USEFUL FIRST:
     * games on the most-played chart, then the most recently updated on Steam (active games are the ones people
     * search for), then the rest. Games already done drop out of the filter, so no paging cursor is needed.
     */
    public List<Target> nextTargets(Instant staleBefore, int limit) {
        return jdbc.query(
                "SELECT id, steam_app_id FROM game WHERE steam_app_id IS NOT NULL "
                        + "AND (metadata_synced_at IS NULL OR metadata_synced_at < ?) "
                        + "ORDER BY peak_players DESC, steam_last_modified DESC, id LIMIT ?",
                (rs, i) -> new Target(rs.getLong(1), rs.getLong(2)),
                utc(staleBefore), limit);
    }

    public long countPending(Instant staleBefore) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM game WHERE steam_app_id IS NOT NULL AND (metadata_synced_at IS NULL OR metadata_synced_at < ?)",
                Long.class, utc(staleBefore));
        return n == null ? 0 : n;
    }

    public long countWithDetails() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM game WHERE metadata_synced_at IS NOT NULL", Long.class);
        return n == null ? 0 : n;
    }

    /** Stores details and stamps the games as fetched; popularity = reviews + 10 * (chart peak players, if any). */
    public void saveDetails(List<Details> rows, Instant now) {
        if (rows.isEmpty()) {
            return;
        }
        LocalDateTime stamp = utc(now);
        jdbc.batchUpdate(
                "UPDATE game SET short_description = ?, image_path = ?, icon_path = ?, review_count = ?, "
                        + "review_score = ?, percent_positive = ?, "
                        + "popularity = ? + 10 * peak_players, metadata_synced_at = ? WHERE steam_app_id = ?",
                rows, rows.size(), (ps, row) -> {
                    ps.setString(1, row.shortDescription());
                    ps.setString(2, row.imagePath());
                    ps.setString(3, row.iconPath());
                    ps.setInt(4, row.reviewCount());
                    ps.setInt(5, row.reviewScore());
                    ps.setObject(6, row.percentPositive(), Types.INTEGER);
                    ps.setLong(7, row.reviewCount());
                    ps.setObject(8, stamp);
                    ps.setLong(9, row.steamAppId());
                });
        saveGenres(rows);
    }

    /** Replaces each game's genres with what was just fetched (a genre Steam dropped must disappear). */
    private void saveGenres(List<Details> rows) {
        jdbc.batchUpdate("DELETE FROM game_genre WHERE game_id IN (SELECT id FROM game WHERE steam_app_id = ?)",
                rows, rows.size(), (ps, row) -> ps.setLong(1, row.steamAppId()));
        List<Object[]> pairs = new ArrayList<>();
        for (Details row : rows) {
            for (Genre genre : row.genres()) {
                pairs.add(new Object[]{genre.name(), row.steamAppId()});
            }
        }
        if (!pairs.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO game_genre (game_id, genre) SELECT id, ? FROM game WHERE steam_app_id = ?",
                    pairs, pairs.size(), (ps, pair) -> {
                        ps.setString(1, (String) pair[0]);
                        ps.setLong(2, (Long) pair[1]);
                    });
        }
    }

    /** Replaces the chart data: games that left the chart go back to review-only popularity. */
    public void applyChart(List<long[]> appIdAndPeak) {
        jdbc.update("UPDATE game SET peak_players = 0, popularity = review_count WHERE peak_players > 0");
        if (appIdAndPeak.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("UPDATE game SET peak_players = ?, popularity = review_count + 10 * ? WHERE steam_app_id = ?",
                appIdAndPeak, appIdAndPeak.size(), (ps, row) -> {
                    ps.setLong(1, row[1]);
                    ps.setLong(2, row[1]);
                    ps.setLong(3, row[0]);
                });
    }

    // LocalDateTime in UTC, matching how Hibernate stores Instants in this app (jdbc.time_zone = UTC)
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
