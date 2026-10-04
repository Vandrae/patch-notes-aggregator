package com.vandrae.patchnotes.users.internal;

import com.vandrae.patchnotes.users.WatchlistItem;
import com.vandrae.patchnotes.users.WatchlistService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Every route is scoped to the caller. The user id comes from the verified token's subject, never from
 * the URL or body (design note #10), so there is no way to read or edit someone else's watchlist.
 */
@RestController
@RequestMapping("/api/watchlist")
class WatchlistController {

    private final WatchlistService watchlist;

    WatchlistController(WatchlistService watchlist) {
        this.watchlist = watchlist;
    }

    @GetMapping
    List<WatchlistItem> list(@AuthenticationPrincipal Jwt jwt) {
        return watchlist.list(Long.parseLong(jwt.getSubject()));
    }

    /** 201 when newly added, 204 when it was already on the list, 409 when the list is full. */
    @PutMapping("/{gameId}")
    ResponseEntity<Void> add(@AuthenticationPrincipal Jwt jwt, @PathVariable long gameId) {
        return switch (watchlist.watch(Long.parseLong(jwt.getSubject()), gameId)) {
            case ADDED -> ResponseEntity.status(HttpStatus.CREATED).build();
            case ALREADY_WATCHING -> ResponseEntity.noContent().build();
            case GAME_NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found");
            case LIMIT_REACHED -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your list is full: you can follow up to " + watchlist.maxGames() + " games. Remove one to add another.");
        };
    }

    @DeleteMapping("/{gameId}")
    ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long gameId) {
        watchlist.unwatch(Long.parseLong(jwt.getSubject()), gameId);
        return ResponseEntity.noContent().build();
    }
}
