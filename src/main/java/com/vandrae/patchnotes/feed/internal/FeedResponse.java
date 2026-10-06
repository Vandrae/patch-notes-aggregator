package com.vandrae.patchnotes.feed.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vandrae.patchnotes.feed.ArticleType;

import java.time.Instant;
import java.util.List;

/**
 * A page of the feed. When there is nothing to show, {@code emptyState} says why, so a client can render
 * "add games to get started" or "no news yet" instead of a blank screen (design note #11).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedResponse(
        List<FeedItem> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        EmptyState emptyState) {

    public record FeedItem(
            long articleId,
            long gameId,
            String gameName,
            /** The game's small square Steam icon (https), or null if it has none (yet). */
            String gameIconUrl,
            String title,
            String url,
            String summary,
            ArticleType type,
            Instant publishedAt) {
    }

    public enum EmptyReason {
        NO_WATCHLIST, NO_ARTICLES_YET, NO_MATCHING_GAMES, NOT_TRACKED
    }

    public record EmptyState(EmptyReason reason, String message) {
    }
}
