package com.vandrae.patchnotes.feed.internal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/feed")
class FeedController {

    private final FeedQueries feed;

    FeedController(FeedQueries feed) {
        this.feed = feed;
    }

    /** Patch notes for the caller's watched games only, newest first; {@code gameId} narrows it to one of them. */
    @GetMapping
    FeedResponse feed(@AuthenticationPrincipal Jwt jwt,
                      @RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "20") int size,
                      @RequestParam(required = false) Long gameId) {
        return feed.feedFor(Long.parseLong(jwt.getSubject()), page, size, gameId);
    }
}
