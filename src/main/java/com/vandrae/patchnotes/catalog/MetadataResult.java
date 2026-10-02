package com.vandrae.patchnotes.catalog;

import java.time.Duration;

/**
 * @param processed     games whose details were fetched and stored this run
 * @param withDetails   of those, how many actually have a store page (the rest are delisted or never released)
 * @param failedBatches requests that failed outright; those games stay pending and the next run retries them
 * @param rateLimited   times Steam answered "too many requests" (each was waited out and the same batch retried)
 */
public record MetadataResult(int processed, int withDetails, int failedBatches, int rateLimited, Duration took) {
}
