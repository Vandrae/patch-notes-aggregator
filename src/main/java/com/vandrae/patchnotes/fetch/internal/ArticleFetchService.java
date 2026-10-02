package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.catalog.CatalogService;
import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.feed.FeedIngestion;
import com.vandrae.patchnotes.feed.IngestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fetches, normalizes and stores one game's articles. Deliberately not transactional: the HTTP call must
 * not hold a database connection, and the store step opens its own short transaction.
 */
@Service
class ArticleFetchService {

    private static final Logger log = LoggerFactory.getLogger(ArticleFetchService.class);

    private final CatalogService catalog;
    private final List<ArticleSource> sources;
    private final FeedIngestion ingestion;
    /** One lock per game so a scheduled poll and an on-demand fetch never ingest the same game at once. */
    private final ConcurrentMap<Long, ReentrantLock> gameLocks = new ConcurrentHashMap<>();

    ArticleFetchService(CatalogService catalog, List<ArticleSource> sources, FeedIngestion ingestion) {
        this.catalog = catalog;
        this.sources = sources;
        this.ingestion = ingestion;
    }

    /** @return empty if the game is unknown or has no source adapter */
    Optional<IngestResult> refreshGame(long gameId) {
        Optional<GameSummary> found = catalog.findById(gameId);
        if (found.isEmpty()) {
            log.warn("Skipping fetch for unknown game id {}", gameId);
            return Optional.empty();
        }
        GameSummary game = found.get();
        Optional<ArticleSource> source = sources.stream().filter(s -> s.supports(game)).findFirst();
        if (source.isEmpty()) {
            log.warn("No article source supports game '{}' ({})", game.name(), game.sourceType());
            return Optional.empty();
        }

        ReentrantLock lock = gameLocks.computeIfAbsent(gameId, id -> new ReentrantLock());
        lock.lock();
        try {
            ArticleSource.SourceBatch batch = source.get().fetchLatest(game);
            if (batch.rawItems() == 0) {
                // a quiet game is possible, but a silently broken adapter looks exactly the same: make it visible
                log.warn("Source {} returned no items at all for '{}'. Broken adapter or genuinely nothing published?",
                        source.get().getClass().getSimpleName(), game.name());
            }
            IngestResult result = ingestion.ingest(gameId, batch.articles());
            log.info("Fetched '{}': {} items from source, {} patch notes -> {} new, {} updated, {} unchanged",
                    game.name(), batch.rawItems(), batch.articles().size(),
                    result.created(), result.updated(), result.unchanged());
            return Optional.of(result);
        } finally {
            lock.unlock();
        }
    }
}
