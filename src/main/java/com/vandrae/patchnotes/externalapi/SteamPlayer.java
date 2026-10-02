package com.vandrae.patchnotes.externalapi;

/** The public bits of a Steam profile that we show in the UI. */
public record SteamPlayer(String personaName, String avatarUrl) {
}
