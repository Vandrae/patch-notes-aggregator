package com.vandrae.patchnotes.catalog;

import java.time.Duration;

/**
 * @param inserted  games added to the catalog
 * @param updated   games whose name or Steam timestamp changed
 * @param unchanged games already up to date
 * @param skipped   entries ignored (blank names, repeats within a page)
 */
public record SyncResult(boolean full, int pages, int inserted, int updated, int unchanged, int skipped, Duration took) {
}
