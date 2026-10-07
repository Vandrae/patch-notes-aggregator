package com.vandrae.patchnotes.externalapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Turns the two shapes of publisher feed we read into {@link PublisherPost}s, newest first: RSS 2.0 / Atom (any publisher,
 * and Discourse forums, which have one per category) and the help-centre article list (Zendesk's public API, which
 * Mojang uses for Minecraft's changelogs). A post without a title, address, id or date is skipped, since it could not be
 * shown, linked or de-duplicated.
 */
final class PublisherFeedParsers {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PublisherFeedParsers() {
    }

    static List<PublisherPost> parseRss(String xml) {
        var doc = Jsoup.parse(xml, "", Parser.xmlParser());
        List<PublisherPost> posts = new ArrayList<>();
        for (Element item : doc.select("item")) { // RSS 2.0
            String link = text(item, "link");
            String id = firstNonBlank(text(item, "guid"), link);
            String body = firstNonBlank(text(item, "content|encoded"), text(item, "description"));
            add(posts, id, text(item, "title"), link, body, date(firstNonBlank(text(item, "pubDate"), text(item, "dc|date"))));
        }
        for (Element entry : doc.select("entry")) { // Atom
            Element alternate = entry.selectFirst("link[rel=alternate], link:not([rel])");
            String link = alternate == null ? "" : alternate.attr("href");
            String body = firstNonBlank(text(entry, "content"), text(entry, "summary"));
            add(posts, firstNonBlank(text(entry, "id"), link), text(entry, "title"), link, body,
                    date(firstNonBlank(text(entry, "published"), text(entry, "updated"))));
        }
        return newestFirst(posts);
    }

    static List<PublisherPost> parseHelpCenter(String json) {
        List<PublisherPost> posts = new ArrayList<>();
        for (Article article : JSON.readValue(json, HelpCenterArticles.class).articles()) {
            if (article.draft()) {
                continue;
            }
            add(posts, article.id() == null ? "" : article.id().toString(), article.title(), article.url(), article.body(),
                    date(article.createdAt()));
        }
        return newestFirst(posts);
    }

    private static final String NEXT_DATA_OPEN = "<script id=\"__NEXT_DATA__\"";

    /**
     * A Riot Games news page. The page is built with Next.js, which leaves the data it was built from in one
     * {@code <script id="__NEXT_DATA__">} block: {@code props.pageProps.page.blades[]}, one of which is the article grid
     * with an {@code items[]} list, newest first. Every item has a title, a site-relative link, a publish date and a short
     * teaser Riot wrote. Items are found by their shape (a title, a link and a date) rather than by their position, so a
     * reordered page still reads; a page where nothing of that shape is found is an error, not an empty list, because
     * "Riot changed its site" must not look the same as "no new patch".
     *
     * @param page where the page was read from: relative links are resolved against it, and only links to the same site are kept
     */
    static List<PublisherPost> parseRiotNews(String html, java.net.URI page) {
        int open = html.indexOf(NEXT_DATA_OPEN);
        int start = open < 0 ? -1 : html.indexOf('>', open);
        int end = start < 0 ? -1 : html.indexOf("</script>", start);
        if (end < 0) {
            throw new IllegalStateException("no embedded page data");
        }
        tools.jackson.databind.JsonNode blades = JSON.readTree(html.substring(start + 1, end))
                .path("props").path("pageProps").path("page").path("blades");
        List<PublisherPost> posts = new ArrayList<>();
        boolean sawArticleList = false;
        for (tools.jackson.databind.JsonNode blade : blades) {
            for (tools.jackson.databind.JsonNode item : blade.path("items")) {
                if (!item.hasNonNull("publishedAt") || !item.hasNonNull("title")) {
                    continue; // not an article card
                }
                sawArticleList = true;
                String link = item.path("action").path("payload").path("url").asString("");
                java.net.URI resolved = link.isBlank() ? null : page.resolve(link);
                if (resolved == null || resolved.getHost() == null || !resolved.getHost().equalsIgnoreCase(page.getHost())) {
                    continue; // a card that leads somewhere else is not this site's own article
                }
                String id = item.path("analytics").path("contentId").asString("");
                add(posts, id.isBlank() ? resolved.getPath() : id, item.path("title").asString(""), resolved.toString(),
                        item.path("description").path("body").asString(""), date(item.path("publishedAt").asString("")));
            }
        }
        if (!sawArticleList) {
            throw new IllegalStateException("no article list in the page data");
        }
        return newestFirst(posts);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HelpCenterArticles(List<Article> articles) {
        HelpCenterArticles {
            articles = articles == null ? List.of() : articles;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Article(Long id, String title, @JsonProperty("html_url") String url, String body,
                           @JsonProperty("created_at") String createdAt, boolean draft) {
    }

    private static void add(List<PublisherPost> posts, String id, String title, String url, String html, Optional<Instant> date) {
        if (id == null || id.isBlank() || title == null || title.isBlank() || url == null || url.isBlank() || date.isEmpty()) {
            return;
        }
        posts.add(new PublisherPost(id.strip(), title.strip(), url.strip(), html == null ? "" : html, date.get()));
    }

    private static List<PublisherPost> newestFirst(List<PublisherPost> posts) {
        return posts.stream().sorted(Comparator.comparing(PublisherPost::publishedAt).reversed()).toList();
    }

    private static String text(Element parent, String selector) {
        Element found = parent.selectFirst(selector);
        return found == null ? "" : found.text();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** RSS dates are RFC 1123 ("Wed, 30 Sep 2026 21:50:53 +0000"), Atom and Zendesk dates ISO 8601. */
    private static Optional<Instant> date(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String v = value.strip();
        try {
            return Optional.of(ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException notRfc1123) {
            try {
                return Optional.of(OffsetDateTime.parse(v).toInstant());
            } catch (DateTimeParseException notIso) {
                return Optional.empty();
            }
        }
    }
}
