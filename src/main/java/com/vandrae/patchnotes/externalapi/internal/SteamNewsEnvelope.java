package com.vandrae.patchnotes.externalapi.internal;

import com.vandrae.patchnotes.externalapi.SteamNewsItem;

import java.util.List;

/** Wire format: {"appnews": {"appid": 1, "newsitems": [...]}} */
public record SteamNewsEnvelope(AppNews appnews) {

    public record AppNews(long appid, List<SteamNewsItem> newsitems) {
    }
}
