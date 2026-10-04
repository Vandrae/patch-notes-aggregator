package com.vandrae.patchnotes.users;

import com.vandrae.patchnotes.catalog.CatalogService;
import com.vandrae.patchnotes.events.GameWatched;
import com.vandrae.patchnotes.users.internal.WatchlistEntry;
import com.vandrae.patchnotes.users.internal.WatchlistProperties;
import com.vandrae.patchnotes.users.internal.WatchlistRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WatchlistService {

    public enum WatchResult {
        ADDED, ALREADY_WATCHING, GAME_NOT_FOUND, LIMIT_REACHED
    }

    private final WatchlistRepository watchlist;
    private final CatalogService catalog;
    private final ApplicationEventPublisher events;
    private final WatchlistProperties properties;

    WatchlistService(WatchlistRepository watchlist, CatalogService catalog, ApplicationEventPublisher events,
                     WatchlistProperties properties) {
        this.watchlist = watchlist;
        this.catalog = catalog;
        this.events = events;
        this.properties = properties;
    }

    /** The most games one person can follow. */
    public int maxGames() {
        return properties.maxGames();
    }

    /**
     * Idempotent. A full list refuses new games (never one already on it). Announces {@link GameWatched} only when the
     * game was actually added.
     */
    @Transactional
    public WatchResult watch(long userId, long gameId) {
        if (!catalog.exists(gameId)) {
            return WatchResult.GAME_NOT_FOUND;
        }
        if (watchlist.existsByUserIdAndGameId(userId, gameId)) {
            return WatchResult.ALREADY_WATCHING;
        }
        if (watchlist.countByUserId(userId) >= properties.maxGames()) {
            return WatchResult.LIMIT_REACHED;
        }
        watchlist.save(new WatchlistEntry(userId, gameId));
        events.publishEvent(new GameWatched(userId, gameId));
        return WatchResult.ADDED;
    }

    /** Idempotent: returns whether anything was removed. */
    @Transactional
    public boolean unwatch(long userId, long gameId) {
        return watchlist.deleteByUserIdAndGameId(userId, gameId) > 0;
    }

    /** Newest first. An empty list is a normal state, not an error. */
    @Transactional(readOnly = true)
    public List<WatchlistItem> list(long userId) {
        var entries = watchlist.findByUserIdOrderByAddedAtDesc(userId);
        var games = catalog.findAllById(entries.stream().map(WatchlistEntry::getGameId).toList());
        return entries.stream()
                .filter(e -> games.containsKey(e.getGameId()))
                .map(e -> new WatchlistItem(games.get(e.getGameId()), e.getAddedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Long> gameIdsFor(long userId) {
        return watchlist.findGameIdsByUserId(userId);
    }

    /** Queried fresh each poll cycle, never cached, so add/remove between cycles is always honored. */
    @Transactional(readOnly = true)
    public List<Long> distinctWatchedGameIds() {
        return watchlist.findDistinctGameIds();
    }
}
