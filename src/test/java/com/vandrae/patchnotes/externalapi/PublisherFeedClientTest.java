package com.vandrae.patchnotes.externalapi;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client against a throwaway local server: what it asks for, and how it fails (with messages safe to log). */
class PublisherFeedClientTest {

    private static final String RSS = "<rss><channel><item><title>Patch 7</title><link>https://p.test/7</link><guid>g7</guid>"
            + "<pubDate>Mon, 05 Oct 2026 12:00:00 GMT</pubDate><description>body</description></item></channel></rss>";

    private HttpServer server;
    private String base;
    private final AtomicReference<String> userAgent = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/rss", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            reply(exchange, 200, RSS);
        });
        server.createContext("/missing", exchange -> reply(exchange, 404, "nope"));
        server.createContext("/html", exchange -> reply(exchange, 200, "<html><body>Just a moment...</body></html>"));
        server.createContext("/badjson", exchange -> reply(exchange, 200, "{\"articles\": [ SECRET-LOOKING-TEXT"));
        server.createContext("/huge", exchange -> reply(exchange, 200, "x".repeat(2_000)));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/rss");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private PublisherFeedClient client(int maxBytes) {
        return new PublisherFeedClient(new PublisherFeedProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), maxBytes,
                "test-agent (+https://example.test)"));
    }

    @Test
    void readsAFeedAndIntroducesItselfHonestly() {
        var posts = client(1_000_000).readRss(base + "/rss");

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).title()).isEqualTo("Patch 7");
        assertThat(userAgent.get()).isEqualTo("test-agent (+https://example.test)");
        assertThat(accept.get()).contains("rss");
    }

    @Test
    void followsARedirect() {
        assertThat(client(1_000_000).readRss(base + "/redirect")).hasSize(1);
    }

    @Test
    void aNonOkAnswerIsAFailureNamingTheFeedAndTheStatusOnly() {
        assertThatThrownBy(() -> client(1_000_000).readRss(base + "/missing?token=SECRET"))
                .isInstanceOf(PublisherFeedException.class)
                .hasMessageContaining("localhost/missing")
                .hasMessageContaining("HTTP 404")
                .hasMessageNotContaining("SECRET"); // the query string is never repeated
    }

    @Test
    void aBodyInTheWrongShapeIsReportedWithoutQuotingIt() {
        assertThatThrownBy(() -> client(1_000_000).readHelpCenter(base + "/badjson"))
                .isInstanceOf(PublisherFeedException.class)
                .hasMessageContaining("not in the expected format")
                .hasMessageNotContaining("SECRET-LOOKING-TEXT");
    }

    @Test
    void anHtmlErrorPageInPlaceOfAnRssFeedGivesNoPostsRatherThanGarbage() {
        // a bot-check page is valid markup but has no items: "nothing came back", which the fetch service already flags
        assertThat(client(1_000_000).readRss(base + "/html")).isEmpty();
    }

    @Test
    void aResponseOverTheSizeLimitIsRefused() {
        assertThatThrownBy(() -> client(1_000).readRss(base + "/huge"))
                .isInstanceOf(PublisherFeedException.class)
                .hasMessageContaining("larger than 1000 bytes");
    }

    @Test
    void anUnreachablePublisherIsAFailure() {
        server.stop(0);

        assertThatThrownBy(() -> client(1_000_000).readRss(base + "/rss"))
                .isInstanceOf(PublisherFeedException.class)
                .hasMessageContaining("could not be read");
    }

    @Test
    void onlyHttpsIsAcceptedExceptForThisMachine() {
        assertThatThrownBy(() -> client(1_000).readRss("http://publisher.example/feed.rss"))
                .isInstanceOf(PublisherFeedException.class).hasMessageContaining("must be https");
        assertThatThrownBy(() -> client(1_000).readRss("file:///etc/passwd"))
                .isInstanceOf(PublisherFeedException.class);
        assertThatThrownBy(() -> client(1_000).readRss("not a url"))
                .isInstanceOf(PublisherFeedException.class);
    }
}
