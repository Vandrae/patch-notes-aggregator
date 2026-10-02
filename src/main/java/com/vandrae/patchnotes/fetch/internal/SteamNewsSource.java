package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.catalog.SourceType;
import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.externalapi.SteamNewsItem;
import com.vandrae.patchnotes.feed.IncomingArticle;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class SteamNewsSource implements ArticleSource {

    private final SteamNewsClient steam;
    private final SteamNewsNormalizer normalizer;

    SteamNewsSource(SteamNewsClient steam, SteamNewsNormalizer normalizer) {
        this.steam = steam;
        this.normalizer = normalizer;
    }

    @Override
    public boolean supports(GameSummary game) {
        return game.sourceType() == SourceType.STEAM_NEWS && game.steamAppId() != null;
    }

    @Override
    public SourceBatch fetchLatest(GameSummary game) {
        List<SteamNewsItem> items = steam.getNewsForApp(game.steamAppId());
        List<IncomingArticle> articles = items.stream()
                .map(normalizer::normalize)
                .flatMap(java.util.Optional::stream)
                .toList();
        return new SourceBatch(items.size(), articles);
    }
}
