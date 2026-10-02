/**
 * The searchable catalog of games, kept in step with Steam's full list by a background sync.
 * Public API: {@link com.vandrae.patchnotes.catalog.CatalogService} (search, lookup) and
 * {@link com.vandrae.patchnotes.catalog.CatalogSyncService} (synchronisation + status).
 */
@ApplicationModule(displayName = "Catalog", allowedDependencies = {"externalapi"})
package com.vandrae.patchnotes.catalog;

import org.springframework.modulith.ApplicationModule;
