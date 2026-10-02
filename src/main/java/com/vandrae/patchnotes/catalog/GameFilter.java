package com.vandrae.patchnotes.catalog;

import java.util.Set;

/**
 * Optional narrowing of games by genre, review rating and age rating, shared by Discover and the feed.
 *
 * @param genres     keep games that have at least one of these genres; empty = any genre
 * @param minRating  keep games whose Steam review level is at least this ({@link Rating}, 1-9); 0 = any rating,
 *                   including games without reviews
 * @param ageRatings keep games with one of these ESRB age ratings; empty = any, including games with no age rating
 */
public record GameFilter(Set<Genre> genres, int minRating, Set<AgeRating> ageRatings) {

    public static final GameFilter NONE = new GameFilter(Set.of(), 0, Set.of());

    public GameFilter {
        genres = genres == null ? Set.of() : Set.copyOf(genres);
        minRating = Math.clamp(minRating, 0, Rating.MAX_SCORE);
        ageRatings = ageRatings == null ? Set.of() : Set.copyOf(ageRatings);
    }

    public boolean isEmpty() {
        return genres.isEmpty() && minRating == 0 && ageRatings.isEmpty();
    }
}
