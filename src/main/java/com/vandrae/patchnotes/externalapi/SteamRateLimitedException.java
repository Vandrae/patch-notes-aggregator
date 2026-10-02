package com.vandrae.patchnotes.externalapi;

/**
 * Steam answered HTTP 429: we are asking too fast. Distinct from other failures because the right response is to
 * slow down and retry the same request, not to skip it. (Retrying immediately only makes the throttling worse.)
 */
public class SteamRateLimitedException extends SteamApiException {

    public SteamRateLimitedException(String message) {
        super(message, null);
    }
}
