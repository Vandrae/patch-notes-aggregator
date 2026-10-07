package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.catalog.SourceType;
import com.vandrae.patchnotes.externalapi.PublisherFeedClient;
import com.vandrae.patchnotes.externalapi.PublisherFeedException;
import com.vandrae.patchnotes.externalapi.PublisherPost;
import com.vandrae.patchnotes.feed.IncomingArticle;
import com.vandrae.patchnotes.fetch.internal.CustomGamesProperties.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Patch notes of a game that is not on Steam, read from the feeds configured for it (see {@link CustomGamesProperties}).
 * A game may have several sources (Minecraft's Java and Bedrock changelogs are one list); if one is down the others still
 * count, and only when all of them fail is the fetch a failure, which backs the game off like any other.
 */
@Component
class CustomFeedSource implements ArticleSource {

    private static final Logger log = LoggerFactory.getLogger(CustomFeedSource.class);

    /** As many recent posts as the Steam source keeps (app.steam.news-count): the schedule only needs what is new. */
    static final int MAX_POSTS = 20;

    private final CustomGameRegistry registry;
    private final PublisherFeedClient feeds;
    private final PublisherPostNormalizer normalizer;

    CustomFeedSource(CustomGameRegistry registry, PublisherFeedClient feeds, PublisherPostNormalizer normalizer) {
        this.registry = registry;
        this.feeds = feeds;
        this.normalizer = normalizer;
    }

    @Override
    public boolean supports(GameSummary game) {
        return game.sourceType() == SourceType.CUSTOM && !registry.sourcesFor(game.id()).isEmpty();
    }

    @Override
    public SourceBatch fetchLatest(GameSummary game) {
        List<Source> sources = registry.sourcesFor(game.id());
        List<PublisherPost> posts = new ArrayList<>();
        PublisherFeedException lastFailure = null;
        int failures = 0;
        for (Source source : sources) {
            try {
                posts.addAll(switch (source.kind()) {
                    case RSS -> feeds.readRss(source.url());
                    case HELP_CENTER -> feeds.readHelpCenter(source.url());
                });
            } catch (PublisherFeedException e) {
                failures++;
                lastFailure = e;
                log.warn("A source of '{}' failed: {}", game.name(), e.getMessage()); // the message names the feed, nothing else
            }
        }
        if (failures == sources.size()) {
            throw lastFailure; // nothing to go on at all: a failure, so the game backs off rather than looking quiet
        }
        List<PublisherPost> newest = posts.stream()
                .sorted(Comparator.comparing(PublisherPost::publishedAt).reversed())
                .limit(MAX_POSTS)
                .toList();
        List<IncomingArticle> articles = newest.stream()
                .map(normalizer::normalize)
                .flatMap(java.util.Optional::stream)
                .toList();
        return new SourceBatch(posts.size(), articles);
    }
}
