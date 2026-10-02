package com.vandrae.patchnotes.catalog;

import com.vandrae.patchnotes.catalog.internal.Game;
import com.vandrae.patchnotes.catalog.internal.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Search behaviour on a small, deliberately awkward catalog. Fixture app ids start at 800,000,000. */
@SpringBootTest
@ActiveProfiles("test")
class CatalogSearchTest {

    private static final long BASE_ID = 800_000_000L;

    @Autowired CatalogService catalog;
    @Autowired GameRepository games;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM game WHERE steam_app_id >= ? AND steam_app_id < ?", BASE_ID, BASE_ID + 1_000_000);
        long id = BASE_ID;
        for (String name : List.of(
                "Deadlock", "Deadlock: Prologue", "Hades Deadlock Edition", "Dead Lock", "Deadlocked Souls",
                "Half-Life 2: Episode One™", "Half-Life: Alyx", "The Witcher® 3: Wild Hunt",
                "Pokémon Legends: Arceus", "Baldur's Gate 3",
                "Rust", "Trust Falls", "Crusty Cooking",
                "Tomb Raider I", "Tomb Raider II")) {
            games.save(new Game(name, id++, SourceType.STEAM_NEWS));
        }
    }

    private List<String> names(String query) {
        return catalog.search(query, 0, 50).getContent().stream().map(GameSummary::name).toList();
    }

    @Test
    void ranksTheExactNameFirstThenPrefixMatchesThenNamesContainingTheText() { // with equal popularity: alphabetical
        assertThat(names("deadlock")).containsExactly(
                "Deadlock",                  // exact
                "Deadlock: Prologue",        // starts with
                "Deadlocked Souls",          // starts with
                "Hades Deadlock Edition");   // contains
    }

    @Test
    void ignoresCaseAndSurroundingWhitespace() {
        assertThat(names("  DEADLOCK  ")).startsWith("Deadlock");
        assertThat(names("DeAdLoCk")).hasSize(4);
    }

    @Test
    void doesNotMatchWhenTheWordsAreSplitDifferently() {
        assertThat(names("deadlock")).doesNotContain("Dead Lock");
    }

    @Test
    void ignoresPunctuationTrademarkSymbolsAndAccents() {
        assertThat(names("half life 2")).containsExactly("Half-Life 2: Episode One™");
        assertThat(names("HALF-LIFE")).containsExactly("Half-Life 2: Episode One™", "Half-Life: Alyx");
        assertThat(names("witcher 3")).containsExactly("The Witcher® 3: Wild Hunt");
        assertThat(names("pokemon")).containsExactly("Pokémon Legends: Arceus");
        assertThat(names("Pokémon")).containsExactly("Pokémon Legends: Arceus");
        assertThat(names("baldurs gate")).containsExactly("Baldur's Gate 3");
        assertThat(names("baldur's gate")).containsExactly("Baldur's Gate 3");
    }

    @Test
    void veryShortQueriesOnlyMatchTheStartOfAName() {
        // "ru" would otherwise also hit "Trust Falls" and "Crusty Cooking" (it still finds every name that STARTS with it)
        assertThat(names("ru")).contains("Rust").doesNotContain("Trust Falls", "Crusty Cooking");
        // from three characters on, "contains" kicks in, still ranked prefix-first
        assertThat(names("rus")).containsExactly("Rust", "Crusty Cooking", "Trust Falls");
    }

    @Test
    void aQueryOfOnlyPunctuationMatchesNothingRatherThanEverything() {
        for (String query : List.of("%", "_", "!!!", "...", "™")) {
            assertThat(catalog.search(query, 0, 50).getTotalElements()).as(query).isZero();
        }
    }

    @Test
    void wildcardCharactersAreNeverInterpretedAsWildcards() {
        assertThat(names("dead%")).isEqualTo(names("dead"));
        assertThat(names("de_dlock")).isEqualTo(names("de dlock"));
    }

    private void setPopularity(String name, long popularity) {
        jdbc.update("UPDATE game SET popularity = ? WHERE name = ? AND steam_app_id >= ? AND steam_app_id < ?",
                popularity, name, BASE_ID, BASE_ID + 1_000_000);
    }

    @Test
    void aBlankQueryBrowsesTheWholeCatalogMostPopularFirst() {
        setPopularity("Rust", 9_000_000_000_000L);
        setPopularity("Half-Life: Alyx", 8_000_000_000_000L);

        Page<GameSummary> page = catalog.search("", 0, 5);

        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(15);
        assertThat(page.getContent()).extracting(GameSummary::name).startsWith("Rust", "Half-Life: Alyx");
        assertThat(catalog.search(null, 0, 5).getTotalElements()).isEqualTo(page.getTotalElements());
    }

    @Test
    void amongEquallyRelevantMatchesTheMostPopularComesFirst() {
        // three unrelated games that share a name, as with the real "Deadlock": only popularity can tell them apart
        long id = BASE_ID + 500;
        games.save(new Game("LOCKDOWN", id, SourceType.STEAM_NEWS));
        games.save(new Game("LockDown", id + 1, SourceType.STEAM_NEWS));
        games.save(new Game("Lockdown", id + 2, SourceType.STEAM_NEWS));
        setPopularity("LOCKDOWN", 10);
        setPopularity("LockDown", 5);
        setPopularity("Lockdown", 1_850_000); // e.g. a game with 185,000 players on the chart but no reviews

        assertThat(names("lockdown")).containsExactly("Lockdown", "LOCKDOWN", "LockDown");
    }

    @Test
    void anObscureExactNameDoesNotBeatMuchMorePopularGames_theRealWarSearch() {
        // real numbers from Steam: "WAR!" has no reviews and no players, but its name normalizes to exactly "war"
        long id = BASE_ID + 600;
        for (String name : List.of("WAR!", "WARDOGS", "War Thunder", "Warframe", "Warhammer 40,000: Space Marine 2",
                "Total War: WARHAMMER III", "Ever Seen A War?")) {
            games.save(new Game(name, id++, SourceType.STEAM_NEWS));
        }
        setPopularity("WAR!", 0);
        setPopularity("WARDOGS", 2_402_115);
        setPopularity("War Thunder", 1_560_190);
        setPopularity("Warframe", 1_384_042);
        setPopularity("Warhammer 40,000: Space Marine 2", 319_662);
        setPopularity("Total War: WARHAMMER III", 801_624);
        setPopularity("Ever Seen A War?", 568);

        assertThat(names("war")).containsSubsequence(
                "WARDOGS", "War Thunder", "Warframe",        // the popular games lead...
                "Warhammer 40,000: Space Marine 2",           // ...a popular prefix match
                "Total War: WARHAMMER III",                   // ...even a popular game that merely CONTAINS "war" beats
                "WAR!",                                       // the exact match nobody plays
                "Ever Seen A War?");
        assertThat(names("war").getFirst()).isNotEqualTo("WAR!");
    }

    @Test
    void anExactNameStillWinsWhenItIsAboutAsPopularAsTheAlternatives() {
        long id = BASE_ID + 700;
        games.save(new Game("Portal", id, SourceType.STEAM_NEWS));
        games.save(new Game("Portal 2", id + 1, SourceType.STEAM_NEWS));
        games.save(new Game("Portal Knights", id + 2, SourceType.STEAM_NEWS));
        setPopularity("Portal", 300_000);
        setPopularity("Portal 2", 1_200_000);   // 4x more popular, but "portal" is clearly about the first
        setPopularity("Portal Knights", 90_000);

        assertThat(names("portal")).containsExactly("Portal", "Portal 2", "Portal Knights");
    }

    @Test
    void relevanceStillMattersWhenPopularityIsEqual() {
        // all four have popularity 0 (no details yet): exact, then prefix (alphabetical), then contains
        assertThat(names("deadlock")).containsExactly(
                "Deadlock", "Deadlock: Prologue", "Deadlocked Souls", "Hades Deadlock Edition");
    }

    @Test
    void aHugelyPopularGameMatchingAnywhereInTheNameCanLeadTheResults() {
        setPopularity("Hades Deadlock Edition", 9_000_000_000_000L); // only CONTAINS "deadlock", but enormously popular

        assertThat(names("deadlock").getFirst()).isEqualTo("Hades Deadlock Edition");
    }

    @Test
    void resultsCarryTheSquareIconAsAFullUrlToo() {
        jdbc.update("UPDATE game SET icon_path = ? WHERE name = ? AND steam_app_id >= ? AND steam_app_id < ?",
                "730/8dbc71957312bbd3baea65848b545be9eae2a355.jpg", "Rust", BASE_ID, BASE_ID + 1_000_000);

        assertThat(catalog.search("rust", 0, 5).getContent().getFirst().iconUrl()).isEqualTo(
                "https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/730/8dbc71957312bbd3baea65848b545be9eae2a355.jpg");
        assertThat(catalog.search("half life alyx", 0, 5).getContent().getFirst().iconUrl()).isNull();
    }

    @Test
    void resultsCarryTheDescriptionAndAFullImageUrl() {
        jdbc.update("UPDATE game SET short_description = ?, image_path = ? WHERE name = ? AND steam_app_id >= ? AND steam_app_id < ?",
                "A short summary.", "steam/apps/9/abc/capsule_231x87.jpg?t=7", "Rust", BASE_ID, BASE_ID + 1_000_000);

        GameSummary rust = catalog.search("rust", 0, 5).getContent().getFirst();

        assertThat(rust.shortDescription()).isEqualTo("A short summary.");
        assertThat(rust.imageUrl()).isEqualTo("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/9/abc/capsule_231x87.jpg?t=7");
        // a game without details simply has none; the UI falls back to a generated tile
        GameSummary alyx = catalog.search("half life alyx", 0, 5).getContent().getFirst();
        assertThat(alyx.shortDescription()).isNull();
        assertThat(alyx.imageUrl()).isNull();
    }

    @Test
    void pagesThroughResultsAndClampsAbsurdPageSizes() {
        Page<GameSummary> first = catalog.search("tomb raider", 0, 1);
        Page<GameSummary> second = catalog.search("tomb raider", 1, 1);

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).extracting(GameSummary::name).containsExactly("Tomb Raider I");
        assertThat(second.getContent()).extracting(GameSummary::name).containsExactly("Tomb Raider II");
        assertThat(catalog.search("", 0, 100_000).getSize()).isEqualTo(CatalogService.MAX_PAGE_SIZE);
        assertThat(catalog.search("tomb raider", -5, 0).getNumber()).isZero();
    }
}
