package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.feed.IncomingArticle;

import java.util.List;

/**
 * A place patch notes can come from. Steam is the first implementation; RSS and HTML-scraping adapters
 * for non-Steam games implement the same interface and the rest of the pipeline doesn't change.
 */
interface ArticleSource {

    boolean supports(GameSummary game);

    SourceBatch fetchLatest(GameSummary game);

    /**
     * @param rawItems number of items the source returned before filtering. Zero from a source that
     *                 normally returns something is the signal of a silently broken adapter (design note #3).
     */
    record SourceBatch(int rawItems, List<IncomingArticle> articles) {
    }
}
