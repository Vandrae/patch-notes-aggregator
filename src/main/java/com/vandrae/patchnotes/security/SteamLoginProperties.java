package com.vandrae.patchnotes.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param openidEndpoint  Steam's OpenID 2.0 endpoint; overridable so tests and e2e runs can use a fake provider
 * @param publicBaseUrl   where the browser reaches this app (scheme + host [+ port], no trailing slash). It is the
 *                        OpenID realm and return address, and an https value turns on the Secure cookie flag.
 */
@ConfigurationProperties("app.security.steam")
public record SteamLoginProperties(
        @DefaultValue("https://steamcommunity.com/openid/login") String openidEndpoint,
        @DefaultValue("http://localhost:8080") String publicBaseUrl) {

    public SteamLoginProperties {
        publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    boolean secureCookies() {
        return publicBaseUrl.startsWith("https://");
    }
}
