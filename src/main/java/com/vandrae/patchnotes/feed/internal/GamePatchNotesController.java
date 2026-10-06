package com.vandrae.patchnotes.feed.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The history behind a game's page. Unlike the feed it is not limited to the caller's watchlist: patch notes are public
 * information and the page is how someone decides whether to follow a game. (Games nobody follows have none stored yet,
 * because only followed games are fetched.)
 */
@RestController
@RequestMapping("/api/games/{gameId}")
class GamePatchNotesController {

    private final FeedQueries feed;

    GamePatchNotesController(FeedQueries feed) {
        this.feed = feed;
    }

    /** The game's patch notes, newest first. 404 for a game that is not in the catalog. */
    @GetMapping("/patch-notes")
    FeedResponse patchNotes(@PathVariable long gameId,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return feed.historyFor(gameId, page, size)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"));
    }

    @GetMapping("/activity")
    GameActivity activity(@PathVariable long gameId) {
        return feed.activityFor(gameId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"));
    }
}
