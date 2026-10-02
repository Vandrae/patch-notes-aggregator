package com.vandrae.patchnotes.feed.internal;

import com.vandrae.patchnotes.catalog.CatalogService;
import com.vandrae.patchnotes.catalog.GameFilter;
import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.feed.internal.FeedResponse.EmptyReason;
import com.vandrae.patchnotes.feed.internal.FeedResponse.EmptyState;
import com.vandrae.patchnotes.feed.internal.FeedResponse.FeedItem;
import com.vandrae.patchnotes.users.WatchlistService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
class FeedQueries {

    static final int MAX_PAGE_SIZE = 50;

    private final ArticleRepository articles;
    private final WatchlistService watchlist;
    private final CatalogService catalog;

    FeedQueries(ArticleRepository articles, WatchlistService watchlist, CatalogService catalog) {
        this.articles = articles;
        this.watchlist = watchlist;
        this.catalog = catalog;
    }

    /**
     * @param gameId optional: narrow the feed to one game. Only games the user actually watches count, so passing
     *               someone else's game id can never reveal anything beyond the user's own feed.
     */
    FeedResponse feedFor(long userId, int page, int size, Long gameId) {
        return feedFor(userId, page, size, gameId, GameFilter.NONE);
    }

    /**
     * @param filter optional: only patch notes of watched games with one of these genres and at least this rating
     *               (a game's genre and rating are the game's, so the filter picks games, not articles)
     */
    FeedResponse feedFor(long userId, int page, int size, Long gameId, GameFilter filter) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);

        List<Long> watchedGameIds = watchlist.gameIdsFor(userId);
        if (watchedGameIds.isEmpty()) {
            return empty(safePage, safeSize, EmptyReason.NO_WATCHLIST,
                    "You're not watching any games yet. Add some to your watchlist to get started.");
        }
        if (gameId != null) {
            watchedGameIds = watchedGameIds.stream().filter(gameId::equals).toList();
            if (watchedGameIds.isEmpty()) {
                return empty(safePage, safeSize, EmptyReason.NO_ARTICLES_YET, "You're not watching that game.");
            }
        }

        if (!filter.isEmpty()) {
            Set<Long> matching = Set.copyOf(catalog.filterIds(watchedGameIds, filter));
            watchedGameIds = watchedGameIds.stream().filter(matching::contains).toList();
            if (watchedGameIds.isEmpty()) {
                return empty(safePage, safeSize, EmptyReason.NO_MATCHING_GAMES,
                        "None of the games you're watching match these filters.");
            }
        }

        // newest first; id breaks ties so paging is stable when several articles share a timestamp
        var pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id")));
        Page<Article> result = articles.findByGameIdIn(watchedGameIds, pageable);

        if (result.getTotalElements() == 0) {
            return empty(safePage, safeSize, EmptyReason.NO_ARTICLES_YET,
                    "No patch notes yet for the games you're watching. We'll show them here as soon as they're published.");
        }

        Set<Long> gameIdsOnPage = result.getContent().stream().map(Article::getGameId).collect(Collectors.toSet());
        Map<Long, GameSummary> games = catalog.findAllById(gameIdsOnPage);

        List<FeedItem> items = result.getContent().stream()
                .map(a -> {
                    GameSummary game = games.get(a.getGameId());
                    return new FeedItem(a.getId(), a.getGameId(), game.name(), game.iconUrl(),
                            a.getTitle(), a.getUrl(), a.getSummary(), a.getType(), a.getPublishedAt());
                })
                .toList();
        return new FeedResponse(items, safePage, safeSize, result.getTotalElements(), result.getTotalPages(), null);
    }

    private static FeedResponse empty(int page, int size, EmptyReason reason, String message) {
        return new FeedResponse(List.of(), page, size, 0, 0, new EmptyState(reason, message));
    }
}
