package com.vandrae.patchnotes.externalapi;

public class SteamApiException extends RuntimeException {

    public SteamApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
