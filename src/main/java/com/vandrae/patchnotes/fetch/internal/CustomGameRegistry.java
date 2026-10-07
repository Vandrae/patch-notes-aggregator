package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.catalog.CatalogService;
import com.vandrae.patchnotes.fetch.internal.CustomGamesProperties.CustomGame;
import com.vandrae.patchnotes.fetch.internal.CustomGamesProperties.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts the configured non-Steam games in the catalog at startup (so they can be searched and followed like any other) and
 * remembers which sources belong to which game. Idempotent: on every later start the games already exist, so everyone's
 * watchlist keeps working, and only their descriptions are refreshed.
 */
@Component
class CustomGameRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CustomGameRegistry.class);

    private final CatalogService catalog;
    private final CustomGamesProperties properties;
    private final Map<Long, List<Source>> sourcesByGameId = new ConcurrentHashMap<>();

    CustomGameRegistry(CatalogService catalog, CustomGamesProperties properties) {
        this.catalog = catalog;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        register();
    }

    void register() {
        if (!properties.enabled()) {
            return;
        }
        for (CustomGame game : properties.games()) {
            long id = catalog.ensureCustomGame(game.name(), game.description(), game.popularity(), game.image(), game.icon());
            sourcesByGameId.put(id, game.sources());
            log.info("Custom game '{}' (id {}): {} source(s)", game.name(), id, game.sources().size());
        }
    }

    /** The sources of a game registered here; empty for any other game. */
    List<Source> sourcesFor(long gameId) {
        return sourcesByGameId.getOrDefault(gameId, List.of());
    }
}
