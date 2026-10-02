package com.vandrae.patchnotes.events;

/**
 * New or changed articles were stored for a game. Nothing consumes this yet; it is the seam where
 * notifications (email, webhook, push) would plug in without touching the fetch pipeline.
 */
public record ArticlesIngested(long gameId, int created, int updated) {
}
