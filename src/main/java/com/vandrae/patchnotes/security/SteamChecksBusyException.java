package com.vandrae.patchnotes.security;

/**
 * Too many sign-in checks are already going to Steam, across all clients together. The sign-in is refused for now and the
 * person is asked to try again shortly (see {@link RateLimitProperties#steamChecks()}).
 */
final class SteamChecksBusyException extends RuntimeException {

    SteamChecksBusyException() {
        super("The global limit on sign-in checks sent to Steam is in use", null, false, false); // no stack trace: this is flow control
    }
}
