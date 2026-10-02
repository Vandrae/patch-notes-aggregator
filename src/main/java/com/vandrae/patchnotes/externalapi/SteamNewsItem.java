package com.vandrae.patchnotes.externalapi;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One entry of ISteamNews/GetNewsForApp, exactly as Steam returns it.
 *
 * @param date     Unix epoch seconds
 * @param feedType 1 = posted by the developer on Steam, 0 = third-party feed (press, SteamDB, ...)
 * @param tags     absent on most items; may hold moderation noise as well as real tags like "patchnotes"
 */
public record SteamNewsItem(
        String gid,
        String title,
        String url,
        @JsonProperty("is_external_url") boolean externalUrl,
        String author,
        String contents,
        String feedlabel,
        long date,
        String feedname,
        @JsonProperty("feed_type") int feedType,
        long appid,
        List<String> tags) {
}
