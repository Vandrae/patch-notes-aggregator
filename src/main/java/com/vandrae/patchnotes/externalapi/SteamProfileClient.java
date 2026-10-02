package com.vandrae.patchnotes.externalapi;

import com.vandrae.patchnotes.externalapi.internal.SteamHttp;
import com.vandrae.patchnotes.externalapi.internal.SteamPlayerSummaries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Looks up a player's display name and avatar. Steam's OpenID login only proves *who* someone is (a SteamID);
 * the name and picture need this Web API call, which needs the API key.
 *
 * <p>Best effort by design: a failure here must never stop someone signing in, so every problem becomes an
 * empty result. Failures are logged by kind only ({@link SteamHttp#describe}), never by message or URL: the key
 * travels in the query string, and Spring's I/O exception messages contain the full request URL.
 */
@Component
public class SteamProfileClient {

    private static final Logger log = LoggerFactory.getLogger(SteamProfileClient.class);

    private final RestClient rest;
    private final String apiKey;

    @Autowired
    public SteamProfileClient(SteamProperties props) {
        this(props, SteamHttp.restClient(props, props.readTimeout()));
    }

    SteamProfileClient(SteamProperties props, RestClient rest) {
        this.rest = rest;
        this.apiKey = props.apiKey();
    }

    public Optional<SteamPlayer> getPlayer(long steamId) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("No Steam API key configured: skipping profile lookup");
            return Optional.empty();
        }
        try {
            SteamPlayerSummaries summaries = rest.get()
                    .uri(uri -> uri.path("/ISteamUser/GetPlayerSummaries/v2/")
                            .queryParam("key", apiKey)
                            .queryParam("steamids", steamId)
                            .build())
                    .retrieve()
                    .body(SteamPlayerSummaries.class);
            if (summaries == null || summaries.response() == null || summaries.response().players() == null) {
                return Optional.empty();
            }
            return summaries.response().players().stream()
                    .filter(p -> Long.toString(steamId).equals(p.steamid()))
                    .findFirst()
                    .map(p -> new SteamPlayer(p.personaname(), safeHttpsUrl(p.avatarFull())));
        } catch (RuntimeException e) {
            log.warn("Steam profile lookup failed: {}", SteamHttp.describe(e));
            return Optional.empty();
        }
    }

    /** The avatar ends up in an {@code <img src>}: only ever accept plain https URLs. */
    private static String safeHttpsUrl(String url) {
        return url != null && url.startsWith("https://") ? url : null;
    }
}
