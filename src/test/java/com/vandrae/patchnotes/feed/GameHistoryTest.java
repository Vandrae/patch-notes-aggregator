package com.vandrae.patchnotes.feed;

import com.vandrae.patchnotes.catalog.SourceType;
import com.vandrae.patchnotes.catalog.internal.Game;
import com.vandrae.patchnotes.catalog.internal.GameRepository;
import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.users.UserAccounts;
import com.vandrae.patchnotes.users.WatchlistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The data behind a game's page: its patch-note history, and who is around it. Fixture app ids start at 996,000,000. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class GameHistoryTest {

    private static final AtomicLong STEAM_IDS = new AtomicLong(76561198800000000L);
    private static final AtomicLong APP_IDS = new AtomicLong(996_000_000L);

    @Autowired MockMvc mvc;
    @Autowired UserAccounts users;
    @Autowired GameRepository games;
    @Autowired FeedIngestion ingestion;
    @Autowired WatchlistService watchlist;
    @MockitoBean SteamNewsClient steam; // keeps the fetch after following off the network

    private RequestPostProcessor user() {
        long id = users.findOrCreateBySteamId(STEAM_IDS.incrementAndGet(), "History tester", null).id();
        return jwt().jwt(token -> token.subject(Long.toString(id)));
    }

    private long userId() {
        return users.findOrCreateBySteamId(STEAM_IDS.incrementAndGet(), "Follower", null).id();
    }

    private long newGame() {
        long appId = APP_IDS.incrementAndGet();
        return games.save(new Game("History fixture " + appId, appId, SourceType.STEAM_NEWS)).getId();
    }

    private void addPatchNotes(long gameId, int count) {
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < count; i++) {
            ingestion.ingest(gameId, List.of(new IncomingArticle("ext-" + gameId + "-" + i, "Patch " + i,
                    "https://example.test/" + i, "summary " + i, ArticleType.PATCH_NOTES, base.plusSeconds(i * 3600L), "hash" + i)));
        }
    }

    @Test
    void anyoneSignedInCanReadAGamesPatchNotesNewestFirstAndPageThroughThem() throws Exception {
        long game = newGame();
        addPatchNotes(game, 5);

        mvc.perform(get("/api/games/{id}/patch-notes", game).param("size", "2").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("Patch 4")) // newest first
                .andExpect(jsonPath("$.items[1].title").value("Patch 3"))
                .andExpect(jsonPath("$.items[0].gameName").value(org.hamcrest.Matchers.startsWith("History fixture")));
        mvc.perform(get("/api/games/{id}/patch-notes", game).param("size", "2").param("page", "2").with(user()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Patch 0"));
    }

    @Test
    void itOnlyHoldsThatGamesNotes() throws Exception {
        long game = newGame();
        long other = newGame();
        addPatchNotes(game, 2);
        addPatchNotes(other, 3);

        mvc.perform(get("/api/games/{id}/patch-notes", game).with(user()))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void aGameNobodyFollowsSaysWhyItHasNothing() throws Exception {
        long game = newGame();

        mvc.perform(get("/api/games/{id}/patch-notes", game).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.emptyState.reason").value("NOT_TRACKED"))
                .andExpect(jsonPath("$.emptyState.message").value(org.hamcrest.Matchers.containsString("Follow it")));
    }

    @Test
    void aFollowedGameWithNothingYetSaysSoDifferently() throws Exception {
        long game = newGame();
        watchlist.watch(userId(), game);

        mvc.perform(get("/api/games/{id}/patch-notes", game).with(user()))
                .andExpect(jsonPath("$.emptyState.reason").value("NO_ARTICLES_YET"));
    }

    @Test
    void theActivityCountsFollowersAndShowsTheNewestPatchWithoutNamingAnyone() throws Exception {
        long game = newGame();
        addPatchNotes(game, 3);
        watchlist.watch(userId(), game);
        watchlist.watch(userId(), game);

        mvc.perform(get("/api/games/{id}/activity", game).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.watcherCount").value(2))
                .andExpect(jsonPath("$.patchNoteCount").value(3))
                .andExpect(jsonPath("$.latestPatchAt").value("2026-09-01T02:00:00Z"))
                .andExpect(jsonPath("$.length()").value(3)); // a count and two facts: nothing that identifies a follower
    }

    @Test
    void theActivityOfAQuietGameHasNoLatestPatch() throws Exception {
        long game = newGame();

        mvc.perform(get("/api/games/{id}/activity", game).with(user()))
                .andExpect(jsonPath("$.watcherCount").value(0))
                .andExpect(jsonPath("$.patchNoteCount").value(0))
                .andExpect(jsonPath("$.latestPatchAt").doesNotExist());
    }

    @Test
    void anUnknownGameIsNotFoundAndAnonymousCallersAreTurnedAway() throws Exception {
        mvc.perform(get("/api/games/{id}/patch-notes", 999_999_999).with(user())).andExpect(status().isNotFound());
        mvc.perform(get("/api/games/{id}/activity", 999_999_999).with(user())).andExpect(status().isNotFound());

        long game = newGame();
        mvc.perform(get("/api/games/{id}/patch-notes", game)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/games/{id}/activity", game)).andExpect(status().isUnauthorized());
    }
}
