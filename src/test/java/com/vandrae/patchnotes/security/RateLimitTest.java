package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.users.UserAccounts;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rate limits through the real filter chain, with small limits so a handful of requests reach them. Every test uses
 * its own client address or user, because the limits are shared by the whole class.
 */
@SpringBootTest(properties = {
        "app.security.rate-limit.login.burst=3", "app.security.rate-limit.login.per-minute=1",
        "app.security.rate-limit.callback.burst=2", "app.security.rate-limit.callback.per-minute=1",
        "app.security.rate-limit.watch.burst=3", "app.security.rate-limit.watch.per-minute=1"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class RateLimitTest {

    private static final AtomicLong STEAM_IDS = new AtomicLong(76561198600000000L);

    @Autowired MockMvc mvc;
    @Autowired UserAccounts users;
    @Autowired MeterRegistry meters;
    @MockitoBean SteamNewsClient news; // keeps background fetches off the network

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private RequestPostProcessor newUser() {
        long id = users.findOrCreateBySteamId(STEAM_IDS.incrementAndGet(), "Rate limit tester", null).id();
        return jwt().jwt(token -> token.subject(Long.toString(id)));
    }

    private double refused(String rule) {
        var counter = meters.find("patchnotes.ratelimit.refused").tag("rule", rule).counter();
        return counter == null ? 0 : counter.count();
    }

    // ------------------------------------------------------------------ starting a sign-in

    @Test
    void startingSignInsIsLimitedPerAddressAndTheBrowserIsToldWhy() throws Exception {
        double before = refused("login");
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(SteamLoginController.LOGIN_PATH).with(from("198.51.100.1")))
                    .andExpect(status().isFound())
                    .andExpect(header().string(HttpHeaders.LOCATION, containsString("steamcommunity.com/openid/login")));
        }

        mvc.perform(get(SteamLoginController.LOGIN_PATH).with(from("198.51.100.1")))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, SteamLoginController.RATE_LIMITED_REDIRECT))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, org.hamcrest.Matchers.matchesPattern("\\d+")));

        // somebody else on another address is not affected, and the refusal was counted
        mvc.perform(get(SteamLoginController.LOGIN_PATH).with(from("198.51.100.2")))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("steamcommunity.com/openid/login")));
        assertThat(refused("login")).isEqualTo(before + 1);
    }

    @Test
    void aRefusedSignInStartSetsNoSignInCookies() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(SteamLoginController.LOGIN_PATH).with(from("198.51.100.3")));
        }

        var refused = mvc.perform(get(SteamLoginController.LOGIN_PATH).with(from("198.51.100.3"))).andReturn().getResponse();

        // (the CSRF cookie every API response carries is unrelated: what must be absent are the sign-in cookies)
        assertThat(refused.getCookie(SessionCookies.LOGIN_STATE)).isNull();
        assertThat(refused.getCookie(SessionCookies.LOGIN_NEXT)).isNull();
    }

    // ------------------------------------------------------------------ the callback, the expensive one

    @Test
    void theCallbackHasItsOwnStricterLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(SteamOpenIdService.CALLBACK_PATH).with(from("198.51.100.4")))
                    .andExpect(header().string(HttpHeaders.LOCATION, SteamLoginController.FAILURE_REDIRECT)); // no parameters: rejected normally
        }

        mvc.perform(get(SteamOpenIdService.CALLBACK_PATH).with(from("198.51.100.4")))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, SteamLoginController.RATE_LIMITED_REDIRECT));
    }

    @Test
    void theLoginAndCallbackLimitsAreSeparate() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(SteamOpenIdService.CALLBACK_PATH).with(from("198.51.100.5")));
        }
        mvc.perform(get(SteamOpenIdService.CALLBACK_PATH).with(from("198.51.100.5")))
                .andExpect(header().string(HttpHeaders.LOCATION, SteamLoginController.RATE_LIMITED_REDIRECT));

        // the same address can still start a sign-in
        mvc.perform(get(SteamLoginController.LOGIN_PATH).with(from("198.51.100.5")))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("steamcommunity.com/openid/login")));
    }

    @Test
    void aHeaderTheClientSendsCannotChangeWhichBucketItIsIn() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get(SteamLoginController.LOGIN_PATH).with(from("198.51.100.6")).header("X-Forwarded-For", "10.0." + i + ".1"));
        }

        // pretending to be somebody new in a header does not earn a fresh allowance
        mvc.perform(get(SteamLoginController.LOGIN_PATH).with(from("198.51.100.6")).header("X-Forwarded-For", "203.0.113.99"))
                .andExpect(header().string(HttpHeaders.LOCATION, SteamLoginController.RATE_LIMITED_REDIRECT));
    }

    // ------------------------------------------------------------------ following games

    @Test
    void followingGamesIsLimitedPerUserWithAProblemDocument() throws Exception {
        RequestPostProcessor user = newUser();
        double before = refused("watch");
        for (int i = 0; i < 3; i++) {
            mvc.perform(put("/api/watchlist/{id}", 999_999 + i).with(user).with(csrf())).andExpect(status().isNotFound()); // not limited yet
        }

        mvc.perform(put("/api/watchlist/{id}", 999_999).with(user).with(csrf()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, org.hamcrest.Matchers.matchesPattern("\\d+")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.detail").value(containsString("too quickly")));
        assertThat(refused("watch")).isEqualTo(before + 1);
    }

    @Test
    void theLimitFollowsTheUserNotTheAddressAndCoversRemovingGamesToo() throws Exception {
        RequestPostProcessor user = newUser();
        mvc.perform(put("/api/watchlist/{id}", 999_990).with(user).with(csrf()).with(from("198.51.100.7")));
        mvc.perform(delete("/api/watchlist/{id}", 999_990).with(user).with(csrf()).with(from("198.51.100.8")));
        mvc.perform(put("/api/watchlist/{id}", 999_991).with(user).with(csrf()).with(from("198.51.100.9")));

        // three requests from three addresses used up this one user's allowance
        mvc.perform(delete("/api/watchlist/{id}", 999_991).with(user).with(csrf()).with(from("198.51.100.7")))
                .andExpect(status().isTooManyRequests());
        // while a different user on the same address is untouched
        mvc.perform(put("/api/watchlist/{id}", 999_992).with(newUser()).with(csrf()).with(from("198.51.100.7")))
                .andExpect(status().isNotFound());
    }

    @Test
    void readingIsNeverLimitedAndAnonymousRequestsAreTurnedAwayAsUsualNotCounted() throws Exception {
        RequestPostProcessor user = newUser();
        for (int i = 0; i < 12; i++) {
            mvc.perform(get("/api/watchlist").with(user)).andExpect(status().isOk());
            mvc.perform(get("/api/feed").with(user)).andExpect(status().isOk());
            mvc.perform(put("/api/watchlist/{id}", 1).with(csrf())).andExpect(status().isUnauthorized()); // never 429
        }
    }
}
