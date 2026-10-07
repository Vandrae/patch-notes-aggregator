package com.vandrae.patchnotes.fetch.internal;

import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.catalog.SourceType;
import com.vandrae.patchnotes.externalapi.PublisherFeedClient;
import com.vandrae.patchnotes.externalapi.PublisherFeedException;
import com.vandrae.patchnotes.externalapi.PublisherPost;
import com.vandrae.patchnotes.fetch.internal.CustomGamesProperties.Kind;
import com.vandrae.patchnotes.fetch.internal.CustomGamesProperties.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The promise that a page read without its publisher having offered a feed is asked for no more often than configured,
 * whatever the poller does. Plain objects and a clock the test moves: no Spring, no network.
 */
class CustomFeedSourceTest {

    private static final String PAGE = "https://www.leagueoflegends.com/en-us/news/tags/patch-notes/";
    private static final GameSummary GAME = new GameSummary(1L, "League of Legends", SourceType.CUSTOM, null, null, null, null,
            List.of(), null, null);

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final PublisherFeedClient feeds = mock(PublisherFeedClient.class);
    private final CustomGameRegistry registry = mock(CustomGameRegistry.class);
    private final TestClock clock = new TestClock();
    private final CustomFeedSource source = new CustomFeedSource(registry, feeds, new PublisherPostNormalizer(), clock);

    private static final List<PublisherPost> POSTS = List.of(new PublisherPost("p1", "Patch 26.20 Notes",
            "https://www.leagueoflegends.com/en-us/news/game-updates/p", "Teaser", Instant.parse("2026-10-06T18:00:00Z")));

    private void configured(Duration gap) {
        when(registry.sourcesFor(1L)).thenReturn(List.of(new Source(Kind.RIOT_NEWS, PAGE, gap)));
    }

    @BeforeEach
    void pageWorks() {
        when(feeds.readRiotNews(PAGE)).thenReturn(POSTS);
    }

    @Test
    void withNoMinimumEveryFetchAsksThePublisher() {
        configured(Duration.ZERO);

        source.fetchLatest(GAME);
        source.fetchLatest(GAME);

        verify(feeds, times(2)).readRiotNews(PAGE);
    }

    @Test
    void insideTheMinimumTheLastAnswerIsReusedAndNothingIsSent() {
        configured(Duration.ofHours(12));

        var first = source.fetchLatest(GAME);
        clock.advance(Duration.ofMinutes(15)); // the poller's quickest cadence
        var second = source.fetchLatest(GAME);
        clock.advance(Duration.ofHours(11));
        var third = source.fetchLatest(GAME); // 11 h 15 min in: still inside

        verify(feeds, times(1)).readRiotNews(PAGE);
        assertThat(second.articles()).isEqualTo(first.articles());
        assertThat(third.articles()).hasSize(1);
    }

    @Test
    void onceTheMinimumHasPassedItAsksAgainAndThenWaitsAgain() {
        configured(Duration.ofHours(12));

        source.fetchLatest(GAME);
        clock.advance(Duration.ofHours(12));
        source.fetchLatest(GAME);
        clock.advance(Duration.ofHours(1));
        source.fetchLatest(GAME);

        verify(feeds, times(2)).readRiotNews(PAGE);
    }

    @Test
    void aFailedReadIsNotRepeatedStraightAwayButIsRetriedSoonEnoughToHealABlip() {
        configured(Duration.ofHours(12));
        when(feeds.readRiotNews(PAGE)).thenThrow(new PublisherFeedException("Feed www.leagueoflegends.com/x answered HTTP 503"));

        assertThatThrownBy(() -> source.fetchLatest(GAME)).isInstanceOf(PublisherFeedException.class);
        clock.advance(Duration.ofMinutes(5)); // the poller's first back-off
        assertThatThrownBy(() -> source.fetchLatest(GAME)).hasMessageContaining("HTTP 503"); // remembered, nothing sent
        verify(feeds, times(1)).readRiotNews(PAGE);

        clock.advance(Duration.ofMinutes(26)); // past the 30 minute cooldown, far short of the 12 hours
        doReturn(POSTS).when(feeds).readRiotNews(PAGE); // not when(...): that would call the stub that is set to throw

        assertThat(source.fetchLatest(GAME).articles()).hasSize(1);
        verify(feeds, times(2)).readRiotNews(PAGE);
    }

    @Test
    void aShortMinimumAlsoShortensHowLongAFailureIsRemembered() {
        configured(Duration.ofMinutes(10));
        when(feeds.readRiotNews(PAGE)).thenThrow(new PublisherFeedException("down"));

        assertThatThrownBy(() -> source.fetchLatest(GAME)).isInstanceOf(PublisherFeedException.class);
        clock.advance(Duration.ofMinutes(11));
        assertThatThrownBy(() -> source.fetchLatest(GAME)).isInstanceOf(PublisherFeedException.class);

        verify(feeds, times(2)).readRiotNews(PAGE);
    }

    @Test
    void eachAddressHasItsOwnClock() {
        String other = "https://playvalorant.com/en-us/news/tags/patch-notes/";
        when(registry.sourcesFor(1L)).thenReturn(List.of(new Source(Kind.RIOT_NEWS, PAGE, Duration.ofHours(12)),
                new Source(Kind.RIOT_NEWS, other, Duration.ofHours(12))));
        when(feeds.readRiotNews(other)).thenReturn(List.of());

        source.fetchLatest(GAME);
        source.fetchLatest(GAME);

        verify(feeds, times(1)).readRiotNews(PAGE);
        verify(feeds, times(1)).readRiotNews(other);
        verifyNoMoreInteractions(feeds);
    }

    @Test
    void theSourceKindsAreRoutedToTheRightReader() {
        when(registry.sourcesFor(1L)).thenReturn(List.of(
                new Source(Kind.RSS, "https://a.test/feed.rss", Duration.ZERO),
                new Source(Kind.HELP_CENTER, "https://b.test/articles.json", Duration.ZERO)));
        when(feeds.readRss("https://a.test/feed.rss")).thenReturn(List.of());
        when(feeds.readHelpCenter("https://b.test/articles.json")).thenReturn(List.of());

        source.fetchLatest(GAME);

        verify(feeds).readRss("https://a.test/feed.rss");
        verify(feeds).readHelpCenter("https://b.test/articles.json");
    }
}
