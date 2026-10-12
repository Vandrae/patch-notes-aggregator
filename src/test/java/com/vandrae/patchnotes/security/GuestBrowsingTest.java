package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guest mode: somebody who has not signed in may LOOK (the catalog, a game's page and its patch notes) but nothing that is
 * about a person, and nothing that changes anything.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class GuestBrowsingTest {

    @Autowired MockMvc mvc;
    @MockitoBean SteamNewsClient news; // keeps background fetches off the network

    @Test
    void aGuestCanSearchTheCatalogAndSeeItsFilterListsAndStatus() throws Exception {
        mvc.perform(get("/api/games").param("q", "a").param("size", "5")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mvc.perform(get("/api/catalog/filters")).andExpect(status().isOk()).andExpect(jsonPath("$.genres").isArray());
        mvc.perform(get("/api/catalog/status")).andExpect(status().isOk());
    }

    @Test
    void aGuestGetsANotFoundNotASignInDemandForAGameThatDoesNotExist() throws Exception {
        mvc.perform(get("/api/games/{id}", 999_999_999)).andExpect(status().isNotFound());
    }

    @Test
    void theFeedTheWatchlistAndTheAccountStayBehindSteamSignIn() throws Exception {
        for (String path : List.of("/api/feed", "/api/watchlist", "/api/me")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aGuestCanChangeNothing() throws Exception {
        mvc.perform(put("/api/watchlist/1").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/watchlist/1").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/me").with(csrf())).andExpect(status().isUnauthorized());
        // and only reads were opened: writing to a public route is not allowed either
        mvc.perform(post("/api/games").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/games/1").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/games/1").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheListedReadsAreOpenAndNothingElseUnderApiIs() throws Exception {
        for (String path : List.of("/api/games/1/anything-else", "/api/games/1/patch-notes/extra", "/api/catalog",
                "/api/catalog/other", "/api/does-not-exist", "/api/auth", "/api/games/1/2/3")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
