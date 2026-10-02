package com.vandrae.patchnotes.catalog.internal;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a game name (or a search box entry) into the form that is compared in the database: lower case, accents
 * folded, trademark symbols and punctuation removed, whitespace collapsed. Both sides are normalized the same way,
 * so "Half-Life 2: Episode One(tm)" is found by "half life", "HALF-LIFE" or "half-life 2".
 *
 * <p>The result only contains letters, digits and single spaces, which also means it can never contain a SQL
 * LIKE wildcard ({@code %} or {@code _}).
 */
public final class NameSearch {

    public static final int MAX_LENGTH = 255;

    private NameSearch() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        // symbols first: Unicode normalization would otherwise turn the trademark sign into the letters "TM"
        String symbolsRemoved = name.replace("™", " ").replace("®", " ").replace("©", " ");
        // Fold accents on Latin letters ("é" -> "e") without damaging other scripts: decompose, drop only the
        // combining accents block (U+0300-036F), then recompose so e.g. Japanese "ゲ" does not turn into "ケ".
        String folded = Normalizer.normalize(
                Normalizer.normalize(symbolsRemoved, Normalizer.Form.NFKD).replaceAll("[̀-ͯ]+", ""),
                Normalizer.Form.NFKC);
        String spaced = folded.toLowerCase(Locale.ROOT)
                .replaceAll("['’]", "")                              // "Baldur's" -> "baldurs"
                .replaceAll("[^\\p{L}\\p{N}]+", " ")                      // everything else separates words
                .strip();
        return spaced.length() <= MAX_LENGTH ? spaced : spaced.substring(0, MAX_LENGTH).strip();
    }
}
