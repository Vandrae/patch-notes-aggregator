package com.vandrae.patchnotes.catalog;

import java.util.Set;

/**
 * Optional narrowing of games by genre and review rating, shared by Discover and the feed.
 *
 * @param genres    keep games that have at least one of these genres; empty = any genre
 * @param minRating keep games whose Steam review level is at least this ({@link Rating}, 1-9); 0 = any rating,
 *                  including games without reviews
 */
public record GameFilter(Set<Genre> genres, int minRating) {

    public static final GameFilter NONE = new GameFilter(Set.of(), 0);

    public GameFilter {
        genres = genres == null ? Set.of() : Set.copyOf(genres);
        minRating = Math.clamp(minRating, 0, Rating.MAX_SCORE);
    }

    public boolean isEmpty() {
        return genres.isEmpty() && minRating == 0;
    }
}
