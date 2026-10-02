package com.vandrae.patchnotes.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import java.io.IOException;

/**
 * Authenticates the browser app from its session cookie.
 *
 * <p>This is deliberately NOT done through the OAuth2 resource server's token resolver. That configurer
 * automatically switches CSRF protection off for every request it finds a token on, which, for a cookie, is
 * exactly the situation CSRF protection exists for. Keeping cookie auth in its own filter means the resource
 * server only ever sees {@code Authorization: Bearer} headers (which a cross-site request cannot forge), and
 * cookie-authenticated writes still have to present the CSRF token.
 *
 * <p>An expired or garbage cookie just leaves the request anonymous, so protected routes answer 401 and the
 * login routes keep working. The cookie is only consulted for data routes under {@code /api/} (not
 * {@code /api/auth/}).
 */
class CookieSessionFilter extends OncePerRequestFilter {

    private final AuthenticationProvider provider;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    CookieSessionFilter(JwtDecoder decoder) {
        this.provider = new JwtAuthenticationProvider(decoder);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Cookie cookie = WebUtils.getCookie(request, SessionCookies.SESSION);
        boolean alreadyAuthenticated = contextHolder.getContext().getAuthentication() != null
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null;
        if (cookie != null && !cookie.getValue().isBlank() && !alreadyAuthenticated) {
            try {
                Authentication authentication = provider.authenticate(new BearerTokenAuthenticationToken(cookie.getValue()));
                SecurityContext context = contextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                contextHolder.setContext(context);
            } catch (AuthenticationException e) {
                // expired / tampered cookie: stay anonymous
            }
        }
        chain.doFilter(request, response);
    }
}
