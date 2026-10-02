package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.CatalogMetadataService;
import com.vandrae.patchnotes.catalog.CatalogSyncService;
import com.vandrae.patchnotes.externalapi.SteamApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Decides WHEN the catalog is imported and enriched: in the background right after startup (a fresh install fills
 * itself without anyone doing anything, and an interrupted run resumes), and then every night to pick up new games,
 * renames and fresh popularity.
 *
 * <p>Order matters: the game list comes first (so every game is searchable as soon as possible), then the details
 * (cover, description, popularity) fill in behind it.
 */
@Component
class CatalogSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncRunner.class);

    private final CatalogSyncService sync;
    private final CatalogMetadataService metadata;
    private final GameRepository games;
    private final CatalogProperties properties;

    CatalogSyncRunner(CatalogSyncService sync, CatalogMetadataService metadata, GameRepository games, CatalogProperties properties) {
        this.sync = sync;
        this.metadata = metadata;
        this.games = games;
        this.properties = properties;
    }

    @Async // never delay startup: the import takes a minute or two and the app is usable meanwhile
    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (!hasApiKey()) {
            return;
        }
        if (properties.sync().enabled()) {
            long count = games.count();
            if (count < properties.sync().startupThreshold()) {
                log.info("Catalog has only {} games: importing the Steam catalog in the background", count);
                syncCatalog();
            } else {
                log.info("Catalog already has {} games: leaving it to the nightly sync", count);
            }
        }
        refreshDetailsIfAnyPending();
    }

    @Scheduled(cron = "${app.catalog.sync.cron:0 30 4 * * *}")
    void nightly() {
        if (!hasApiKey()) {
            return;
        }
        if (properties.sync().enabled()) {
            syncCatalog();
        }
        refreshDetailsIfAnyPending();
    }

    private boolean hasApiKey() {
        if (!sync.isConfigured()) {
            if (properties.sync().enabled() || properties.metadata().enabled()) {
                log.warn("Catalog import skipped: no Steam API key. Set STEAM_API_KEY to import the full Steam catalog "
                        + "(only the starter games are searchable until then)");
            }
            return false;
        }
        return true;
    }

    private void refreshDetailsIfAnyPending() {
        if (!properties.metadata().enabled()) {
            return;
        }
        long pending = metadata.pending();
        if (pending == 0) {
            log.info("All games have up-to-date details");
            return;
        }
        log.info("{} games need their cover, description and popularity fetched", pending);
        guarded("Game details refresh", metadata::tryEnrich);
    }

    private void syncCatalog() {
        guarded("Catalog sync", sync::trySync);
    }

    private static void guarded(String what, Runnable task) {
        try {
            task.run();
        } catch (SteamApiException e) {
            log.error("{} failed: {}. Work already done is kept; the next run continues.", what, e.getMessage());
        } catch (RuntimeException e) {
            log.error("{} failed unexpectedly: {}", what, e.getClass().getSimpleName(), e);
        }
    }
}
