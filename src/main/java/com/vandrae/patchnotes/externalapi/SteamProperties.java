package com.vandrae.patchnotes.externalapi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param apiKey           needed for the catalog sync (IStoreService/GetAppList) and profile lookups. The news endpoint
 *                         is keyless, so the key is deliberately never sent with news requests.
 * @param storeReadTimeout a full catalog page is ~5 MB, so the app-list call gets a much longer read timeout
 * @param appListPageSize  apps per catalog request; Steam allows at most 50,000
 */
@ConfigurationProperties("app.steam")
public record SteamProperties(
        @DefaultValue("https://api.steampowered.com") String baseUrl,
        String apiKey,
        @DefaultValue("20") int newsCount,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("500ms") Duration retryDelay,
        @DefaultValue("90s") Duration storeReadTimeout,
        @DefaultValue("50000") int appListPageSize) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** A record prints every field; this one must never print the key, however carelessly it gets logged. */
    @Override
    public String toString() {
        return "SteamProperties[baseUrl=" + baseUrl + ", apiKey=" + (hasApiKey() ? "<set>" : "<not set>")
                + ", newsCount=" + newsCount + ", maxRetries=" + maxRetries + "]";
    }
}
