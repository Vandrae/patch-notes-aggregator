package com.vandrae.patchnotes.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.time.Instant;

/**
 * Sliding session: once a cookie-authenticated request arrives past the halfway point of the token's life, hand
 * back a fresh cookie. Someone who keeps using the app stays signed in; someone who stops is signed out when the
 * token expires. Not a Spring bean on purpose, so Boot doesn't also register it as a plain servlet filter.
 */
class SessionRenewalFilter extends OncePerRequestFilter {

    private final TokenService tokens;
    private final SessionCookies cookies;
    private final JwtProperties props;

    SessionRenewalFilter(TokenService tokens, SessionCookies cookies, JwtProperties props) {
        this.tokens = tokens;
        this.cookies = cookies;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean viaCookie = WebUtils.getCookie(request, SessionCookies.SESSION) != null
                && request.getHeader(HttpHeaders.AUTHORIZATION) == null;
        if (viaCookie && authentication instanceof JwtAuthenticationToken jwtAuth && dueForRenewal(jwtAuth.getToken())) {
            long userId = Long.parseLong(jwtAuth.getToken().getSubject());
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.session(tokens.issue(userId)).toString());
        }
        chain.doFilter(request, response);
    }

    private boolean dueForRenewal(Jwt jwt) {
        Instant issuedAt = jwt.getIssuedAt();
        return issuedAt != null && Instant.now().isAfter(issuedAt.plus(props.ttl().dividedBy(2)));
    }
}
