package com.vandrae.patchnotes.users;

import com.vandrae.patchnotes.catalog.SourceType;
import com.vandrae.patchnotes.catalog.internal.Game;
import com.vandrae.patchnotes.catalog.internal.GameRepository;
import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One person can only follow so many games; the limit is small here so a few requests reach it. */
@SpringBootTest(properties = "app.watchlist.max-games=2")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class WatchlistLimitTest {

    private static final AtomicLong STEAM_IDS = new AtomicLong(76561198700000000L);
    private static final AtomicLong APP_IDS = new AtomicLong(970_000_000L);

    @Autowired MockMvc mvc;
    @Autowired UserAccounts users;
    @Autowired GameRepository games;
    @MockitoBean SteamNewsClient steam; // keeps the fetch after watching off the network

    private RequestPostProcessor newUser() {
        long id = users.findOrCreateBySteamId(STEAM_IDS.incrementAndGet(), "Limit tester", null).id();
        return jwt().jwt(token -> token.subject(Long.toString(id)));
    }

    private long newGame() {
        long appId = APP_IDS.incrementAndGet();
        return games.save(new Game("Limit fixture " + appId, appId, SourceType.STEAM_NEWS)).getId();
    }

    @Test
    void aFullListRefusesAnotherGameButStillAcceptsOnesAlreadyOnIt() throws Exception {
        RequestPostProcessor user = newUser();
        long first = newGame(), second = newGame(), third = newGame();
        mvc.perform(put("/api/watchlist/{id}", first).with(user).with(csrf())).andExpect(status().isCreated());
        mvc.perform(put("/api/watchlist/{id}", second).with(user).with(csrf())).andExpect(status().isCreated());

        mvc.perform(put("/api/watchlist/{id}", third).with(user).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("up to 2 games")));

        // repeating a game already followed is still the harmless 204, not a refusal
        mvc.perform(put("/api/watchlist/{id}", first).with(user).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void removingAGameMakesRoomAndTheLimitIsPerPerson() throws Exception {
        RequestPostProcessor user = newUser();
        long first = newGame(), second = newGame(), third = newGame();
        mvc.perform(put("/api/watchlist/{id}", first).with(user).with(csrf())).andExpect(status().isCreated());
        mvc.perform(put("/api/watchlist/{id}", second).with(user).with(csrf())).andExpect(status().isCreated());
        mvc.perform(put("/api/watchlist/{id}", third).with(user).with(csrf())).andExpect(status().isConflict());

        mvc.perform(delete("/api/watchlist/{id}", first).with(user).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(put("/api/watchlist/{id}", third).with(user).with(csrf())).andExpect(status().isCreated());

        // somebody else's list is separate
        mvc.perform(put("/api/watchlist/{id}", first).with(newUser()).with(csrf())).andExpect(status().isCreated());
    }
}
