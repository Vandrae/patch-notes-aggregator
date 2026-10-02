package com.vandrae.patchnotes.users;

import com.vandrae.patchnotes.catalog.GameSummary;

import java.time.Instant;

public record WatchlistItem(GameSummary game, Instant addedAt) {
}
