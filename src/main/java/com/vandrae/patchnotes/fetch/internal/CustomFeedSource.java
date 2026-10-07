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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Patch notes of a game that is not on Steam, read from the feeds configured for it (see {@link CustomGamesProperties}).
 * A game may have several sources (Minecraft's Java and Bedrock changelogs are one list); if one is down the others still
 * count, and only when all of them fail is the fetch a failure, which backs the game off like any other.
 *
 * <p>A source may carry a minimum interval. Inside it the last answer is reused and no request is sent, so a page read
 * without its publisher having offered a feed is asked for at most that often, however the poller is scheduled and however
 * many people follow the game. A failure is remembered too (for at most {@link #FAILURE_COOLDOWN}), so a page that has
 * changed shape is not asked for again and again while it stays unreadable.
 */
@Component
class CustomFeedSource implements ArticleSource {

    private static final Logger log = LoggerFactory.getLogger(CustomFeedSource.class);

    /** As many recent posts as the Steam source keeps (app.steam.news-count): the schedule only needs what is new. */
    static final int MAX_POSTS = 20;

    /** How long a failed read is remembered, at most: long enough not to hammer, short enough that a blip heals soon. */
    static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(30);

    /** What the last read of an address produced: posts, or the failure; and when. */
    private record Answer(Instant at, List<PublisherPost> posts, PublisherFeedException failure) {
    }

    private final CustomGameRegistry registry;
    private final PublisherFeedClient feeds;
    private final PublisherPostNormalizer normalizer;
    private final Clock clock;
    private final ConcurrentMap<String, Answer> lastAnswers = new ConcurrentHashMap<>();

    CustomFeedSource(CustomGameRegistry registry, PublisherFeedClient feeds, PublisherPostNormalizer normalizer, Clock clock) {
        this.registry = registry;
        this.feeds = feeds;
        this.normalizer = normalizer;
        this.clock = clock;
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
                posts.addAll(read(source));
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

    /** The source's posts: from the network, or from the last answer when the source's minimum interval has not passed. */
    private List<PublisherPost> read(Source source) {
        Duration gap = source.minInterval();
        Instant now = clock.instant();
        if (!gap.isZero()) {
            Answer last = lastAnswers.get(source.url());
            if (last != null) {
                Duration remember = last.failure() == null ? gap : gap.compareTo(FAILURE_COOLDOWN) < 0 ? gap : FAILURE_COOLDOWN;
                if (now.isBefore(last.at().plus(remember))) {
                    if (last.failure() != null) {
                        throw last.failure();
                    }
                    return last.posts();
                }
            }
        }
        try {
            List<PublisherPost> posts = switch (source.kind()) {
                case RSS -> feeds.readRss(source.url());
                case HELP_CENTER -> feeds.readHelpCenter(source.url());
                case RIOT_NEWS -> feeds.readRiotNews(source.url());
            };
            if (!gap.isZero()) {
                lastAnswers.put(source.url(), new Answer(now, posts, null));
            }
            return posts;
        } catch (PublisherFeedException e) {
            if (!gap.isZero()) {
                lastAnswers.put(source.url(), new Answer(now, List.of(), e));
            }
            throw e;
        }
    }
}
