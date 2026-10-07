package com.vandrae.patchnotes.externalapi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real responses (trimmed to three posts) from Roblox's developer forum and Minecraft's help centre, plus the odd shapes. */
class PublisherFeedParsersTest {

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/publisher", name), StandardCharsets.UTF_8);
    }

    @Test
    void readsRobloxsRealRssFeedNewestFirstWithAddressDateAndBody() throws IOException {
        List<PublisherPost> posts = PublisherFeedParsers.parseRss(fixture("roblox-release-notes.rss"));

        assertThat(posts).hasSize(3); // the channel's own title and link are not posts
        PublisherPost newest = posts.get(0);
        assertThat(newest.title()).isEqualTo("Release Notes for 741");
        assertThat(newest.url()).isEqualTo("https://devforum.roblox.com/t/release-notes-for-741/4906281");
        assertThat(newest.externalId()).isEqualTo("devforum.roblox.com-topic-4906281");
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-30T21:50:53Z"));
        assertThat(newest.html()).contains("release notes are here").contains("<ul>");
        assertThat(posts).extracting(PublisherPost::title)
                .containsExactly("Release Notes for 741", "Release Notes for 740", "Release Notes for 739");
    }

    @Test
    void readsMinecraftsRealHelpCentreArticles() throws IOException {
        List<PublisherPost> posts = PublisherFeedParsers.parseHelpCenter(fixture("minecraft-release-changelogs.json"));

        assertThat(posts).hasSize(3);
        PublisherPost newest = posts.get(0);
        assertThat(newest.title()).isEqualTo("Minecraft: Bedrock Edition 26.52 Hotfix Changelog");
        assertThat(newest.url()).startsWith("https://feedback.minecraft.net/hc/en-us/articles/");
        assertThat(newest.externalId()).matches("\\d+");
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-09-25T22:05:12Z"));
        assertThat(newest.html()).isNotBlank();
    }

    @Test
    void helpCentreDraftsAreSkippedAndPostsMissingAnythingNeededAreDropped() {
        String json = """
                {"articles":[
                  {"id":1,"title":"A draft","html_url":"https://x.test/1","created_at":"2026-01-01T00:00:00Z","body":"b","draft":true},
                  {"id":2,"title":"","html_url":"https://x.test/2","created_at":"2026-01-02T00:00:00Z","body":"b","draft":false},
                  {"id":3,"title":"No address","html_url":"","created_at":"2026-01-03T00:00:00Z","body":"b","draft":false},
                  {"id":4,"title":"No date","html_url":"https://x.test/4","body":"b","draft":false},
                  {"id":5,"title":"Fine","html_url":"https://x.test/5","created_at":"2026-01-05T00:00:00Z","body":"b","draft":false,"surprise":"ignored"}
                ]}""";

        assertThat(PublisherFeedParsers.parseHelpCenter(json)).extracting(PublisherPost::title).containsExactly("Fine");
    }

    // ------------------------------------------------------------------ Riot Games news pages

    private static final URI LOL = URI.create("https://www.leagueoflegends.com/en-us/news/tags/patch-notes/");

    @Test
    void readsLeagueOfLegendsRealNewsPageNewestFirstWithRiotsOwnTeaser() throws IOException {
        List<PublisherPost> posts = PublisherFeedParsers.parseRiotNews(fixture("league-of-legends-patch-notes.html"), LOL);

        assertThat(posts).extracting(PublisherPost::title).containsExactly(
                "League of Legends Patch 26.20 Notes", "League of Legends Patch 26.19 Notes", "League of Legends Patch 26.18 Notes");
        PublisherPost newest = posts.get(0);
        assertThat(newest.url()).isEqualTo("https://www.leagueoflegends.com/en-us/news/game-updates/league-of-legends-patch-26-20-notes");
        assertThat(newest.publishedAt()).isEqualTo(Instant.parse("2026-10-06T18:00:00Z"));
        assertThat(newest.externalId()).isEqualTo("d1feeede-bde0-47e4-bb9c-b06921b070d6.en-us");
        assertThat(newest.html()).contains("the Worlds patch is here");
    }

    @Test
    void readsValorantsRealNewsPageToo() throws IOException {
        List<PublisherPost> posts = PublisherFeedParsers.parseRiotNews(fixture("valorant-patch-notes.html"),
                URI.create("https://playvalorant.com/en-us/news/tags/patch-notes/"));

        assertThat(posts).hasSize(3);
        assertThat(posts.get(0).title()).isEqualTo("VALORANT Patch Notes 13.06");
        assertThat(posts.get(0).url()).isEqualTo("https://playvalorant.com/en-us/news/game-updates/valorant-patch-notes-13-06");
        assertThat(posts.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-22T13:00:00Z"));
    }

    private static String riotPage(String items) {
        return "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"page\":{\"blades\":[{\"type\":\"masthead\"},{\"type\":\"grid\",\"items\":[" + items + "]}]}}}}"
                + "</script></body></html>";
    }

    @Test
    void aCardThatLeadsToAnotherSiteIsNotThisSitesArticleAndIsSkipped() {
        String page = riotPage("""
                {"title":"Ours","action":{"payload":{"url":"/en-us/news/game-updates/ours"}},"publishedAt":"2026-10-01T00:00:00Z"},
                {"title":"Elsewhere","action":{"payload":{"url":"https://evil.example/phish"}},"publishedAt":"2026-10-02T00:00:00Z"},
                {"title":"No link","publishedAt":"2026-10-03T00:00:00Z"}""");

        assertThat(PublisherFeedParsers.parseRiotNews(page, LOL)).extracting(PublisherPost::title).containsExactly("Ours");
    }

    @Test
    void itFindsTheArticleListByItsShapeNotByWhereItSits() {
        String reordered = "<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"page\":{\"blades\":["
                + "{\"items\":[{\"title\":\"Not an article\",\"id\":1}]},"
                + "{\"items\":[{\"title\":\"Patch 1\",\"action\":{\"payload\":{\"url\":\"/p1\"}},\"publishedAt\":\"2026-10-01T00:00:00Z\"}]}"
                + "]}}}}</script>";

        assertThat(PublisherFeedParsers.parseRiotNews(reordered, LOL)).extracting(PublisherPost::title).containsExactly("Patch 1");
    }

    @Test
    void aPageThatNoLongerHasItsDataIsAnErrorNotAnEmptyList() {
        // "Riot changed its site" must not look the same as "no new patch"
        assertThatThrownBy(() -> PublisherFeedParsers.parseRiotNews("<html><body>A redesigned page</body></html>", LOL))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PublisherFeedParsers.parseRiotNews(riotPage(""), LOL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsAtomEntriesToo() {
        String atom = """
                <?xml version="1.0" encoding="utf-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Blog</title>
                  <link href="https://blog.test/" rel="alternate"/>
                  <entry>
                    <title>Patch 1.2</title>
                    <link rel="alternate" href="https://blog.test/patch-1-2"/>
                    <id>tag:blog.test,2026:1.2</id>
                    <updated>2026-08-01T10:00:00Z</updated>
                    <content type="html">&lt;p&gt;Fixed &lt;b&gt;things&lt;/b&gt;&lt;/p&gt;</content>
                  </entry>
                </feed>""";

        List<PublisherPost> posts = PublisherFeedParsers.parseRss(atom);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).title()).isEqualTo("Patch 1.2");
        assertThat(posts.get(0).url()).isEqualTo("https://blog.test/patch-1-2");
        assertThat(posts.get(0).externalId()).isEqualTo("tag:blog.test,2026:1.2");
        assertThat(posts.get(0).publishedAt()).isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
        assertThat(posts.get(0).html()).isEqualTo("<p>Fixed <b>things</b></p>");
    }

    @Test
    void anRssItemUsesItsLinkAsItsIdWhenItHasNoGuidAndPrefersFullContentOverTheTeaser() {
        String rss = """
                <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>T</title>
                  <item><title>Notes</title><link>https://p.test/notes</link><pubDate>Mon, 05 Oct 2026 12:00:00 GMT</pubDate>
                    <description>teaser</description><content:encoded><![CDATA[<p>the full text</p>]]></content:encoded></item>
                </channel></rss>""";

        List<PublisherPost> posts = PublisherFeedParsers.parseRss(rss);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).externalId()).isEqualTo("https://p.test/notes");
        assertThat(posts.get(0).html()).isEqualTo("<p>the full text</p>");
        assertThat(posts.get(0).publishedAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
    }

    @Test
    void anItemWithAnUnreadableDateIsDroppedNotGivenTodaysDate() {
        String rss = "<rss><channel><item><title>x</title><link>https://p.test/x</link><pubDate>last tuesday</pubDate></item></channel></rss>";

        assertThat(PublisherFeedParsers.parseRss(rss)).isEmpty();
    }
}
