package com.vandrae.patchnotes;

import com.jayway.jsonpath.JsonPath;
import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.externalapi.SteamNewsItem;
import com.vandrae.patchnotes.feed.ArticleType;
import com.vandrae.patchnotes.feed.FeedIngestion;
import com.vandrae.patchnotes.feed.IncomingArticle;
import com.vandrae.patchnotes.feed.IngestResult;
import com.vandrae.patchnotes.users.UserAccounts;
import com.vandrae.patchnotes.users.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Drives the real application (security, JPA, Flyway, events) over HTTP; only Steam itself is faked. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class PatchNotesFlowTest {

    private static final AtomicLong STEAM_IDS = new AtomicLong(76561198200000000L);

    @Autowired MockMvc mvc;
    @Autowired UserAccounts users;
    @Autowired FeedIngestion feedIngestion;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SteamNewsClient steam;

    // ---------------------------------------------------------------- security

    @Test
    void everyApiRouteRequiresASignedInUser() throws Exception {
        for (String path : List.of("/api/feed", "/api/watchlist", "/api/games", "/api/games/1", "/api/me")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(put("/api/watchlist/1").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/feed").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void theWebAppShellIsServedAtClientRoutesButUnknownApiRoutesStayNotFound() throws Exception {
        for (String route : List.of("/", "/feed", "/discover", "/watchlist", "/account", "/login", "/some-unknown-page")) {
            mvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
        // an API typo must not come back as an HTML page with a 200
        mvc.perform(get("/api/does-not-exist").with(signedIn())).andExpect(status().isNotFound());
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheHealthEndpointIsExposed() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------- catalog

    @Test
    void catalogSearchesByNameCaseInsensitively() throws Exception {
        RequestPostProcessor user = signedIn();

        mvc.perform(get("/api/games").with(user)).andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        mvc.perform(get("/api/games").param("q", "DRAGONWILDS").with(user))
                .andExpect(jsonPath("$.content[0].steamAppId").value(1374490));
        // every result carries the fields the UI uses for the cover and the description (null until details are fetched)
        mvc.perform(get("/api/games").param("q", "DRAGONWILDS").with(user))
                .andExpect(jsonPath("$.content[0].imageUrl").hasJsonPath())
                .andExpect(jsonPath("$.content[0].shortDescription").hasJsonPath());
        mvc.perform(get("/api/catalog/status").with(user))
                .andExpect(jsonPath("$.detailsLoaded").isNumber())
                .andExpect(jsonPath("$.loadingDetails").value(false));
        mvc.perform(get("/api/games").param("q", "runescape: dragon-wilds").with(user)) // punctuation doesn't matter
                .andExpect(jsonPath("$.page.totalElements").value(0)); // ...but "dragon wilds" is not "dragonwilds"
        mvc.perform(get("/api/games").param("q", "runescape dragonwilds").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/catalog/status").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.games").isNumber())
                .andExpect(jsonPath("$.syncing").value(false));
        mvc.perform(get("/api/games").param("q", "DRAGONWILDS").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceType").value("STEAM_NEWS"));
        mvc.perform(get("/api/games").param("q", "no-such-game-xyz").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        // '%' must be matched literally, not act as "match everything"
        mvc.perform(get("/api/games").param("q", "%").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    // ---------------------------------------------- watchlist -> fetch -> feed

    @Test
    void watchingAGameFetchesNormalizesAndServesItsPatchNotesOnlyToWatchers() throws Exception {
        when(steam.getNewsForApp(anyLong())).thenReturn(List.of(
                steamItem("201", "0.12.0.4 is live!", 1, List.of("patchnotes"), 1790676675L,
                        "[list][*][p]Resolved an issue where various fishing locations were unusable.[/p][/*][/list]"),
                steamItem("202", "0.12.1 is now live!", 1, null, 1790000000L, "<b>Fish</b> &amp; chips"),
                steamItem("203", "Dragonwilds 1.0.0.4 patch notes explained", 0, null, 1790700000L, "press"),
                steamItem("204", "Our 1.0 Check-In Survey is now Live!", 1, null, 1790800000L, "survey")));

        RequestPostProcessor alice = signedIn();
        RequestPostProcessor bob = signedIn();
        long gameId = dragonwildsId(alice);
        // the catalog knows this game's Steam icon; every feed item for it must carry the full https URL
        jdbc.update("UPDATE game SET icon_path = '1374490/8e307e43b46f4dc3554487c491015ba2b24ff235.jpg' WHERE steam_app_id = 1374490");

        // brand-new user: a clean empty state, not an error
        mvc.perform(get("/api/feed").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyState.reason").value("NO_WATCHLIST"));
        mvc.perform(get("/api/watchlist").with(alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        // unknown game
        mvc.perform(put("/api/watchlist/999999").with(alice).with(csrf())).andExpect(status().isNotFound());

        // watch: 201 first time, 204 when repeated
        mvc.perform(put("/api/watchlist/{id}", gameId).with(alice).with(csrf())).andExpect(status().isCreated());
        mvc.perform(put("/api/watchlist/{id}", gameId).with(alice).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/watchlist").with(alice))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].game.name").value("RuneScape: Dragonwilds"));

        // the GameWatched event triggers an asynchronous fetch; wait for it to land in the feed
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mvc.perform(get("/api/feed").with(alice)).andExpect(jsonPath("$.totalItems").value(2)));

        // only the 2 real patch notes survived normalization (press + survey dropped), newest first, plain-text summaries
        mvc.perform(get("/api/feed").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emptyState").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("0.12.0.4 is live!"))
                .andExpect(jsonPath("$.items[0].gameName").value("RuneScape: Dragonwilds"))
                .andExpect(jsonPath("$.items[0].gameIconUrl").value("https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/1374490/8e307e43b46f4dc3554487c491015ba2b24ff235.jpg"))
                .andExpect(jsonPath("$.items[1].gameIconUrl").value("https://cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/1374490/8e307e43b46f4dc3554487c491015ba2b24ff235.jpg"))
                .andExpect(jsonPath("$.items[0].type").value("PATCH_NOTES"))
                .andExpect(jsonPath("$.items[0].publishedAt").value("2026-09-29T10:11:15Z")) // epoch 1790676675
                .andExpect(jsonPath("$.items[0].summary").value("Resolved an issue where various fishing locations were unusable."))
                .andExpect(jsonPath("$.items[1].title").value("0.12.1 is now live!"))
                .andExpect(jsonPath("$.items[1].summary").value("Fish & chips"));

        // Bob follows nothing, so he sees none of it: the feed is scoped to the caller
        mvc.perform(get("/api/feed").with(bob))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyState.reason").value("NO_WATCHLIST"));

        // paging
        mvc.perform(get("/api/feed").param("size", "1").param("page", "1").with(alice))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("0.12.1 is now live!"))
                .andExpect(jsonPath("$.totalPages").value(2));

        // per-game filter: the watched game works; a game you don't watch reveals nothing and is not an error
        mvc.perform(get("/api/feed").param("gameId", Long.toString(gameId)).with(alice))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/feed").param("gameId", "999999").with(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyState.reason").value("NO_ARTICLES_YET"));
        mvc.perform(get("/api/feed").param("gameId", Long.toString(gameId)).with(bob)) // Bob watches nothing
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyState.reason").value("NO_WATCHLIST"));

        // ingestion is idempotent, and a silent edit updates the row in place instead of duplicating it
        IncomingArticle existing = new IncomingArticle("201", "0.12.0.4 is live!", "https://example.test/x", "s",
                ArticleType.PATCH_NOTES, Instant.parse("2026-09-29T10:11:15Z"), "hash-v1");
        assertThat(feedIngestion.ingest(gameId, List.of(existing)).created()).isEqualTo(0); // 201 already stored by the fetch
        IncomingArticle brandNew = new IncomingArticle("901", "9.9.9 is live", "https://example.test/y", "s",
                ArticleType.PATCH_NOTES, Instant.parse("2026-10-01T00:00:00Z"), "hash-v1");
        assertThat(feedIngestion.ingest(gameId, List.of(brandNew))).isEqualTo(new IngestResult(1, 0, 0));
        assertThat(feedIngestion.ingest(gameId, List.of(brandNew))).isEqualTo(new IngestResult(0, 0, 1));
        IncomingArticle edited = new IncomingArticle("901", "9.9.9 is live", "https://example.test/y", "s + hotfix",
                ArticleType.PATCH_NOTES, Instant.parse("2026-10-01T00:00:00Z"), "hash-v2");
        assertThat(feedIngestion.ingest(gameId, List.of(edited))).isEqualTo(new IngestResult(0, 1, 0));
        mvc.perform(get("/api/feed").with(alice))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].title").value("9.9.9 is live"))
                .andExpect(jsonPath("$.items[0].summary").value("s + hotfix"));

        // unwatching is idempotent and empties the feed again
        mvc.perform(delete("/api/watchlist/{id}", gameId).with(alice).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/watchlist/{id}", gameId).with(alice).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/feed").with(alice))
                .andExpect(jsonPath("$.emptyState.reason").value("NO_WATCHLIST"));
    }

    @Test
    void summariesStoredWithTheOldEscapedBracketBugAreRewrittenInPlaceOnTheNextFetch() throws Exception {
        // a second game, so the Dragonwilds counts in the main flow test are untouched
        long appId = 1_695_999L;
        jdbc.update("INSERT INTO game (name, name_search, steam_app_id, source_type, created_at) VALUES (?, ?, ?, 'STEAM_NEWS', CURRENT_TIMESTAMP)",
                "Bracket Test", "bracket test", appId);
        long gameId = jdbc.queryForObject("SELECT id FROM game WHERE steam_app_id = ?", Long.class, appId);
        // exactly what the earlier version stored: the backslash left in, under the old hash
        feedIngestion.ingest(gameId, List.of(new IncomingArticle("301", "Counter Update 1.2.3", "https://example.test/301",
                "\\[ RUSH ] Various fixes", ArticleType.PATCH_NOTES, Instant.parse("2026-09-01T00:00:00Z"), "hash-from-before-the-fix")));
        // Steam's text has NOT changed since: it is the real Counter-Strike 2 shape, with an escaped bracket
        when(steam.getNewsForApp(appId)).thenReturn(List.of(
                steamItem("301", "Counter Update 1.2.3", 1, List.of("patchnotes"), 1_788_220_800L,
                        "[p]\\[ RUSH ][/p][list][*][p]Various fixes[/p][/*][/list]")));
        RequestPostProcessor user = signedIn();

        mvc.perform(put("/api/watchlist/{id}", gameId).with(user).with(csrf())).andExpect(status().isCreated());

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                mvc.perform(get("/api/feed").param("gameId", Long.toString(gameId)).with(user))
                        .andExpect(jsonPath("$.items[0].summary").value("[ RUSH ] Various fixes")));
        mvc.perform(get("/api/feed").param("gameId", Long.toString(gameId)).with(user))
                .andExpect(jsonPath("$.totalItems").value(1)); // rewritten in place, not duplicated
    }

    @Test
    void deletingYourAccountAlsoRemovesYourWatchlist() throws Exception {
        UserSummary user = users.findOrCreateBySteamId(STEAM_IDS.incrementAndGet(), "Leaver", null);
        RequestPostProcessor leaver = asUser(user);
        when(steam.getNewsForApp(anyLong())).thenReturn(List.of());
        mvc.perform(put("/api/watchlist/{id}", dragonwildsId(leaver)).with(leaver).with(csrf())).andExpect(status().isCreated());

        mvc.perform(delete("/api/me").with(leaver).with(csrf())).andExpect(status().isNoContent());

        assertThat(users.findById(user.id())).isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private static SteamNewsItem steamItem(String gid, String title, int feedType, List<String> tags, long epoch, String contents) {
        return new SteamNewsItem(gid, title, "https://example.test/news/" + gid, true, "Dev", contents,
                "Community Announcements", epoch, "steam_community_announcements", feedType, 1374490L, tags);
    }

    private RequestPostProcessor signedIn() {
        return asUser(users.findOrCreateBySteamId(STEAM_IDS.incrementAndGet(), "Test user", null));
    }

    private static RequestPostProcessor asUser(UserSummary user) {
        return jwt().jwt(token -> token.subject(Long.toString(user.id())));
    }

    private long dragonwildsId(RequestPostProcessor user) throws Exception {
        String body = mvc.perform(get("/api/games").param("q", "dragonwilds").with(user))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.content[0].id")).longValue();
    }
}
