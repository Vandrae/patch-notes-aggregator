package com.vandrae.patchnotes.externalapi;

import java.util.List;

/** One page of the app list; pass {@code lastAppId} back to fetch the next page while {@code hasMore} is true. */
public record SteamAppPage(List<SteamApp> apps, boolean hasMore, long lastAppId) {
}
