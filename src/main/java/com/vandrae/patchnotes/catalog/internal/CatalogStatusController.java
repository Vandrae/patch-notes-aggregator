package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.CatalogStatus;
import com.vandrae.patchnotes.catalog.CatalogSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
class CatalogStatusController {

    private final CatalogSyncService sync;

    CatalogStatusController(CatalogSyncService sync) {
        this.sync = sync;
    }

    /** Lets the UI say "the catalog is still being imported" instead of showing a mysteriously empty search. */
    @GetMapping("/status")
    CatalogStatus status() {
        return sync.status();
    }
}
