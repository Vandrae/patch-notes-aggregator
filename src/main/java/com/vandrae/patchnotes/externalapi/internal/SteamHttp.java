package com.vandrae.patchnotes.externalapi.internal;

import com.vandrae.patchnotes.externalapi.SteamProperties;
import org.slf4j.Logger;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/** The HTTP plumbing shared by every Steam client: timeouts, retry with backoff, and log-safe error descriptions. */
public final class SteamHttp {

    private SteamHttp() {
    }

    public static RestClient restClient(SteamProperties props, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    }

    /** Retries transient failures (I/O errors, 5xx, 429) with exponential backoff; anything else fails at once. */
    public static RetryTemplate retry(SteamProperties props, Logger log) {
        return retry(props, log, true);
    }

    /**
     * @param retryRateLimit false for endpoints that Steam throttles hard: an immediate retry after a 429 is itself a
     *                       request that counts against the limit, so the caller should pace and retry instead
     */
    public static RetryTemplate retry(SteamProperties props, Logger log, boolean retryRateLimit) {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(props.maxRetries())
                .delay(props.retryDelay())
                .multiplier(2.0)
                .predicate(t -> {
                    boolean transientFailure = isTransient(t) && (retryRateLimit || !isRateLimit(t));
                    if (transientFailure) {
                        log.warn("Transient Steam API failure, will retry: {}", describe(t));
                    }
                    return transientFailure;
                })
                .build());
    }

    public static boolean isRateLimit(Throwable t) {
        return t instanceof HttpClientErrorException.TooManyRequests;
    }

    static boolean isTransient(Throwable t) {
        return t instanceof ResourceAccessException
                || t instanceof HttpServerErrorException
                || t instanceof HttpClientErrorException.TooManyRequests;
    }

    /**
     * A description of a failure that is safe to log or put in an exception message. Spring's I/O exception messages
     * contain the full request URL, and some Steam calls carry the API key in the query string, so a failure is only
     * ever described by its kind and HTTP status, never by its message.
     */
    public static String describe(Throwable t) {
        if (t instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return t == null ? "unknown error" : t.getClass().getSimpleName();
    }
}
