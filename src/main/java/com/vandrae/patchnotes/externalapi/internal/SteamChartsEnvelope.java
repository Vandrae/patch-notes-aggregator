package com.vandrae.patchnotes.externalapi.internal;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Wire format of ISteamChartsService/GetMostPlayedGames/v1. */
public record SteamChartsEnvelope(Response response) {

    public record Response(List<Rank> ranks) {
    }

    public record Rank(Long appid, @JsonProperty("peak_in_game") Integer peakInGame) {
    }
}
