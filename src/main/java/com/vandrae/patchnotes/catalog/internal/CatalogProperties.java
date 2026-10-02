package com.vandrae.patchnotes.catalog.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param seedGames    games inserted at startup even without a Steam key (handy for development and tests)
 * @param imageBaseUrl where Steam serves store images; stored image paths are relative to it
 * @param iconBaseUrl  where Steam serves the small square game icons; stored icon paths are relative to it
 * @param sync         the Steam catalog synchronisation
 * @param metadata     fetching descriptions, cover images and popularity for each game
 */
@ConfigurationProperties("app.catalog")
public record CatalogProperties(
        List<SeedGame> seedGames,
        @DefaultValue("https://shared.akamai.steamstatic.com/store_item_assets/") String imageBaseUrl,
        @DefaultValue("https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/") String iconBaseUrl,
        @DefaultValue Sync sync,
        @DefaultValue Metadata metadata) {

    public CatalogProperties {
        seedGames = seedGames == null ? List.of() : List.copyOf(seedGames);
        imageBaseUrl = imageBaseUrl.endsWith("/") ? imageBaseUrl : imageBaseUrl + "/";
        iconBaseUrl = iconBaseUrl.endsWith("/") ? iconBaseUrl : iconBaseUrl + "/";
    }

    public record SeedGame(String name, long steamAppId) {
    }

    /**
     * @param enabled          master switch (also needs a Steam API key)
     * @param startupThreshold on startup a full sync runs if the catalog has fewer games than this; after that the
     *                         daily job keeps it current
     */
    public record Sync(@DefaultValue("true") boolean enabled, @DefaultValue("1000") long startupThreshold) {
    }

    /**
     * Steam throttles the details endpoint hard (measured: after a short burst, about one request every 3 seconds;
     * faster ones get HTTP 429), and each request carries 200 games. So the job is ONE paced worker, not a parallel one.
     *
     * @param enabled             master switch (also needs a Steam API key)
     * @param minRequestInterval  pause between requests; also the floor the job speeds back up to after being throttled
     * @param rateLimitBackoff    first wait after a 429; doubles each time it repeats
     * @param maxRateLimitBackoff longest single wait
     * @param maxRateLimitedInARow give up the run after this many consecutive 429s (the next run resumes)
     * @param maxFailedBatches    give up the run after this many failed batches in a row (Steam down)
     * @param staleAfter          details older than this are fetched again
     */
    public record Metadata(@DefaultValue("true") boolean enabled,
                           @DefaultValue("3s") Duration minRequestInterval,
                           @DefaultValue("10s") Duration rateLimitBackoff,
                           @DefaultValue("2m") Duration maxRateLimitBackoff,
                           @DefaultValue("30") int maxRateLimitedInARow,
                           @DefaultValue("5") int maxFailedBatches,
                           @DefaultValue("30d") Duration staleAfter) {
    }
}
