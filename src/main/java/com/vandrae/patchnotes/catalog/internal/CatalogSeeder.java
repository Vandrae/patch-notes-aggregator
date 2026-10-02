package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Inserts the configured starter games on boot. Idempotent: existing games are left alone. */
@Component
class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

    private final GameRepository games;
    private final CatalogProperties properties;

    CatalogSeeder(GameRepository games, CatalogProperties properties) {
        this.games = games;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (var seed : properties.seedGames()) {
            if (!games.existsBySteamAppId(seed.steamAppId())) {
                games.save(new Game(seed.name(), seed.steamAppId(), SourceType.STEAM_NEWS));
                log.info("Seeded catalog game '{}' (steamAppId={})", seed.name(), seed.steamAppId());
            }
        }
    }
}
