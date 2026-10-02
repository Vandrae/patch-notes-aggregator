package com.vandrae.patchnotes.security;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds the cookies this module sets. All are HttpOnly + SameSite=Lax, and Secure whenever the site is https. */
@Component
class SessionCookies {

    static final String SESSION = "PN_SESSION";
    static final String LOGIN_STATE = "pn_login_state";
    static final String LOGIN_NEXT = "pn_login_next";

    private final SteamLoginProperties props;
    private final JwtProperties jwt;

    SessionCookies(SteamLoginProperties props, JwtProperties jwt) {
        this.props = props;
        this.jwt = jwt;
    }

    ResponseCookie session(String token) {
        return build(SESSION, token, jwt.ttl());
    }

    ResponseCookie clearSession() {
        return build(SESSION, "", Duration.ZERO);
    }

    /**
     * Expires the CSRF cookie so the next request gets a brand-new token. Done at sign-in: a token that existed before the
     * session (for instance one planted by another site on the same domain) must not carry over into it. Unlike the
     * others this cookie is readable by the app's JavaScript, which has to copy it into a header.
     */
    ResponseCookie clearCsrf() {
        return ResponseCookie.from("XSRF-TOKEN", "")
                .httpOnly(false)
                .secure(props.secureCookies())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    /** Short-lived: only needs to survive the round trip to Steam and back. */
    ResponseCookie loginTemp(String name, String value) {
        return build(name, value, Duration.ofMinutes(10));
    }

    ResponseCookie clearLoginTemp(String name) {
        return build(name, "", Duration.ZERO);
    }

    private ResponseCookie build(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(props.secureCookies())
                .sameSite("Lax") // Lax (not Strict): the cookie must come back on the redirect from steamcommunity.com
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
