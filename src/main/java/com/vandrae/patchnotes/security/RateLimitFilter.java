package com.vandrae.patchnotes.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Applies the rate limits to the requests that need them, before the controller does any work.
 *
 * <ul>
 *   <li>{@code GET /api/auth/steam/login} and {@code GET /api/auth/steam/callback}: per client address. The browser is
 *       sent back to the sign-in page with a message, because both are redirects in the middle of a sign-in and an error
 *       document would be a dead end.</li>
 *   <li>{@code PUT} and {@code DELETE /api/watchlist/{id}}: per signed-in user (not per address, so a shared network does
 *       not penalise anybody and one user cannot dodge it by switching address). Answered with HTTP 429 as a problem
 *       document the app can show.</li>
 * </ul>
 *
 * Both kinds carry a {@code Retry-After} header. Everything else, including every read, is untouched.
 */
final class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimits limits;

    RateLimitFilter(RateLimits limits) {
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!limits.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        boolean isGet = HttpMethod.GET.matches(request.getMethod());
        KeyedRateLimiter.Decision decision = KeyedRateLimiter.Decision.ALLOWED;
        boolean browserRedirect = false;

        if (isGet && SteamLoginController.LOGIN_PATH.equals(path)) {
            decision = limits.login().tryAcquire(ClientIp.keyOf(request));
            browserRedirect = true;
        } else if (isGet && SteamOpenIdService.CALLBACK_PATH.equals(path)) {
            decision = limits.callback().tryAcquire(ClientIp.keyOf(request));
            browserRedirect = true;
        } else if (isWatchWrite(request, path)) {
            String user = signedInUser();
            if (user != null) { // an anonymous request is turned away by the security rules; counting it would help nobody
                decision = limits.watch().tryAcquire(user);
            }
        }

        if (decision.allowed()) {
            chain.doFilter(request, response);
        } else if (browserRedirect) {
            response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds(decision.retryAfter()));
            response.setStatus(HttpStatus.FOUND.value());
            response.setHeader(HttpHeaders.LOCATION, SteamLoginController.RATE_LIMITED_REDIRECT);
        } else {
            tooManyRequests(response, decision.retryAfter());
        }
    }

    private static boolean isWatchWrite(HttpServletRequest request, String path) {
        String method = request.getMethod();
        return path.startsWith("/api/watchlist/")
                && (HttpMethod.PUT.matches(method) || HttpMethod.DELETE.matches(method));
    }

    private static String signedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication.getName();
    }

    private static void tooManyRequests(HttpServletResponse response, Duration retryAfter) throws IOException {
        String seconds = retryAfterSeconds(retryAfter);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, seconds);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"You're doing that too quickly. Please wait " + seconds + " seconds and try again.\"}");
    }

    /** Whole seconds, rounded up, and at least one: the HTTP header takes an integer. */
    private static String retryAfterSeconds(Duration wait) {
        return Long.toString(Math.max(1, (wait.toMillis() + 999) / 1000));
    }
}
