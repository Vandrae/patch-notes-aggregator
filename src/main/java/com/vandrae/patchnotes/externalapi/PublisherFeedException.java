package com.vandrae.patchnotes.externalapi;

/** A publisher's feed could not be read. The message names the feed (host and path) and the kind of failure, nothing else. */
public class PublisherFeedException extends RuntimeException {

    public PublisherFeedException(String message) {
        super(message);
    }

    public PublisherFeedException(String message, Throwable cause) {
        super(message, cause);
    }
}
