package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Refuses to start the {@code prod} profile with a configuration that would be unsafe or silently broken. A deployment that
 * fails at startup with a clear message is far better than one that comes up and quietly signs people in over plain http.
 *
 * <p>The signing key is checked separately, where it is built ({@link SecurityConfig#jwtSigningKey}).
 */
@Component
@Profile("prod")
class ProductionSettingsCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSettingsCheck.class);
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    ProductionSettingsCheck(SteamLoginProperties login, SteamProperties steam) {
        requireSecureBaseUrl(login.publicBaseUrl());
        if (!steam.hasApiKey()) {
            log.warn("STEAM_API_KEY is not set: the full game catalog cannot be imported and profile names and avatars "
                    + "are not looked up. Only the starter games will be searchable.");
        }
    }

    /**
     * Sign-in cookies and the Steam redirect must travel over https. The one exception is a loopback address, so the
     * production configuration can be tried on your own machine (for example with docker compose) without a certificate.
     */
    static void requireSecureBaseUrl(String publicBaseUrl) {
        URI uri;
        try {
            uri = new URI(publicBaseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("PUBLIC_BASE_URL is not a valid URL: " + publicBaseUrl);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if ("https".equals(scheme) && !host.isEmpty()) {
            return;
        }
        if ("http".equals(scheme) && LOOPBACK_HOSTS.contains(host)) {
            log.warn("PUBLIC_BASE_URL is plain http on {}: fine for trying the production setup on this machine, but "
                    + "session cookies will not be marked Secure. Use your https address when you deploy.", host);
            return;
        }
        throw new IllegalStateException("PUBLIC_BASE_URL must be an https:// address in production (got '" + publicBaseUrl
                + "'): the session cookie and the Steam sign-in redirect must not travel over plain http. "
                + "Only http://localhost is accepted, for trying the production setup locally.");
    }
}
