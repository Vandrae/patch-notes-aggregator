package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.events.GameWatched;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * When somebody starts watching a game, fetch it right away instead of making them wait for the next
 * scheduled cycle, so a freshly watched game shows patch notes straight away.
 *
 * <p>Runs asynchronously after the watcher's transaction commits. If it fails, Modulith's event
 * publication registry keeps the event and retries on restart; the next poll cycle is the safety net.
 */
@Component
class GameWatchedListener {

    private final ArticleFetchService fetcher;

    GameWatchedListener(ArticleFetchService fetcher) {
        this.fetcher = fetcher;
    }

    @ApplicationModuleListener
    void on(GameWatched event) {
        fetcher.refreshGame(event.gameId());
    }
}
