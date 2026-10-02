package com.vandrae.patchnotes.feed;

import java.time.Instant;

/**
 * The one shape every source is normalized into.
 *
 * @param externalId  the source's own stable id for the article (Steam: the gid); the dedup key
 * @param publishedAt always a UTC instant, whatever format the source used (design note #5)
 * @param contentHash hash of the full source content, used to detect silent edits after publishing
 */
public record IncomingArticle(
        String externalId,
        String title,
        String url,
        String summary,
        ArticleType type,
        Instant publishedAt,
        String contentHash) {
}
