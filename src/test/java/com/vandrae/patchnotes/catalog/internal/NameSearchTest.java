package com.vandrae.patchnotes.catalog.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class NameSearchTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(delimiterString = "=>", textBlock = """
            Deadlock                           => deadlock
            Half-Life 2: Episode One™     => half life 2 episode one
            The Witcher® 3: Wild Hunt     => the witcher 3 wild hunt
            Dark Souls™ III               => dark souls iii
            Pokémon Legends: Arceus       => pokemon legends arceus
            Baldur's Gate 3                    => baldurs gate 3
            Baldur’s Gate 3               => baldurs gate 3
            RuneScape: Dragonwilds             => runescape dragonwilds
            ELDEN   RING                       => elden ring
            ---!!!                             =>
            """)
    void normalizesNamesAndQueriesTheSameWay(String input, String expected) {
        assertThat(NameSearch.normalize(input)).isEqualTo(expected == null ? "" : expected);
    }

    @Test
    void neverProducesLikeWildcards() {
        assertThat(NameSearch.normalize("100% _wild_ game")).isEqualTo("100 wild game");
        assertThat(NameSearch.normalize("%")).isEmpty();
    }

    @Test
    void handlesNullAndNonLatinNames() {
        assertThat(NameSearch.normalize(null)).isEmpty();
        assertThat(NameSearch.normalize("ゲーム 2")).isEqualTo("ゲーム 2"); // Japanese letters are kept
    }

    @Test
    void capsTheLengthAtTheColumnSize() {
        assertThat(NameSearch.normalize("a".repeat(400))).hasSize(NameSearch.MAX_LENGTH);
    }
}
