package com.vandrae.patchnotes.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Sign in through Steam" is OpenID 2.0, which Spring Security does not support and which has no maintained
 * Java library, so the (small) protocol is implemented here: send the browser to Steam, then, when it comes
 * back, prove the response is genuine by asking Steam itself ({@code check_authentication}).
 *
 * <p>Steam only ever tells us a SteamID. Verification does not stop at "Steam said valid": it also pins the
 * endpoint, the return address (which carries our anti-forgery {@code state}), the shape of the identity, that
 * those fields were covered by Steam's signature, and the freshness and uniqueness of the response nonce.
 */
@Service
class SteamOpenIdService {

    private static final Logger log = LoggerFactory.getLogger(SteamOpenIdService.class);

    static final String CALLBACK_PATH = "/api/auth/steam/callback";
    static final String OPENID_NS = "http://specs.openid.net/auth/2.0";
    private static final String IDENTIFIER_SELECT = "http://specs.openid.net/auth/2.0/identifier_select";
    private static final Pattern CLAIMED_ID = Pattern.compile("^https://steamcommunity\\.com/openid/id/(\\d{17})$");
    /** Fields that must be inside Steam's signature, otherwise an attacker could swap them without invalidating it. */
    private static final Set<String> MUST_BE_SIGNED =
            Set.of("op_endpoint", "claimed_id", "identity", "return_to", "response_nonce", "assoc_handle");
    private static final int NONCE_TIMESTAMP_LENGTH = "2026-10-01T15:12:57Z".length();
    private static final Duration MAX_NONCE_AGE = Duration.ofMinutes(5);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(1);

    private final SteamLoginProperties props;
    private final RestClient rest;
    /** Nonces already accepted. Steam also rejects replays; this is a second line of defence that costs nothing. */
    private final Map<String, Instant> seenNonces = new ConcurrentHashMap<>();

    @Autowired
    SteamOpenIdService(SteamLoginProperties props) {
        this(props, defaultRestClient());
    }

    SteamOpenIdService(SteamLoginProperties props, RestClient rest) {
        this.props = props;
        this.rest = rest;
    }

    private static RestClient defaultRestClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }

    /** The address Steam sends the browser back to; embeds the per-login {@code state}. */
    String returnTo(String state) {
        return props.publicBaseUrl() + CALLBACK_PATH + "?state=" + state;
    }

    URI redirectUrl(String state) {
        return UriComponentsBuilder.fromUriString(props.openidEndpoint())
                .queryParam("openid.ns", OPENID_NS)
                .queryParam("openid.mode", "checkid_setup")
                .queryParam("openid.return_to", "{returnTo}")
                .queryParam("openid.realm", "{realm}")
                .queryParam("openid.identity", IDENTIFIER_SELECT)
                .queryParam("openid.claimed_id", IDENTIFIER_SELECT)
                .encode()
                .build(returnTo(state), props.publicBaseUrl());
    }

    /**
     * @param params the callback's query parameters (first value of each)
     * @param state  the value we stored in the browser's cookie when the login started
     * @return the verified SteamID64, or empty if anything about the response is not trustworthy
     */
    Optional<Long> verify(Map<String, String> params, String state) {
        Optional<Long> steamId = checkLocally(params, state);
        if (steamId.isEmpty() || !steamConfirms(params)) {
            return Optional.empty();
        }
        // only claim the nonce once Steam has vouched for the response
        if (seenNonces.putIfAbsent(params.get("openid.response_nonce"), Instant.now()) != null) {
            log.warn("Rejected a replayed Steam login response");
            return Optional.empty();
        }
        purgeOldNonces();
        return steamId;
    }

    private Optional<Long> checkLocally(Map<String, String> params, String state) {
        if (!OPENID_NS.equals(params.get("openid.ns")) || !"id_res".equals(params.get("openid.mode"))) {
            return reject("not an OpenID 2.0 id_res response");
        }
        if (!props.openidEndpoint().equals(params.get("openid.op_endpoint"))) {
            return reject("response is not from the expected OpenID endpoint");
        }
        if (state == null || state.isBlank() || !returnTo(state).equals(params.get("openid.return_to"))) {
            return reject("return_to does not match this login attempt");
        }
        Set<String> signed = Set.copyOf(Arrays.asList(params.getOrDefault("openid.signed", "").split(",")));
        if (!signed.containsAll(MUST_BE_SIGNED)) {
            return reject("required fields are not covered by the signature");
        }
        String claimedId = params.get("openid.claimed_id");
        Matcher matcher = claimedId == null ? null : CLAIMED_ID.matcher(claimedId);
        if (matcher == null || !matcher.matches() || !claimedId.equals(params.get("openid.identity"))) {
            return reject("claimed_id is not a Steam identity");
        }
        if (!nonceIsFresh(params.get("openid.response_nonce"))) {
            return reject("response nonce is missing, malformed or stale");
        }
        return Optional.of(Long.parseLong(matcher.group(1)));
    }

    private boolean nonceIsFresh(String nonce) {
        if (nonce == null || nonce.length() <= NONCE_TIMESTAMP_LENGTH) {
            return false;
        }
        try {
            Instant issued = Instant.parse(nonce.substring(0, NONCE_TIMESTAMP_LENGTH));
            Instant now = Instant.now();
            return !issued.isBefore(now.minus(MAX_NONCE_AGE)) && !issued.isAfter(now.plus(MAX_CLOCK_SKEW));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Sends Steam its own response back; only Steam can say whether its signature is genuine. */
    private boolean steamConfirms(Map<String, String> params) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("openid.")) {
                form.add(key, value);
            }
        });
        form.set("openid.mode", "check_authentication");
        try {
            String body = rest.post()
                    .uri(props.openidEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            boolean valid = body != null && body.lines().anyMatch(line -> line.strip().equals("is_valid:true"));
            if (!valid) {
                log.warn("Steam rejected a login response as invalid");
            }
            return valid;
        } catch (RuntimeException e) {
            log.warn("Could not reach Steam to verify a login: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private void purgeOldNonces() {
        Instant cutoff = Instant.now().minus(MAX_NONCE_AGE.multipliedBy(2));
        seenNonces.values().removeIf(seenAt -> seenAt.isBefore(cutoff));
    }

    private static Optional<Long> reject(String reason) {
        log.warn("Rejected Steam login response: {}", reason);
        return Optional.empty();
    }
}
