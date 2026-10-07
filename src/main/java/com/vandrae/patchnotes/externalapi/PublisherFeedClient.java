package com.vandrae.patchnotes.externalapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads the public patch-note feeds of games that are not on Steam. The addresses come from our own configuration, never
 * from a user, and must be https (plain http is accepted only for this machine, so tests and the browser tests can use a
 * local fake publisher). Failures say which feed and what kind of failure, never more, so a log line cannot leak anything.
 */
@Component
public class PublisherFeedClient {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final HttpClient http;
    private final PublisherFeedProperties props;

    @Autowired
    public PublisherFeedClient(PublisherFeedProperties props) {
        this(props, HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL) // never downgrades from https to http
                .build());
    }

    PublisherFeedClient(PublisherFeedProperties props, HttpClient http) {
        this.props = props;
        this.http = http;
    }

    /** An RSS 2.0 or Atom feed, newest post first. */
    public List<PublisherPost> readRss(String url) {
        return parsed(url, "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.5", PublisherFeedParsers::parseRss);
    }

    /** A help centre's article list (Zendesk's public API, as used for Minecraft's changelogs), newest post first. */
    public List<PublisherPost> readHelpCenter(String url) {
        return parsed(url, "application/json", PublisherFeedParsers::parseHelpCenter);
    }

    /**
     * The article list of a Riot Games news page (leagueoflegends.com, playvalorant.com), newest first. These sites publish no
     * feed, but a news page carries its whole list, with a short teaser Riot wrote for each article, as data inside the
     * page itself. One request to the list page is therefore all it takes; the articles themselves are never opened.
     */
    public List<PublisherPost> readRiotNews(String url) {
        return parsed(url, "text/html", html -> PublisherFeedParsers.parseRiotNews(html, URI.create(url)));
    }

    private List<PublisherPost> parsed(String url, String accept, Function<String, List<PublisherPost>> parser) {
        String body = fetch(url, accept);
        try {
            return parser.apply(body);
        } catch (RuntimeException e) {
            // an HTML error page or broken JSON: say so without quoting the body, which a parser's message may do
            throw new PublisherFeedException("Feed " + name(URI.create(url)) + " is not in the expected format ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private String fetch(String url, String accept) {
        URI uri = checked(url);
        String feed = name(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(props.readTimeout())
                .header("User-Agent", props.userAgent())
                .header("Accept", accept)
                .header("Accept-Encoding", "gzip") // a news page is 430 KB of HTML but about 56 KB compressed: ask for less
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream raw = response.body()) {
                if (response.statusCode() != 200) {
                    throw new PublisherFeedException("Feed " + feed + " answered HTTP " + response.statusCode());
                }
                boolean gzip = "gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""));
                // the size limit applies to what the text grows to once unpacked, so a tiny download cannot become a huge one
                InputStream body = gzip ? new GZIPInputStream(raw) : raw;
                byte[] bytes = body.readNBytes(props.maxBytes() + 1);
                if (bytes.length > props.maxBytes()) {
                    throw new PublisherFeedException("Feed " + feed + " is larger than " + props.maxBytes() + " bytes");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublisherFeedException("Feed " + feed + " was interrupted", e);
        } catch (IOException e) {
            throw new PublisherFeedException("Feed " + feed + " could not be read (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Host and path, without the query string: enough to tell feeds apart in a log, and nothing that could be secret. */
    private static String name(URI uri) {
        return uri.getHost() + uri.getPath();
    }

    private static URI checked(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new PublisherFeedException("Not a valid feed address");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && LOCAL_HOSTS.contains(uri.getHost().toLowerCase());
        if (uri.getHost() == null || !(https || localHttp)) {
            throw new PublisherFeedException("Feed addresses must be https: " + (uri.getHost() == null ? "no host" : uri.getHost()));
        }
        return uri;
    }
}
