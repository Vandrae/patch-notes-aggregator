package com.vandrae.patchnotes.externalapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SteamNewsClientTest {

    private static final String BASE = "https://steam.test";
    private static final String BODY = """
            {"appnews":{"appid":1374490,"newsitems":[
              {"gid":"1844751498233777","title":"1.0.0.6 is now Live!","url":"https://example.test/a",
               "is_external_url":true,"author":"Mod Doom","contents":"[p]Fixed things[/p]",
               "feedlabel":"Community Announcements","date":1790676675,
               "feedname":"steam_community_announcements","feed_type":1,"appid":1374490,"tags":["patchnotes"]},
              {"gid":"1844751498232545","title":"Press piece","url":"https://example.test/b",
               "is_external_url":true,"author":"Someone","contents":"hi","feedlabel":"PC Gamer","date":1790630242,
               "feedname":"PC Gamer","feed_type":0,"appid":1374490}
            ]}}
            """;

    private MockRestServiceServer server;
    private SteamNewsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new SteamProperties(BASE, "super-secret-key", 20, Duration.ofSeconds(1), Duration.ofSeconds(1),
                3, Duration.ofMillis(1), Duration.ofSeconds(5), 50_000);
        client = new SteamNewsClient(props, builder.build());
    }

    @Test
    void parsesSteamsPayloadIncludingOptionalFields() {
        server.expect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/ISteamNews/GetNewsForApp/v2/")))
                .andExpect(queryParam("appid", "1374490"))
                .andExpect(queryParam("maxlength", "0"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        List<SteamNewsItem> items = client.getNewsForApp(1374490);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).gid()).isEqualTo("1844751498233777");
        assertThat(items.get(0).feedType()).isEqualTo(1);
        assertThat(items.get(0).date()).isEqualTo(1790676675L);
        assertThat(items.get(0).tags()).containsExactly("patchnotes");
        assertThat(items.get(1).tags()).isNull();
        server.verify();
    }

    @Test
    void neverSendsTheApiKeyWithNewsRequests() {
        server.expect(requestTo(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("key="))))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        client.getNewsForApp(1374490);

        server.verify();
    }

    @Test
    void retriesTransientServerErrorsThenSucceeds() {
        server.expect(ExpectedCount.times(2), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        assertThat(client.getNewsForApp(1374490)).hasSize(2);
        server.verify();
    }

    @Test
    void aRateLimitingResponseIsReportedAtOnceInsteadOfBeingRetried() {
        // retrying straight away would be one more request counted against the limit; the caller decides how long to wait
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getNewsForApp(1374490)).isInstanceOf(SteamRateLimitedException.class);
        server.verify(); // exactly one request
    }

    @Test
    void givesUpAfterTheConfiguredNumberOfRetries() {
        // 1 initial attempt + 3 retries
        server.expect(ExpectedCount.times(4), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.getNewsForApp(1374490)).isInstanceOf(SteamApiException.class);
        server.verify();
    }

    @Test
    void aForbiddenOrNotFoundAnswerMeansTheAppHasNoNewsFeedNotThatSomethingBroke() {
        // Steam answers 403 {} for any app without a news feed (tools, delisted apps, unknown ids). Treating that as a
        // failure would leave a watched game's fetch failing forever, and its "watch" event replayed on every restart.
        server.expect(ExpectedCount.twice(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{}").contentType(MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getNewsForApp(1695790)).isEmpty();
        assertThat(client.getNewsForApp(99999999)).isEmpty();
        assertThat(client.getNewsForApp(42)).isEmpty();
        server.verify(); // exactly one request each: no retrying a definite answer
    }

    @Test
    void otherPermanentClientErrorsAreStillFailuresAndAreNotRetried() {
        server.expect(ExpectedCount.once(), requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.getNewsForApp(1374490)).isInstanceOf(SteamApiException.class);
        server.verify(); // exactly one request was made
    }

    @Test
    void treatsAnEmptyOrMissingNewsListAsNoItems() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withSuccess("{\"appnews\":{\"appid\":1374490}}", MediaType.APPLICATION_JSON));

        assertThat(client.getNewsForApp(1374490)).isEmpty();
    }
}
