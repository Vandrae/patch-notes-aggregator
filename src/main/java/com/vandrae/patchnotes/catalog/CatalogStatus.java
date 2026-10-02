package com.vandrae.patchnotes.catalog;

import java.time.Instant;

/**
 * @param games          how many games are searchable right now
 * @param syncing        a catalog import is running, so the list is still filling up
 * @param lastFullSyncAt when a complete import last finished (null until the first one does)
 * @param detailsLoaded  how many games have had their cover, description and popularity fetched
 * @param loadingDetails that fetch is running; until it finishes, popularity ranking is only partly informed
 */
public record CatalogStatus(long games, boolean syncing, Instant lastFullSyncAt, long detailsLoaded, boolean loadingDetails) {
}
