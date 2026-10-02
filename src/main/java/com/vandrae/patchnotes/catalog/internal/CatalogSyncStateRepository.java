package com.vandrae.patchnotes.catalog.internal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogSyncStateRepository extends JpaRepository<CatalogSyncState, Integer> {
}
