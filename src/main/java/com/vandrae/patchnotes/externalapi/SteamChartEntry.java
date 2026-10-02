package com.vandrae.patchnotes.externalapi;

/** One row of Steam's "most played" chart: a game and its highest concurrent player count in the last day. */
public record SteamChartEntry(long appId, int peakPlayers) {
}
