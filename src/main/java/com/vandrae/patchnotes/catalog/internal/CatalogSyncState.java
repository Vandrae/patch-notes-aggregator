package com.vandrae.patchnotes.catalog.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** The one row (id = 1) that remembers how far the catalog sync has got. */
@Entity
@Table(name = "catalog_sync_state")
public class CatalogSyncState {

    public static final int ROW_ID = 1;

    @Id
    private Integer id = ROW_ID;

    /** When the last successful run began: the next incremental run asks Steam for everything changed since. */
    @Column(name = "last_sync_started_at")
    private Instant lastSyncStartedAt;

    @Column(name = "last_full_sync_at")
    private Instant lastFullSyncAt;

    protected CatalogSyncState() {
    }

    public static CatalogSyncState initial() {
        return new CatalogSyncState();
    }

    public Instant getLastSyncStartedAt() {
        return lastSyncStartedAt;
    }

    public Instant getLastFullSyncAt() {
        return lastFullSyncAt;
    }

    public void recordSuccess(Instant startedAt, boolean full) {
        this.lastSyncStartedAt = startedAt;
        if (full) {
            this.lastFullSyncAt = startedAt;
        }
    }
}
