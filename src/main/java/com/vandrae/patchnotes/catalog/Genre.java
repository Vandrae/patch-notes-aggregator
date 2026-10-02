package com.vandrae.patchnotes.catalog;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Steam's ten standard store genres. Steam has hundreds of free-form user tags; these are the ones that behave like
 * genres (the same list the store's own "Genre" filter offers), identified by their tag id.
 */
public enum Genre {
    ACTION(19, "Action"),
    ADVENTURE(21, "Adventure"),
    CASUAL(597, "Casual"),
    INDIE(492, "Indie"),
    MASSIVELY_MULTIPLAYER(128, "Massively Multiplayer"),
    RACING(699, "Racing"),
    RPG(122, "RPG"),
    SIMULATION(599, "Simulation"),
    SPORTS(701, "Sports"),
    STRATEGY(9, "Strategy");

    private static final Map<Long, Genre> BY_TAG_ID =
            Arrays.stream(values()).collect(Collectors.toMap(g -> g.steamTagId, Function.identity()));

    private final long steamTagId;
    private final String label;

    Genre(long steamTagId, String label) {
        this.steamTagId = steamTagId;
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<Genre> fromSteamTag(long tagId) {
        return Optional.ofNullable(BY_TAG_ID.get(tagId));
    }
}
