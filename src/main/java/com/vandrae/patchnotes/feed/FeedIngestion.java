package com.vandrae.patchnotes.feed;

import com.vandrae.patchnotes.events.ArticlesIngested;
import com.vandrae.patchnotes.feed.internal.Article;
import com.vandrae.patchnotes.feed.internal.ArticleRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FeedIngestion {

    private final ArticleRepository articles;
    private final ApplicationEventPublisher events;

    FeedIngestion(ArticleRepository articles, ApplicationEventPublisher events) {
        this.articles = articles;
        this.events = events;
    }

    /**
     * Upserts by (game, externalId): unseen articles are inserted, articles whose content hash changed are
     * updated in place, identical ones are skipped. Re-running with the same input is a no-op, so
     * overlapping or repeated polls can't create duplicates.
     */
    @Transactional
    public IngestResult ingest(long gameId, Collection<IncomingArticle> incoming) {
        Map<String, IncomingArticle> byExternalId = new LinkedHashMap<>();
        incoming.forEach(article -> byExternalId.put(article.externalId(), article)); // last one wins
        if (byExternalId.isEmpty()) {
            return IngestResult.NOTHING;
        }

        Map<String, Article> existing = articles.findByGameIdAndExternalIdIn(gameId, byExternalId.keySet()).stream()
                .collect(Collectors.toMap(Article::getExternalId, Function.identity()));

        Instant now = Instant.now();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (IncomingArticle in : byExternalId.values()) {
            Article current = existing.get(in.externalId());
            if (current == null) {
                articles.save(new Article(gameId, in, now));
                created++;
            } else if (!current.getContentHash().equals(in.contentHash())) {
                current.updateFrom(in, now); // managed entity: flushed on commit
                updated++;
            } else {
                unchanged++;
            }
        }

        if (created > 0 || updated > 0) {
            events.publishEvent(new ArticlesIngested(gameId, created, updated));
        }
        return new IngestResult(created, updated, unchanged);
    }
}
