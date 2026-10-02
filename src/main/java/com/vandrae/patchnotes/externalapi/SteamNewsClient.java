package com.vandrae.patchnotes.externalapi;

import com.vandrae.patchnotes.externalapi.internal.SteamHttp;
import com.vandrae.patchnotes.externalapi.internal.SteamNewsEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Client for Steam's news API. Transient failures (I/O errors, 5xx) are retried with exponential
 * backoff; other errors (e.g. 400) fail immediately. HTTP 429 is not retried here: an immediate retry is itself a
 * request that counts against the limit, so it is reported as a {@link SteamRateLimitedException} and the caller, which
 * knows how fast it has been going, decides how long to stand down. A 403/404 is not an error: it is Steam's way of saying the app
 * has no news feed, so it yields an empty list. Once retries are exhausted a {@link SteamApiException} is thrown,
 * so one network blip never poisons a source permanently.
 */
@Component
public class SteamNewsClient {

    private static final Logger log = LoggerFactory.getLogger(SteamNewsClient.class);

    private final RestClient rest;
    private final RetryTemplate retry;
    private final int newsCount;

    @Autowired
    public SteamNewsClient(SteamProperties props) {
        this(props, SteamHttp.restClient(props, props.readTimeout()));
    }

    SteamNewsClient(SteamProperties props, RestClient rest) {
        this.rest = rest;
        this.newsCount = props.newsCount();
        this.retry = SteamHttp.retry(props, log, false);
    }

    /**
     * Steam answers 403 (with an empty {@code {}} body) for an app that has no news feed: tools, delisted apps, ids
     * that don't exist. That is a definite answer, "nothing to fetch", not a failure to retry or report.
     */
    private static boolean hasNoNewsFeed(Throwable cause) {
        return cause instanceof HttpClientErrorException.Forbidden || cause instanceof HttpClientErrorException.NotFound;
    }

    /** Latest news items for a Steam app, newest first, with full (untruncated) contents; empty if it has no feed. */
    public List<SteamNewsItem> getNewsForApp(long appId) {
        try {
            SteamNewsEnvelope envelope = retry.execute(() -> rest.get()
                    .uri(uri -> uri.path("/ISteamNews/GetNewsForApp/v2/")
                            .queryParam("appid", appId)
                            .queryParam("count", newsCount)
                            .queryParam("maxlength", 0) // 0 = full contents (needed to hash edits, note #6)
                            .queryParam("format", "json")
                            .build())
                    .retrieve()
                    .body(SteamNewsEnvelope.class));

            if (envelope == null || envelope.appnews() == null || envelope.appnews().newsitems() == null) {
                return List.of();
            }
            return envelope.appnews().newsitems();
        } catch (RetryException e) {
            if (hasNoNewsFeed(e.getCause())) {
                log.debug("Steam has no news feed for app {} ({})", appId, SteamHttp.describe(e.getCause()));
                return List.of();
            }
            if (SteamHttp.isRateLimit(e.getCause())) {
                throw new SteamRateLimitedException("Steam news request for app " + appId + " was rate limited (HTTP 429)");
            }
            throw new SteamApiException("Steam news request for app " + appId + " failed after retries", e.getCause());
        }
    }
}
