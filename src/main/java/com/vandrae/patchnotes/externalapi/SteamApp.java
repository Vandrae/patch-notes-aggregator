package com.vandrae.patchnotes.externalapi;

/** One entry of Steam's app list. {@code lastModified} is Unix epoch seconds. */
public record SteamApp(long appId, String name, long lastModified) {
}
