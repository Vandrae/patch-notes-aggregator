package com.vandrae.patchnotes.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param secret HS256 key material, at least 32 bytes. Blank = generate a random one per start (refused in prod).
 * @param ttl    session lifetime; the session is silently extended while the user stays active
 */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("patch-notes-aggregator") String issuer,
        @DefaultValue("7d") Duration ttl) {

    /** A record prints every field; this one must never print the signing secret, however carelessly it gets logged. */
    @Override
    public String toString() {
        return "JwtProperties[secret=" + (secret == null || secret.isBlank() ? "<not set>" : "<set>")
                + ", issuer=" + issuer + ", ttl=" + ttl + "]";
    }
}
