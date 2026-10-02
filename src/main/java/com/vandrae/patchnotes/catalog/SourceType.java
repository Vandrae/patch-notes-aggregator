package com.vandrae.patchnotes.catalog;

/** Where a game's patch notes come from. */
public enum SourceType {
    /** Covered by Steam's news API. */
    STEAM_NEWS,
    /** Needs a hand-written adapter (RSS / HTML). */
    CUSTOM
}
