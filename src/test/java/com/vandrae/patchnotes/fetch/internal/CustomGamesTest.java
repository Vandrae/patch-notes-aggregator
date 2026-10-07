package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.catalog.CatalogService;
import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.catalog.SourceType;
import com.vandrae.patchnotes.externalapi.PublisherFeedClient;
import com.vandrae.patchnotes.externalapi.PublisherFeedException;
import com.vandrae.patchnotes.externalapi.PublisherPost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/** Non-Steam games end to end: configured, registered in the catalog, fetched from their feeds, stored as patch notes. */
@SpringBootTest(properties = {
        "app.custom.enabled=true",
        "app.custom.games[0].name=Custompub Single",
        "app.custom.games[0].description=A game with one feed.",
        "app.custom.games[0].popularity=777",
        "app.custom.games[0].image=/art/roblox-cover.svg",
        "app.custom.games[0].icon=/art/roblox-icon.svg",
        "app.custom.games[0].sources[0].kind=RSS",
        "app.custom.games[0].sources[0].url=https://publisher.test/single.rss",
        "app.custom.games[1].name=Custompub Double",
        "app.custom.games[1].description=A game with two feeds.",
        "app.custom.games[1].sources[0].kind=RSS",
        "app.custom.games[1].sources[0].url=https://publisher.test/one.rss",
        "app.custom.games[1].sources[1].kind=HELP_CENTER",
        "app.custom.games[1].sources[1].url=https://publisher.test/two.json"})
@ActiveProfiles("test")
class CustomGamesTest {

    @Autowired CatalogService catalog;
    @Autowired CustomGameRegistry registry;
    @Autowired ArticleFetchService fetcher;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PublisherFeedClient feeds;

    private static PublisherPost post(String id, String title, String url, String html, String when) {
        return new PublisherPost(id, title, url, html, Instant.parse(when));
    }

