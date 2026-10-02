package com.vandrae.patchnotes.externalapi.internal;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire format of IStoreService/GetAppList/v1. When nothing matches (e.g. an incremental sync with no changes) Steam
 * answers with an empty {@code {"response":{}}}, so every field here is allowed to be absent.
 */
public record SteamAppListEnvelope(Response response) {

    public record Response(
            List<App> apps,
            @JsonProperty("have_more_results") Boolean haveMoreResults,
            @JsonProperty("last_appid") Long lastAppId) {
    }

    public record App(long appid, String name, @JsonProperty("last_modified") long lastModified) {
    }
}
