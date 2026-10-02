package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.externalapi.SteamPlayer;
import com.vandrae.patchnotes.externalapi.SteamProfileClient;
import com.vandrae.patchnotes.users.UserAccounts;
import com.vandrae.patchnotes.users.UserSummary;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The browser-facing half of Steam login, session cookies and CSRF, through the real filter chain.
 * Steam itself is faked: the OpenID verifier is a spy (its redirect building stays real, its call to
 * Steam is stubbed per test) and the profile API is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class SteamLoginFlowTest {

    private static final AtomicLong STEAM_IDS = new AtomicLong(76561198100000000L);

    @Autowired MockMvc mvc;
    @Autowired UserAccounts users;
    @Autowired TokenService tokens;
    @Autowired JwtEncoder encoder;
    @MockitoSpyBean SteamOpenIdService openId;
    @MockitoBean SteamProfileClient profiles;
    @MockitoBean SteamNewsClient news; // keeps background fetches off the network

    // ---------------------------------------------------------------- start of login

    @Test
    void loginSendsTheBrowserToSteamAndRemembersTheAttemptInCookies() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/steam/login").param("next", "/games/7"))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith("https://steamcommunity.com/openid/login?").contains("openid.mode=checkid_setup");
        Cookie state = result.getResponse().getCookie(SessionCookies.LOGIN_STATE);
        assertThat(state).isNotNull();
        assertThat(state.isHttpOnly()).isTrue();
        assertThat(state.getValue()).hasSize(48);
        // the same state is baked into the return address Steam will call back (URL-encoded inside the query)
        assertThat(location).contains("state%3D" + state.getValue());
        assertThat(result.getResponse().getCookie(SessionCookies.LOGIN_NEXT).getValue()).isEqualTo("/games/7");
    }

    @Test
    void anAttackerSuppliedNextUrlIsNeutralised() throws Exception {
        for (String evil : List.of("https://evil.test", "//evil.test/x", "/\\evil.test")) {
            MvcResult result = mvc.perform(get("/api/auth/steam/login").param("next", evil)).andExpect(status().isFound()).andReturn();
            assertThat(result.getResponse().getCookie(SessionCookies.LOGIN_NEXT).getValue()).as(evil).isEqualTo("/");
        }
    }

    @Test
    void aStaleSessionCookieDoesNotBlockSigningInAgain() throws Exception {
        mvc.perform(get("/api/auth/steam/login").cookie(new Cookie(SessionCookies.SESSION, "expired.or.garbage")))
                .andExpect(status().isFound());
        mvc.perform(get("/api/me").cookie(new Cookie(SessionCookies.SESSION, "expired.or.garbage")))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- the callback

    @Test
    void aVerifiedCallbackCreatesTheUserSetsAHardenedSessionCookieAndRedirectsOn() throws Exception {
        long steamId = STEAM_IDS.incrementAndGet();
        doReturn(Optional.of(steamId)).when(openId).verify(any(), eq("s1"));
        when(profiles.getPlayer(steamId)).thenReturn(Optional.of(new SteamPlayer("Gabe", "https://avatars.test/g.jpg")));

        MvcResult result = mvc.perform(get("/api/auth/steam/callback").param("state", "s1")
                        .cookie(new Cookie(SessionCookies.LOGIN_STATE, "s1"), new Cookie(SessionCookies.LOGIN_NEXT, "/games/7")))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/games/7"))
                .andReturn();

        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        String session = setCookies.stream().filter(c -> c.startsWith(SessionCookies.SESSION + "=")).findFirst().orElseThrow();
        assertThat(session).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(setCookies).anyMatch(c -> c.startsWith(SessionCookies.LOGIN_STATE + "=;") || c.contains(SessionCookies.LOGIN_STATE + "=; Max-Age=0"));

        // the cookie alone now authenticates API calls
        String token = session.substring((SessionCookies.SESSION + "=").length(), session.indexOf(';'));
        mvc.perform(get("/api/me").cookie(new Cookie(SessionCookies.SESSION, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personaName").value("Gabe"))
                .andExpect(jsonPath("$.avatarUrl").value("https://avatars.test/g.jpg"))
                .andExpect(jsonPath("$.steamId").value(Long.toString(steamId))) // a string: > 2^53 would corrupt as a JSON number
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void signingInExpiresAnyEarlierCsrfTokenSoTheNewSessionStartsFresh() throws Exception {
        long steamId = STEAM_IDS.incrementAndGet();
        doReturn(Optional.of(steamId)).when(openId).verify(any(), any());
        when(profiles.getPlayer(steamId)).thenReturn(Optional.empty());

        MvcResult result = mvc.perform(get("/api/auth/steam/callback").param("state", "f1")
                        .cookie(new Cookie(SessionCookies.LOGIN_STATE, "f1"), new Cookie("XSRF-TOKEN", "planted-before-login")))
                .andExpect(status().isFound())
                .andReturn();

        Cookie csrf = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        assertThat(csrf.getMaxAge()).as("expired: the browser drops it and the next request is issued a new one").isZero();
        assertThat(csrf.isHttpOnly()).isFalse();
    }

    @Test
    void aFailedSignInDoesNotTouchTheCsrfToken() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/steam/callback").param("state", "attacker")
                        .cookie(new Cookie(SessionCookies.LOGIN_STATE, "victim")))
                .andExpect(status().isFound()).andReturn();

        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNull();
    }

    @Test
    void signingInAgainReusesTheAccountAndRefreshesTheName() throws Exception {
        long steamId = STEAM_IDS.incrementAndGet();
        doReturn(Optional.of(steamId)).when(openId).verify(any(), any());
        when(profiles.getPlayer(steamId)).thenReturn(Optional.of(new SteamPlayer("Old Name", null)));
        signIn("a");
        when(profiles.getPlayer(steamId)).thenReturn(Optional.of(new SteamPlayer("New Name", null)));
        signIn("b");

        UserSummary user = users.findOrCreateBySteamId(steamId, null, null); // lookup "failed": keeps what we have
        assertThat(user.personaName()).isEqualTo("New Name");
    }

    @Test
    void aFailedProfileLookupStillSignsTheUserInWithAGenericName() throws Exception {
        long steamId = STEAM_IDS.incrementAndGet();
        doReturn(Optional.of(steamId)).when(openId).verify(any(), any());
        when(profiles.getPlayer(steamId)).thenReturn(Optional.empty());

        signIn("x");

        assertThat(users.findOrCreateBySteamId(steamId, null, null).personaName()).isEqualTo("Steam user");
    }

    @Test
    void aCallbackFromADifferentBrowserIsRejectedWithoutAskingSteam() throws Exception {
        // state in the URL does not match the state cookie: someone else's login being replayed into this browser
        MvcResult result = mvc.perform(get("/api/auth/steam/callback").param("state", "attacker")
                        .cookie(new Cookie(SessionCookies.LOGIN_STATE, "victim")))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, SteamLoginController.FAILURE_REDIRECT))
                .andReturn();

        assertThat(result.getResponse().getCookie(SessionCookies.SESSION)).isNull();
        verify(openId, never()).verify(any(), any());
    }

    @Test
    void aCallbackWithNoStateCookieIsRejected() throws Exception {
        mvc.perform(get("/api/auth/steam/callback").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, SteamLoginController.FAILURE_REDIRECT));
        verify(openId, never()).verify(any(), any());
    }

    @Test
    void aResponseSteamDoesNotVouchForDoesNotSignAnyoneIn() throws Exception {
        doReturn(Optional.empty()).when(openId).verify(any(), any());

        MvcResult result = mvc.perform(get("/api/auth/steam/callback").param("state", "s2")
                        .cookie(new Cookie(SessionCookies.LOGIN_STATE, "s2")))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, SteamLoginController.FAILURE_REDIRECT))
                .andReturn();
        assertThat(result.getResponse().getCookie(SessionCookies.SESSION)).isNull();
    }

    @Test
    void aTamperedNextCookieCannotRedirectOffSite() throws Exception {
        long steamId = STEAM_IDS.incrementAndGet();
        doReturn(Optional.of(steamId)).when(openId).verify(any(), any());

        mvc.perform(get("/api/auth/steam/callback").param("state", "s3")
                        .cookie(new Cookie(SessionCookies.LOGIN_STATE, "s3"), new Cookie(SessionCookies.LOGIN_NEXT, "https://evil.test")))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, "/"));
    }

    // ------------------------------------------------------- CSRF and bearer clients

    @Test
    void cookieAuthenticatedWritesNeedTheCsrfHeaderButBearerClientsDoNot() throws Exception {
        UserSummary cookieUser = newUser();
        Cookie session = new Cookie(SessionCookies.SESSION, tokens.issue(cookieUser.id()));

        // a forged cross-site request rides the cookie but cannot produce the CSRF token
        mvc.perform(delete("/api/me").cookie(session)).andExpect(status().isForbidden());
        assertThat(users.findById(cookieUser.id())).isPresent();

        // the app itself sends the token it read from the XSRF-TOKEN cookie
        mvc.perform(delete("/api/me").cookie(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(users.findById(cookieUser.id())).isEmpty();

        // a script holding the token in a header isn't exposed to CSRF, so it doesn't need one
        UserSummary bearerUser = newUser();
        mvc.perform(delete("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.issue(bearerUser.id())))
                .andExpect(status().isNoContent());
        assertThat(users.findById(bearerUser.id())).isEmpty();
    }

    // -------------------------------------------------------------- sign out / delete

    @Test
    void logoutClearsTheSessionCookie() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden()); // CSRF applies to sign-out too

        MvcResult result = mvc.perform(post("/api/auth/logout").with(csrf())).andExpect(status().isNoContent()).andReturn();
        Cookie cleared = result.getResponse().getCookie(SessionCookies.SESSION);
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    @Test
    void aTokenForADeletedAccountIsNotASignedInUser() throws Exception {
        UserSummary user = newUser();
        String token = tokens.issue(user.id());
        users.delete(user.id());

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- sliding sessions

    @Test
    void anOldSessionIsExtendedButAFreshOneIsLeftAlone() throws Exception {
        UserSummary user = newUser();
        String fresh = tokens.issue(user.id());
        String aging = tokenIssuedAt(user.id(), Instant.now().minus(Duration.ofDays(5))); // ttl is 7d, halfway is 3.5d

        MvcResult freshResult = mvc.perform(get("/api/me").cookie(new Cookie(SessionCookies.SESSION, fresh)))
                .andExpect(status().isOk()).andReturn();
        MvcResult agingResult = mvc.perform(get("/api/me").cookie(new Cookie(SessionCookies.SESSION, aging)))
                .andExpect(status().isOk()).andReturn();

        assertThat(freshResult.getResponse().getCookie(SessionCookies.SESSION)).isNull();
        Cookie renewed = agingResult.getResponse().getCookie(SessionCookies.SESSION);
        assertThat(renewed).isNotNull();
        assertThat(renewed.getValue()).isNotEqualTo(aging);
    }

    @Test
    void anExpiredSessionIsRejected() throws Exception {
        UserSummary user = newUser();
        String expired = tokenIssuedAt(user.id(), Instant.now().minus(Duration.ofDays(8)));

        mvc.perform(get("/api/me").cookie(new Cookie(SessionCookies.SESSION, expired))).andExpect(status().isUnauthorized());
    }

    // ----------------------------------------------------------------------- helpers

    private void signIn(String state) throws Exception {
        mvc.perform(get("/api/auth/steam/callback").param("state", state).cookie(new Cookie(SessionCookies.LOGIN_STATE, state)))
                .andExpect(status().isFound());
    }

    private UserSummary newUser() {
        return users.findOrCreateBySteamId(STEAM_IDS.incrementAndGet(), "Test user", null);
    }

    private String tokenIssuedAt(long userId, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("patch-notes-aggregator")
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofDays(7)))
                .subject(Long.toString(userId))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
