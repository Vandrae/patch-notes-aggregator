package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.SourceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The fast path for the catalog sync. Tens of thousands of games are written per run, and JPA cannot batch inserts
 * for IDENTITY ids, so the sync uses plain JDBC batches instead. Everything else goes through JPA as usual.
 */
@Component
public class GameBulkWriter {

    public record Existing(long steamAppId, String name, String nameSearch, Long steamLastModified) {
    }

    public record Row(long steamAppId, String name, String nameSearch, long steamLastModified) {
    }

    private final JdbcTemplate jdbc;

    GameBulkWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What we already have for these app ids (keep the list at most ~1000 long: it becomes an SQL IN clause). */
    public Map<Long, Existing> findExisting(Collection<Long> appIds) {
        if (appIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = appIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<Long, Existing> result = new HashMap<>();
        jdbc.query("SELECT steam_app_id, name, name_search, steam_last_modified FROM game WHERE steam_app_id IN (" + placeholders + ")",
                rs -> {
                    long appId = rs.getLong(1);
                    long modified = rs.getLong(4);
                    result.put(appId, new Existing(appId, rs.getString(2), rs.getString(3), rs.wasNull() ? null : modified));
                },
                appIds.toArray());
        return result;
    }

    public void insert(List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        // LocalDateTime in UTC: matches how Hibernate stores Instants in this app (jdbc.time_zone = UTC)
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.batchUpdate(
                "INSERT INTO game (name, name_search, steam_app_id, steam_last_modified, source_type, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                rows, rows.size(), (ps, row) -> {
                    ps.setString(1, row.name());
                    ps.setString(2, row.nameSearch());
                    ps.setLong(3, row.steamAppId());
                    ps.setLong(4, row.steamLastModified());
                    ps.setString(5, SourceType.STEAM_NEWS.name());
                    ps.setObject(6, now);
                });
    }

    public void update(List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "UPDATE game SET name = ?, name_search = ?, steam_last_modified = ?, metadata_synced_at = NULL WHERE steam_app_id = ?",
                rows, rows.size(), (ps, row) -> {
                    ps.setString(1, row.name());
                    ps.setString(2, row.nameSearch());
                    ps.setLong(3, row.steamLastModified());
                    ps.setLong(4, row.steamAppId());
                });
    }
}
