package com.vandrae.patchnotes.externalapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SteamStoreClientTest {

    private static final String BASE = "https://steam.test";
    private static final String KEY = "TESTKEY1234567890ABCDEF";
    private static final String BODY = """
            {"response":{"apps":[
              {"appid":10,"name":"Counter-Strike","last_modified":1745368572,"price_change_number":39392500},
              {"appid":1422450,"name":"Deadlock","last_modified":1790000000,"price_change_number":1}
            ],"have_more_results":true,"last_appid":1422450}}
            """;

    private MockRestServiceServer server;
    private SteamStoreClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = clientWithKey(builder, KEY);
    }

    private static SteamStoreClient clientWithKey(RestClient.Builder builder, String key) {
        var props = new SteamProperties(BASE, key, 20, Duration.ofSeconds(1), Duration.ofSeconds(1), 3,
                Duration.ofMillis(1), Duration.ofSeconds(5), 50_000);
        return new SteamStoreClient(props, builder.build());
    }

    @Test
    void asksForGamesOnlyWithTheKeyAndTheLargestPage() {
        server.expect(method(HttpMethod.GET))
                .andExpect(requestTo(startsWith(BASE + "/IStoreService/GetAppList/v1/")))
                .andExpect(queryParam("key", KEY))
                .andExpect(queryParam("include_games", "true"))
                .andExpect(queryParam("include_dlc", "false"))
                .andExpect(queryParam("include_software", "false"))
                .andExpect(queryParam("include_videos", "false"))
                .andExpect(queryParam("include_hardware", "false"))
                .andExpect(queryParam("max_results", "50000"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("last_appid"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("if_modified_since"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        client.getAppList(0, null);

        server.verify();
    }

    @Test
    void parsesAPageAndItsContinuationCursor() {
        server.expect(requestTo(startsWith(BASE))).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        SteamAppPage page = client.getAppList(0, null);

        assertThat(page.apps()).containsExactly(
                new SteamApp(10, "Counter-Strike", 1745368572L),
                new SteamApp(1422450, "Deadlock", 1790000000L));
        assertThat(page.hasMore()).isTrue();
        assertThat(page.lastAppId()).isEqualTo(1422450);
    }

    @Test
    void sendsTheCursorAndTheIncrementalTimestampWhenGiven() {
        server.expect(requestTo(startsWith(BASE)))
                .andExpect(queryParam("last_appid", "1422450"))
                .andExpect(queryParam("if_modified_since", "1790000000"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        client.getAppList(1422450, Instant.ofEpochSecond(1790000000L));

        server.verify();
    }

    @Test
    void anEmptyAnswerIsAnEmptyLastPage() {
        server.expect(requestTo(startsWith(BASE)))
                .andRespond(withSuccess("{\"response\":{}}", MediaType.APPLICATION_JSON));

        SteamAppPage page = client.getAppList(0, null);

        assertThat(page.apps()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void retriesTransientFailuresThenSucceeds() {
        server.expect(ExpectedCount.twice(), requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE))).andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(client.getAppList(0, null).apps()).hasSize(2);
        server.verify();
    }

    @Test
    void refusesToRunWithoutAKey() {
        SteamStoreClient keyless = clientWithKey(RestClient.builder().baseUrl(BASE), "");

        assertThat(keyless.isConfigured()).isFalse();
        assertThatThrownBy(() -> keyless.getAppList(0, null)).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------- the key must never leak

    @Test
    void anExhaustedRetryReportsTheStatusButNeverTheKey() {
        server.expect(ExpectedCount.times(4), requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.getAppList(0, null))
                .isInstanceOf(SteamApiException.class)
                .hasMessageContaining("HTTP 502")
                .hasMessageNotContaining(KEY)
                .hasNoCause();
    }

    @Test
    void aNetworkErrorWhoseOwnMessageContainsTheUrlDoesNotLeakTheKey() {
        // exactly what Spring does: the I/O exception message includes the full request URL, which includes the key
        server.expect(ExpectedCount.times(4), requestTo(startsWith(BASE)))
                .andRespond(request -> {
                    throw new IOException("Connection reset while calling " + request.getURI());
                });

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> client.getAppList(0, null));

        assertThat(thrown).isInstanceOf(SteamApiException.class).hasNoCause();
        assertThat(thrown.getMessage()).doesNotContain(KEY).doesNotContain("key=");
        assertThat(thrown.toString()).doesNotContain(KEY);
        var stackTrace = new java.io.StringWriter();
        thrown.printStackTrace(new java.io.PrintWriter(stackTrace));
        assertThat(stackTrace.toString()).doesNotContain(KEY);
    }

    @Test
    void aPermanentClientErrorFailsAtOnceWithoutTheKey() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.getAppList(0, null))
                .isInstanceOf(SteamApiException.class)
                .hasMessageContaining("HTTP 403")
                .hasMessageNotContaining(KEY);
        server.verify();
    }
}
