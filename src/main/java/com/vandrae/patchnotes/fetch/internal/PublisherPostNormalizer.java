package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.externalapi.PublisherPost;
import com.vandrae.patchnotes.feed.ArticleType;
import com.vandrae.patchnotes.feed.IncomingArticle;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns a post from a publisher's own feed into the common article shape. A feed configured for a game is the game's
 * patch-note feed, so every post in it is a patch note: unlike Steam's mixed news, nothing needs guessing.
 *
 * <p>Steam's ids, titles and links always fit our columns; a publisher's may not (an RSS id is often the post's whole
 * address), and one that did not fit would fail the whole game's fetch. So they are made to fit here.
 */
@Component
class PublisherPostNormalizer {

    /**
     * Mixed into every article's content hash, as for Steam: an article is only rewritten when its hash changes, so bump
     * this whenever the way summaries are built changes and the stored ones are rebuilt on the next fetch.
     */
    static final String SUMMARY_VERSION = "publisher-summary-v1: plain text of the html body";

    /** The column sizes of the article table (V4__articles.sql). */
    static final int MAX_ID = 64;
    static final int MAX_TITLE = 500;
    static final int MAX_URL = 1000;

    /** Empty when the post cannot be shown: readers are sent to the original, and only a plain https address is safe to send them to. */
    Optional<IncomingArticle> normalize(PublisherPost post) {
        if (!isHttps(post.url()) || post.url().length() > MAX_URL) {
            return Optional.empty();
        }
        return Optional.of(new IncomingArticle(
                fittingId(post.externalId()),
                PlainText.truncate(post.title(), MAX_TITLE),
                post.url(),
                PlainText.summaryOfHtml(post.html()),
                ArticleType.PATCH_NOTES,
                post.publishedAt(),
                PlainText.sha256(SUMMARY_VERSION + "\n" + post.title() + "\n" + post.html())));
    }

    /** An id too long for its column is replaced by its SHA-256 (exactly 64 hex characters): still unique, and the same every time. */
    private static String fittingId(String id) {
        return id.length() <= MAX_ID ? id : PlainText.sha256(id);
    }

    private static boolean isHttps(String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, "https://".length());
    }
}
