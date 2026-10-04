package com.vandrae.patchnotes.users.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code app.watchlist.max-games}: how many games one person can follow, so no single account can make the poller do unbounded work. */
@ConfigurationProperties("app.watchlist")
public record WatchlistProperties(@DefaultValue("500") int maxGames) {

    public WatchlistProperties {
        if (maxGames < 1) {
            throw new IllegalArgumentException("app.watchlist.max-games must be at least 1");
        }
    }
}
