package com.vandrae.patchnotes.externalapi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for reading publishers' own feeds (the patch notes of games that are not on Steam).
 *
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout    how long a whole request may take
 * @param maxBytes       a response larger than this is refused rather than read into memory
 * @param userAgent      sent with every request, so a publisher can see who is asking and why
 */
@ConfigurationProperties("app.feeds")
public record PublisherFeedProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("5242880") int maxBytes,
        @DefaultValue("patch-notes-aggregator (+https://github.com/Vandrae/patch-notes-aggregator)") String userAgent) {
}
