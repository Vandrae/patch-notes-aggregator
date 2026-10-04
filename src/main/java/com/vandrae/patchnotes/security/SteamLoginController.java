package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamPlayer;
import com.vandrae.patchnotes.externalapi.SteamProfileClient;
import com.vandrae.patchnotes.users.UserAccounts;
import com.vandrae.patchnotes.users.UserSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The two browser-facing legs of "Sign in through Steam". Both are plain redirects, so the single-page app
 * starts a login by simply navigating to {@code /api/auth/steam/login}.
 */
@RestController
@RequestMapping("/api/auth/steam")
class SteamLoginController {

    private static final Logger log = LoggerFactory.getLogger(SteamLoginController.class);
    /** Where the app sends people when a login did not complete; its login screen shows the message. */
    static final String FAILURE_REDIRECT = "/login?error=steam";
    /** Where people are sent when a sign-in limit has been reached (see {@link RateLimitFilter}). */
    static final String RATE_LIMITED_REDIRECT = "/login?error=rate-limited";
    static final String LOGIN_PATH = "/api/auth/steam/login";

    private final SecureRandom random = new SecureRandom();
    private final SteamOpenIdService openId;
    private final SteamProfileClient profiles;
    private final UserAccounts users;
    private final TokenService tokens;
    private final SessionCookies cookies;

    SteamLoginController(SteamOpenIdService openId, SteamProfileClient profiles, UserAccounts users,
                         TokenService tokens, SessionCookies cookies) {
        this.openId = openId;
        this.profiles = profiles;
        this.users = users;
        this.tokens = tokens;
        this.cookies = cookies;
    }

    /**
     * Starts a login. A random {@code state} goes both into the return address Steam will call back and into a
     * cookie; the callback only proceeds if they match, so an attacker can't make a victim's browser complete a
     * login the attacker started ("login CSRF").
     */
    @GetMapping("/login")
    ResponseEntity<Void> login(@RequestParam(required = false) String next) {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String state = HexFormat.of().formatHex(bytes);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(openId.redirectUrl(state))
                .header(HttpHeaders.SET_COOKIE, cookies.loginTemp(SessionCookies.LOGIN_STATE, state).toString())
                .header(HttpHeaders.SET_COOKIE,
                        cookies.loginTemp(SessionCookies.LOGIN_NEXT, RedirectTargets.sanitize(next)).toString())
                .build();
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(HttpServletRequest request,
                                  @RequestParam(name = "state", required = false) String state,
                                  @CookieValue(name = SessionCookies.LOGIN_STATE, required = false) String stateCookie,
                                  @CookieValue(name = SessionCookies.LOGIN_NEXT, required = false) String nextCookie) {
        boolean sameBrowser = state != null && stateCookie != null
                && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), stateCookie.getBytes(StandardCharsets.UTF_8));
        Optional<Long> steamId;
        try {
            steamId = sameBrowser ? openId.verify(firstValues(request), state) : Optional.empty();
        } catch (SteamChecksBusyException e) {
            return redirect(RATE_LIMITED_REDIRECT, null); // too many checks are going to Steam right now; try again shortly
        }
        if (steamId.isEmpty()) {
            return redirect(FAILURE_REDIRECT, null);
        }

        // best effort: login still succeeds, just with a generic name, if Steam's profile API is unavailable
        SteamPlayer player = profiles.getPlayer(steamId.get()).orElse(null);
        UserSummary user = users.findOrCreateBySteamId(steamId.get(),
                player == null ? null : player.personaName(), player == null ? null : player.avatarUrl());
        log.info("User {} signed in with Steam", user.id());
        return redirect(RedirectTargets.sanitize(nextCookie), cookies.session(tokens.issue(user.id())).toString());
    }

    private ResponseEntity<Void> redirect(String location, String sessionCookie) {
        var response = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location))
                .header(HttpHeaders.SET_COOKIE, cookies.clearLoginTemp(SessionCookies.LOGIN_STATE).toString())
                .header(HttpHeaders.SET_COOKIE, cookies.clearLoginTemp(SessionCookies.LOGIN_NEXT).toString());
        if (sessionCookie != null) {
            response.header(HttpHeaders.SET_COOKIE, sessionCookie);
            response.header(HttpHeaders.SET_COOKIE, cookies.clearCsrf().toString()); // start the new session on a fresh CSRF token
        }
        return response.build();
    }

    private static Map<String, String> firstValues(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values.length > 0) {
                params.put(key, values[0]);
            }
        });
        return params;
    }
}
