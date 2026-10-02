package com.vandrae.patchnotes.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgeRatingTest {

    @Test
    void mapsSteamsLowerCaseEsrbCodes() {
        assertThat(AgeRating.fromSteam("e")).contains(AgeRating.EVERYONE);
        assertThat(AgeRating.fromSteam("e10")).contains(AgeRating.EVERYONE_10);
        assertThat(AgeRating.fromSteam("t")).contains(AgeRating.TEEN);
        assertThat(AgeRating.fromSteam("m")).contains(AgeRating.MATURE);
        assertThat(AgeRating.fromSteam("ao")).contains(AgeRating.ADULTS_ONLY);
    }

    @Test
    void ignoresCaseAndSpaces_andFoldsTheRetiredEarlyChildhoodRatingIntoEveryone() {
        assertThat(AgeRating.fromSteam(" M ")).contains(AgeRating.MATURE);
        assertThat(AgeRating.fromSteam("E10")).contains(AgeRating.EVERYONE_10);
        assertThat(AgeRating.fromSteam("ec")).contains(AgeRating.EVERYONE);
    }

    @Test
    void anythingElseMeansNoRating() {
        for (String code : new String[]{null, "", "rp", "18", "pegi 18", "r18", "x"}) {
            assertThat(AgeRating.fromSteam(code)).as(String.valueOf(code)).isEmpty();
        }
    }

    @Test
    void labelsAreTheWordsPeopleKnowFromTheBox() {
        assertThat(AgeRating.values()).extracting(AgeRating::label)
                .containsExactly("Everyone", "Everyone 10+", "Teen", "Mature 17+", "Adults Only 18+");
    }
}