    private GameSummary game(String name) {
        return catalog.search(name, 0, 5).stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    private int storedArticles(long gameId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE game_id = ?", Integer.class, gameId);
    }

    @BeforeEach
    void cleanSlate() {
        reset(feeds);
        jdbc.update("DELETE FROM article WHERE game_id IN (SELECT id FROM game WHERE name LIKE 'Custompub %')");
        jdbc.update("DELETE FROM game_fetch_state WHERE game_id IN (SELECT id FROM game WHERE name LIKE 'Custompub %')");
    }

    @Test
    void theConfiguredGamesAreInTheCatalogAsGamesNotOnSteamAndCanBeSearched() {
        GameSummary single = game("Custompub Single");

        assertThat(single.sourceType()).isEqualTo(SourceType.CUSTOM);
        assertThat(single.steamAppId()).isNull();
        assertThat(single.shortDescription()).isEqualTo("A game with one feed.");
        // its art is a path on this site, used as it is (Steam's are paths under Steam's image servers)
        assertThat(single.imageUrl()).isEqualTo("/art/roblox-cover.svg");
        assertThat(single.iconUrl()).isEqualTo("/art/roblox-icon.svg");
        assertThat(game("Custompub Double").imageUrl()).isNull(); // no art configured: the app draws a tile
        assertThat(catalog.search("custompub", 0, 10).getContent()).extracting(GameSummary::name)
                .contains("Custompub Single", "Custompub Double");
        assertThat(jdbc.queryForObject("SELECT popularity FROM game WHERE id = ?", Long.class, single.id())).isEqualTo(777L);
    }

    @Test
    void registeringAgainKeepsTheSameGameSoWatchlistsStayValid() {
        long before = game("Custompub Single").id();

        registry.register();
        registry.register();

        assertThat(game("Custompub Single").id()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game WHERE name = 'Custompub Single'", Integer.class)).isEqualTo(1);
    }

    @Test
    void aFetchStoresTheFeedsPostsAsPatchNotesWithAShortPlainSummaryAndTheOriginalLink() {
        long id = game("Custompub Single").id();
        when(feeds.readRss("https://publisher.test/single.rss")).thenReturn(List.of(
                post("p2", "Patch 2", "https://publisher.test/p2",
                        "<p>Fixed <b>a crash</b> &amp; <i>a typo</i>.</p><ul><li>One</li><li>Two</li></ul>", "2026-10-02T00:00:00Z"),
                post("p1", "Patch 1", "https://publisher.test/p1", "<p>First.</p>", "2026-10-01T00:00:00Z")));

        var result = fetcher.refreshGame(id).orElseThrow();

        assertThat(result.created()).isEqualTo(2);
        assertThat(storedArticles(id)).isEqualTo(2);
        var row = jdbc.queryForMap("SELECT title, url, summary, article_type FROM article WHERE game_id = ? AND external_id = 'p2'", id);
        assertThat(row.get("title")).isEqualTo("Patch 2");
        assertThat(row.get("url")).isEqualTo("https://publisher.test/p2");
        assertThat(row.get("summary")).isEqualTo("Fixed a crash & a typo. One Two");
        assertThat(row.get("article_type")).isEqualTo("PATCH_NOTES");
    }

    @Test
    void fetchingAgainChangesNothingUntilThePublisherEditsAPost() {
        long id = game("Custompub Single").id();
        when(feeds.readRss(anyString())).thenReturn(List.of(post("p1", "Patch 1", "https://publisher.test/p1", "<p>First.</p>", "2026-10-01T00:00:00Z")));
        fetcher.refreshGame(id);

        assertThat(fetcher.refreshGame(id).orElseThrow().unchanged()).isEqualTo(1);

        when(feeds.readRss(anyString())).thenReturn(List.of(post("p1", "Patch 1", "https://publisher.test/p1", "<p>First, corrected.</p>", "2026-10-01T00:00:00Z")));
        var edited = fetcher.refreshGame(id).orElseThrow();

        assertThat(edited.updated()).isEqualTo(1);
        assertThat(storedArticles(id)).isEqualTo(1); // rewritten in place, not duplicated
    }

    @Test
    void aGameWithTwoFeedsCombinesThemAndCarriesOnWhenOneIsDown() {
        long id = game("Custompub Double").id();
        when(feeds.readRss("https://publisher.test/one.rss")).thenReturn(List.of(
                post("a", "From the first", "https://publisher.test/a", "<p>a</p>", "2026-10-03T00:00:00Z")));
        when(feeds.readHelpCenter("https://publisher.test/two.json")).thenReturn(List.of(
                post("b", "From the second", "https://publisher.test/b", "<p>b</p>", "2026-10-04T00:00:00Z")));

        assertThat(fetcher.refreshGame(id).orElseThrow().created()).isEqualTo(2);

        jdbc.update("DELETE FROM article WHERE game_id = ?", id);
        when(feeds.readHelpCenter("https://publisher.test/two.json")).thenThrow(new PublisherFeedException("Feed publisher.test/two.json answered HTTP 503"));

        assertThat(fetcher.refreshGame(id).orElseThrow().created()).isEqualTo(1); // the feed that is up still counts
    }

    @Test
    void whenEveryFeedIsDownTheFetchFailsSoTheGameBacksOffInsteadOfLookingQuiet() {
        long id = game("Custompub Single").id();
        when(feeds.readRss(anyString())).thenThrow(new PublisherFeedException("Feed publisher.test/single.rss answered HTTP 503"));

        assertThatThrownBy(() -> fetcher.refreshGame(id)).isInstanceOf(PublisherFeedException.class).hasMessageContaining("HTTP 503");
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM game_fetch_state WHERE game_id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void aPostWhoseLinkIsNotHttpsIsNotStored() {
        long id = game("Custompub Single").id();
        when(feeds.readRss(anyString())).thenReturn(List.of(
                post("bad", "Sneaky", "javascript:alert(1)", "<p>x</p>", "2026-10-01T00:00:00Z"),
                post("ok", "Fine", "https://publisher.test/ok", "<p>y</p>", "2026-10-02T00:00:00Z")));

        assertThat(fetcher.refreshGame(id).orElseThrow().created()).isEqualTo(1);
    }

    @Test
    void aPostWhoseIdOrTitleIsLongerThanItsColumnStillStoresAndStaysTheSameOnTheNextFetch() {
        long id = game("Custompub Single").id();
        String longId = "https://publisher.test/a/very/long/address/that/is/the/posts/rss/id/" + "x".repeat(80);
        String longTitle = "T".repeat(700);
        when(feeds.readRss(anyString())).thenReturn(List.of(
                post(longId, longTitle, "https://publisher.test/long", "<p>x</p>", "2026-10-01T00:00:00Z"),
                post("too-long-link", "Link too long", "https://publisher.test/" + "y".repeat(1100), "<p>x</p>", "2026-10-02T00:00:00Z")));

        var first = fetcher.refreshGame(id).orElseThrow();
        var second = fetcher.refreshGame(id).orElseThrow();

        assertThat(first.created()).isEqualTo(1); // the one whose link cannot be stored is skipped, not a failure
        assertThat(second.created()).isZero();
        assertThat(second.unchanged()).isEqualTo(1); // the shortened id is the same every time, so it is not duplicated
        assertThat(jdbc.queryForObject("SELECT LENGTH(title) FROM article WHERE game_id = ?", Integer.class, id)).isLessThanOrEqualTo(500);
    }

    @Test
    void onlyTheNewestPostsAreKept() {
        long id = game("Custompub Single").id();
        var many = new java.util.ArrayList<PublisherPost>();
        for (int i = 0; i < 30; i++) {
            many.add(post("n" + i, "Patch " + i, "https://publisher.test/n" + i, "<p>x</p>", Instant.parse("2026-09-01T00:00:00Z").plusSeconds(i * 60L).toString()));
        }
        when(feeds.readRss(anyString())).thenReturn(many);

        fetcher.refreshGame(id);

        assertThat(storedArticles(id)).isEqualTo(CustomFeedSource.MAX_POSTS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM article WHERE game_id = ? AND external_id = 'n29'", Integer.class, id)).isEqualTo(1);
    }
}
