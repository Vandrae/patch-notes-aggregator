package com.vandrae.patchnotes.feed.internal;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * What is going on around one game, for its page: how many people follow it (a count, never who), when its newest patch
 * note came out (absent when none has been stored) and how many patch notes there are.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameActivity(long watcherCount, Instant latestPatchAt, long patchNoteCount) {
}
