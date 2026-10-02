package com.vandrae.patchnotes.events;

/** A user added a game to their watchlist. */
public record GameWatched(long userId, long gameId) {
}
