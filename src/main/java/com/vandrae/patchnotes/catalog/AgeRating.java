package com.vandrae.patchnotes.catalog;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The ESRB age rating Steam shows on a store page, from youngest to oldest audience. Steam reports it as a lower-case
 * letter code ("e10", "t", "m"); {@link #fromSteam} maps those and ignores anything else.
 */
public enum AgeRating {
    EVERYONE("e", "Everyone"),
    EVERYONE_10("e10", "Everyone 10+"),
    TEEN("t", "Teen"),
    MATURE("m", "Mature 17+"),
    ADULTS_ONLY("ao", "Adults Only 18+");

    private static final Map<String, AgeRating> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toMap(a -> a.steamCode, Function.identity()));

    private final String steamCode;
    private final String label;

    AgeRating(String steamCode, String label) {
        this.steamCode = steamCode;
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** "Early Childhood" ("ec") was folded into Everyone by the ESRB; "rp" (rating pending) and unknown codes mean no rating. */
    public static Optional<AgeRating> fromSteam(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.strip().toLowerCase(Locale.ROOT);
        return Optional.ofNullable("ec".equals(normalized) ? EVERYONE : BY_CODE.get(normalized));
    }
}
