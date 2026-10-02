package com.vandrae.patchnotes;

import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.feed.ArticleType;
import com.vandrae.patchnotes.feed.FeedIngestion;
import com.vandrae.patchnotes.feed.IncomingArticle;
import com.vandrae.patchnotes.users.UserAccounts;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Genre and rating filters in Discover ({@code /api/games}) and in the feed, on three games with known attributes:
 * <ul>
 *   <li><b>Alpha</b>: Action + RPG, Overwhelmingly Positive (9)</li>
 *   <li><b>Beta</b>: Strategy, Mostly Positive (6)</li>
 *   <li><b>Gamma</b>: no genres and no rating (details not fetched yet, or no reviews)</li>
 * </ul>
 * Fixture app ids start at 950,000,000.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class GenreAndRatingFilterTest {

    private static final long BASE_ID = 950_000_000L;
    private static final AtomicLong STEAM_IDS = new AtomicLong(76561198300000000L);

    @Autowired MockMvc mvc;
    @Autowired UserAccounts users;
    @Autowired FeedIngestion feedIngestion;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SteamNewsClient steam;

    private long alpha;
    private long beta;
    private long gamma;
    private RequestPostProcessor user;

    @BeforeEach
    void fixtures() throws Exception {
        String mine = "(SELECT id FROM game WHERE steam_app_id >= ? AND steam_app_id < ?)";
        jdbc.update("DELETE FROM article WHERE game_id IN " + mine, BASE_ID, BASE_ID + 1_000);
        jdbc.update("DELETE FROM watchlist_entry WHERE game_id IN " + mine, BASE_ID, BASE_ID + 1_000);
        jdbc.update("DELETE FROM game WHERE steam_app_id >= ? AND steam_app_id < ?", BASE_ID, BASE_ID + 1_000);
        alpha = game(1, "Filtertest Alpha", 9, 97, "ACTION", "RPG");
        beta = game(2, "Filtertest Beta", 6, 74, "STRATEGY");
        gamma = game(3, "Filtertest Gamma", 0, null);

        user = asNewUser("Filter tester");
        for (long id : List.of(alpha, beta, gamma)) {
            mvc.perform(put("/api/watchlist/{id}", id).with(user).with(csrf())).andExpect(status().isCreated());
            feedIngestion.ingest(id, List.of(new IncomingArticle("art-" + id, "Patch for " + id, "https://example.test/" + id,
                    "summary", ArticleType.PATCH_NOTES, Instant.parse("2026-09-30T00:00:00Z"), "h")));
        }
    }

    private RequestPostProcessor asNewUser(String name) {
        long userId = users.findOrCreateBySteamId(STEAM_IDS.incrementAndGet(), name, null).id();
        return jwt().jwt(token -> token.subject(Long.toString(userId)));
    }

    private long game(int n, String name, int score, Integer percent, String... genres) {
        jdbc.update("INSERT INTO game (name, name_search, steam_app_id, source_type, created_at, review_score, percent_positive) "
                + "VALUES (?, ?, ?, 'STEAM_NEWS', CURRENT_TIMESTAMP, ?, ?)", name, name.toLowerCase(), BASE_ID + n, score, percent);
        Long id = jdbc.queryForObject("SELECT id FROM game WHERE steam_app_id = ?", Long.class, BASE_ID + n);
        for (String genre : genres) {
            jdbc.update("INSERT INTO game_genre (game_id, genre) VALUES (?, ?)", id, genre);
        }
        return id;
    }

    // ---------------------------------------------------------------- Discover

    @Test
    void discoverFiltersByGenre_anyOfTheChosenGenres() throws Exception {
        mvc.perform(get("/api/games").param("q", "filtertest").param("genre", "ACTION").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Filtertest Alpha"));
        mvc.perform(get("/api/games").param("q", "filtertest").param("genre", "STRATEGY", "RPG").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mvc.perform(get("/api/games").param("q", "filtertest").param("genre", "STRATEGY,RPG").with(user)) // comma form too
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mvc.perform(get("/api/games").param("q", "filtertest").param("genre", "RACING").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void discoverFiltersByMinimumRating_andAGameWithoutReviewsNeverPassesARatingFilter() throws Exception {
        mvc.perform(get("/api/games").param("q", "filtertest").param("minRating", "6").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mvc.perform(get("/api/games").param("q", "filtertest").param("minRating", "7").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Filtertest Alpha"));
        mvc.perform(get("/api/games").param("q", "filtertest").param("minRating", "0").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(3)); // 0 = any, including unrated
    }

    @Test
    void genreAndRatingCombine_andApplyWithoutASearchTextToo() throws Exception {
        mvc.perform(get("/api/games").param("q", "filtertest").param("genre", "STRATEGY").param("minRating", "7").with(user))
                .andExpect(jsonPath("$.page.totalElements").value(0));
        // blank query = browsing the whole catalog; the filter still narrows it
        mvc.perform(get("/api/games").param("genre", "RPG").param("minRating", "9").param("size", "50").with(user))
                .andExpect(jsonPath("$.content[?(@.name == 'Filtertest Alpha')]").isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.name == 'Filtertest Beta')]").isEmpty());
    }

    @Test
    void gamesCarryTheirGenresAndRating() throws Exception {
        mvc.perform(get("/api/games/{id}", alpha).with(user))
                .andExpect(jsonPath("$.genres.length()").value(2))
                .andExpect(jsonPath("$.genres[0]").value("ACTION"))
                .andExpect(jsonPath("$.genres[1]").value("RPG"))
                .andExpect(jsonPath("$.rating.score").value(9))
                .andExpect(jsonPath("$.rating.label").value("Overwhelmingly Positive"))
                .andExpect(jsonPath("$.rating.percentPositive").value(97));
        mvc.perform(get("/api/games/{id}", gamma).with(user))
                .andExpect(jsonPath("$.genres").isEmpty())
                .andExpect(jsonPath("$.rating").doesNotExist());
    }

    @Test
    void unknownGenresAreRejectedNotSilentlyIgnored() throws Exception {
        mvc.perform(get("/api/games").param("genre", "BOGUS").with(user)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/feed").param("genre", "BOGUS").with(user)).andExpect(status().isBadRequest());
    }

    @Test
    void theFilterListsAreServedForTheUi() throws Exception {
        mvc.perform(get("/api/catalog/filters").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres.length()").value(10))
                .andExpect(jsonPath("$.genres[?(@.code == 'MASSIVELY_MULTIPLAYER')].label").value("Massively Multiplayer"))
                .andExpect(jsonPath("$.ratings.length()").value(4))
                .andExpect(jsonPath("$.ratings[0].minRating").value(6))
                .andExpect(jsonPath("$.ratings[0].label").value("Mostly Positive"))
                .andExpect(jsonPath("$.ratings[3].label").value("Overwhelmingly Positive"));
    }

    // ---------------------------------------------------------------- feed

    @Test
    void theFeedCanBeNarrowedToWatchedGamesOfAGenre() throws Exception {
        mvc.perform(get("/api/feed").with(user)).andExpect(jsonPath("$.totalItems").value(3));
        mvc.perform(get("/api/feed").param("genre", "ACTION").with(user))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].gameId").value(alpha));
        mvc.perform(get("/api/feed").param("genre", "STRATEGY").param("genre", "ACTION").with(user))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void theFeedCanBeNarrowedByRating_andFiltersComposeWithTheGameFilter() throws Exception {
        mvc.perform(get("/api/feed").param("minRating", "6").with(user)).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/feed").param("minRating", "9").with(user))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].gameId").value(alpha));
        // a specific game that does not pass the filter yields nothing, not that game's notes
        mvc.perform(get("/api/feed").param("minRating", "9").param("gameId", Long.toString(beta)).with(user))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void whenNoWatchedGameMatchesTheFeedSaysSoInsteadOfClaimingThereAreNoPatchNotes() throws Exception {
        mvc.perform(get("/api/feed").param("genre", "RACING").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyState.reason").value("NO_MATCHING_GAMES"));
    }

    @Test
    void aFilterNeverRevealsAnotherUsersWatchlist() throws Exception {
        mvc.perform(get("/api/feed").param("genre", "ACTION").with(asNewUser("Stranger")))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.emptyState.reason").value("NO_WATCHLIST"));
    }

    @Test
    void watchlistGamesCarryGenresAndRatingSoTheUiCanFilterItsOwnChips() throws Exception {
        mvc.perform(get("/api/watchlist").with(user))
                .andExpect(jsonPath("$[?(@.game.name == 'Filtertest Beta')].game.genres[0]").value("STRATEGY"))
                .andExpect(jsonPath("$[?(@.game.name == 'Filtertest Beta')].game.rating.label").value("Mostly Positive"));
    }
}
