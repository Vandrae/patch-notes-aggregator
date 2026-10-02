package com.vandrae.patchnotes.catalog;

import com.vandrae.patchnotes.catalog.internal.CatalogSyncState;
import com.vandrae.patchnotes.catalog.internal.CatalogSyncStateRepository;
import com.vandrae.patchnotes.catalog.internal.GameBulkWriter;
import com.vandrae.patchnotes.catalog.internal.GameBulkWriter.Existing;
import com.vandrae.patchnotes.catalog.internal.GameBulkWriter.Row;
import com.vandrae.patchnotes.catalog.internal.GameRepository;
import com.vandrae.patchnotes.catalog.internal.NameSearch;
import com.vandrae.patchnotes.externalapi.SteamApp;
import com.vandrae.patchnotes.externalapi.SteamAppPage;
import com.vandrae.patchnotes.externalapi.SteamStoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the catalog in step with Steam's list of games.
 *
 * <p>The first run (or any run before a full one has completed) imports everything; later runs only ask Steam for
 * what changed since the last one started. Work is committed in chunks, so a failure halfway keeps what was already
 * imported, and because every step is an upsert keyed on the Steam app id, simply running again finishes the job.
 */
@Service
public class CatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);
    private static final int CHUNK = 1000;

    private final SteamStoreClient steam;
    private final GameBulkWriter writer;
    private final GameRepository games;
    private final CatalogSyncStateRepository stateRepository;
    private final CatalogMetadataService metadata;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean(false);

    CatalogSyncService(SteamStoreClient steam, GameBulkWriter writer, GameRepository games,
                       CatalogSyncStateRepository stateRepository, CatalogMetadataService metadata,
                       PlatformTransactionManager transactionManager) {
        this.steam = steam;
        this.writer = writer;
        this.games = games;
        this.stateRepository = stateRepository;
        this.metadata = metadata;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public boolean isConfigured() {
        return steam.isConfigured();
    }

    public boolean isSyncing() {
        return running.get();
    }

    public CatalogStatus status() {
        Instant lastFull = stateRepository.findById(CatalogSyncState.ROW_ID)
                .map(CatalogSyncState::getLastFullSyncAt).orElse(null);
        return new CatalogStatus(games.count(), running.get(), lastFull, metadata.detailsLoaded(), metadata.isEnriching());
    }

    /** Runs a sync unless one is already in progress (then returns empty). */
    public Optional<SyncResult> trySync() {
        if (!running.compareAndSet(false, true)) {
            log.info("Catalog sync requested but one is already running");
            return Optional.empty();
        }
        try {
            return Optional.of(sync());
        } finally {
            running.set(false);
        }
    }

    private SyncResult sync() {
        Instant startedAt = Instant.now();
        CatalogSyncState state = stateRepository.findById(CatalogSyncState.ROW_ID).orElseGet(CatalogSyncState::initial);
        boolean full = state.getLastFullSyncAt() == null;
        Instant since = full ? null : state.getLastSyncStartedAt();
        log.info("Catalog sync starting ({})", full ? "full import" : "changes since " + since);

        int pages = 0;
        Counts counts = new Counts();
        long lastAppId = 0;
        boolean more = true;
        while (more) {
            SteamAppPage page = steam.getAppList(lastAppId, since);
            pages++;
            processPage(page.apps(), counts);
            log.info("Catalog sync page {}: {} apps (inserted so far {}, updated {})",
                    pages, page.apps().size(), counts.inserted, counts.updated);
            more = page.hasMore() && page.lastAppId() > lastAppId; // the second check guards against a stuck cursor
            lastAppId = page.lastAppId();
        }

        state.recordSuccess(startedAt, full);
        stateRepository.save(state);
        SyncResult result = new SyncResult(full, pages, counts.inserted, counts.updated, counts.unchanged, counts.skipped,
                Duration.between(startedAt, Instant.now()));
        log.info("Catalog sync finished in {} s: {}", result.took().toSeconds(), result);
        return result;
    }

    private void processPage(List<SteamApp> apps, Counts counts) {
        // clean the page: trim names, drop blanks, and keep one entry per app id (the last one wins)
        Map<Long, Row> cleaned = new LinkedHashMap<>();
        for (SteamApp app : apps) {
            String name = app.name() == null ? "" : app.name().strip();
            String normalized = NameSearch.normalize(name);
            if (name.isEmpty() || normalized.isEmpty()) {
                counts.skipped++;
                continue;
            }
            if (cleaned.put(app.appId(), new Row(app.appId(), truncate(name), normalized, app.lastModified())) != null) {
                counts.skipped++;
            }
        }

        List<Row> rows = new ArrayList<>(cleaned.values());
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<Row> chunk = rows.subList(from, Math.min(from + CHUNK, rows.size()));
            Map<Long, Existing> existing = writer.findExisting(chunk.stream().map(Row::steamAppId).toList());

            List<Row> toInsert = new ArrayList<>();
            List<Row> toUpdate = new ArrayList<>();
            for (Row row : chunk) {
                Existing current = existing.get(row.steamAppId());
                if (current == null) {
                    toInsert.add(row);
                } else if (!row.name().equals(current.name()) || !row.nameSearch().equals(current.nameSearch())
                        || current.steamLastModified() == null || current.steamLastModified() != row.steamLastModified()) {
                    toUpdate.add(row);
                } else {
                    counts.unchanged++;
                }
            }
            tx.executeWithoutResult(status -> {
                writer.insert(toInsert);
                writer.update(toUpdate);
            });
            counts.inserted += toInsert.size();
            counts.updated += toUpdate.size();
        }
    }

    private static String truncate(String name) {
        return name.length() <= 255 ? name : name.substring(0, 255);
    }

    private static final class Counts {
        int inserted;
        int updated;
        int unchanged;
        int skipped;
    }
}
