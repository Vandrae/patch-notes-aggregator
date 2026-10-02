package com.vandrae.patchnotes.externalapi.internal;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Wire format of ISteamUser/GetPlayerSummaries/v2: {"response": {"players": [...]}} */
public record SteamPlayerSummaries(Response response) {

    public record Response(List<Player> players) {
    }

    public record Player(String steamid, String personaname, @JsonProperty("avatarfull") String avatarFull) {
    }
}
