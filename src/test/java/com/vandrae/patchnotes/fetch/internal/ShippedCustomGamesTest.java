package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.fetch.internal.CustomGamesProperties.CustomGame;
import com.vandrae.patchnotes.fetch.internal.CustomGamesProperties.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list of non-Steam games that ships in application.yml, read the way the app reads it. No database and no network:
 * this checks the file itself, so a typo or a lost safeguard fails here and not on a live site.
 */
class ShippedCustomGamesTest {

    private static List<CustomGame> shipped() throws IOException {
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("app.custom", CustomGamesProperties.class)
                .get()
                .games();
    }

    @Test
    void theShippedGamesAreTheOnesWeMeanToSupport() throws IOException {
        assertThat(shipped()).extracting(CustomGame::name)
                .containsExactlyInAnyOrder("Roblox", "Minecraft", "League of Legends", "VALORANT");
    }

    @Test
    void everySourceIsHttpsAndEveryGameHasADescriptionThatFitsItsColumn() throws IOException {
        for (CustomGame game : shipped()) {
            assertThat(game.sources()).as(game.name()).isNotEmpty();
            assertThat(game.sources()).allSatisfy(source -> assertThat(source.url()).startsWith("https://"));
            assertThat(game.description()).as(game.name()).isNotBlank().hasSizeLessThanOrEqualTo(CustomGame.MAX_DESCRIPTION);
        }
    }

    @Test
    void aPageReadWithoutThePublishersInvitationIsNeverReadMoreOftenThanTwiceADay() throws IOException {
        // Riot offers no feed: its pages are read for their embedded data. That is only acceptable while it stays gentle,
        // so removing or shortening this gap must be a deliberate change that fails here first.
        var riotSources = shipped().stream().flatMap(g -> g.sources().stream()).filter(s -> s.kind() == Kind.RIOT_NEWS).toList();

        assertThat(riotSources).hasSize(2);
        assertThat(riotSources).allSatisfy(source -> assertThat(source.minInterval()).isGreaterThanOrEqualTo(Duration.ofHours(12)));
    }

    @Test
    void theFeedsThePublishersOfferOnPurposeUseTheKindsMatchingTheirShape() throws IOException {
        var byGame = shipped().stream().collect(java.util.stream.Collectors.toMap(CustomGame::name, g -> g.sources().get(0).kind()));

        assertThat(byGame).containsEntry("Roblox", Kind.RSS).containsEntry("Minecraft", Kind.HELP_CENTER)
                .containsEntry("League of Legends", Kind.RIOT_NEWS).containsEntry("VALORANT", Kind.RIOT_NEWS);
    }
}
