package com.vandrae.patchnotes.feed;

public record IngestResult(int created, int updated, int unchanged) {

    public static final IngestResult NOTHING = new IngestResult(0, 0, 0);
}
