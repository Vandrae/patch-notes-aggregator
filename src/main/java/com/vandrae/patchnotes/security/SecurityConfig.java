package com.vandrae.patchnotes.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * What the browser may load. The app is a bundled single-page app, so everything comes from its own origin, with two
     * exceptions: images may also come from Steam's CDN (game covers and icons, profile avatars) or be inlined as data:
     * URIs. There is no inline script and no eval, so an injected script tag would not run even if markup got through.
     * Nothing may frame the app, load plugins, or change the base URL, and forms may only post back to this origin.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data: https://*.steamstatic.com",
            "connect-src 'self'",
            "font-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /** Browser features the app never uses, switched off for it and anything embedded in it. */
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, TokenService tokens, SessionCookies cookies, JwtProperties jwt,
                                    JwtDecoder jwtDecoder, SteamLoginProperties login) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(login.secureCookies()));
        http
                // CSRF is on. The resource server below automatically exempts requests that carry an
                // Authorization: Bearer header (a cross-site request can't forge one); cookie-authenticated
                // requests, handled by CookieSessionFilter, are not exempt and must send the CSRF token.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // By default Spring rotates the CSRF token (clears the cookie, issues another) whenever it thinks a
                        // user has just authenticated. With stateless sessions it thinks that on EVERY request, so a page
                        // that polls keeps invalidating the token another request just read, and writes fail with 403.
                        // Rotation on login is done explicitly instead (see SteamLoginController).
                        .sessionAuthenticationStrategy((authentication, request, response) -> { }))
                // Spring Security already sends X-Content-Type-Options, X-Frame-Options, Cache-Control for API responses,
                // and Strict-Transport-Security (on https requests only). These add the rest.
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                        .crossOriginOpenerPolicy(opener -> opener.policy(CrossOriginOpenerPolicy.SAME_ORIGIN)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/steam/**", "/api/auth/logout", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/actuator/**").denyAll()
                        // everything else is the single-page app's static files and client-side routes
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .addFilterAfter(new CookieSessionFilter(jwtDecoder), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new SessionRenewalFilter(tokens, cookies, jwt), CookieSessionFilter.class);
        return http.build();
    }

    @Bean
    SecretKey jwtSigningKey(JwtProperties props, Environment environment) {
        byte[] bytes;
        if (props.secret() == null || props.secret().isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException(
                        "app.security.jwt.secret (JWT_SECRET) must be set in production: a random key would sign "
                                + "everyone out on every restart and can't be shared between instances");
            }
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            log.warn("app.security.jwt.secret is not set: generated a random signing key. "
                    + "Sessions will end on restart. Set JWT_SECRET for a stable key.");
        } else {
            bytes = props.secret().getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalStateException("app.security.jwt.secret must be at least 32 bytes for HS256");
            }
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // default validators (expiry / not-before) plus: the token must have been issued by us
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }
}
