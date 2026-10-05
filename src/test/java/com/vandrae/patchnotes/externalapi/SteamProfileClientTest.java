package com.vandrae.patchnotes.externalapi;

import com.vandrae.patchnotes.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The profile lookup sends the API key in the URL, so nothing it logs or throws may contain that URL. */
@ExtendWith(OutputCaptureExtension.class)
class SteamProfileClientTest {

    private static final String BASE = "https://steam.test";
    private static final String KEY = "PROFILEKEY1234567890ABCDEF";
    private static final long STEAM_ID = 76561198000000001L;

    private MockRestServiceServer server;
    private SteamProfileClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new SteamProperties(BASE, KEY, 20, Duration.ofSeconds(1), Duration.ofSeconds(1), 3,
                Duration.ofMillis(1), Duration.ofSeconds(5), 50_000);
        client = new SteamProfileClient(props, builder.build());
    }

    @Test
    void readsTheNameAndAvatarAndSendsTheKey() {
        server.expect(requestTo(startsWith(BASE + "/ISteamUser/GetPlayerSummaries/v2/")))
                .andExpect(queryParam("key", KEY))
                .andExpect(queryParam("steamids", Long.toString(STEAM_ID)))
                .andRespond(withSuccess("""
                        {"response":{"players":[{"steamid":"%d","personaname":"Gabe","avatarfull":"https://avatars.steamstatic.com/a_full.jpg"}]}}
                        """.formatted(STEAM_ID), MediaType.APPLICATION_JSON));

        var player = client.getPlayer(STEAM_ID).orElseThrow();

        assertThat(player.personaName()).isEqualTo("Gabe");
        assertThat(player.avatarUrl()).isEqualTo("https://avatars.steamstatic.com/a_full.jpg");
    }

    @Test
    void aServerErrorIsLoggedByStatusAndNeverWithTheKey(CapturedOutput output) {
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.getPlayer(STEAM_ID)).isEmpty();

        assertThat(output.getAll()).contains("Steam profile lookup failed: HTTP 502").doesNotContain(KEY).doesNotContain("key=");
    }

    @Test
    void aNetworkErrorWhoseMessageContainsTheUrlDoesNotLeakTheKeyIntoTheLog(CapturedOutput output) {
        // exactly what Spring does: the I/O exception message includes the full request URL, which includes the key
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE)))
                .andRespond(request -> {
                    throw new IOException("Connection reset while calling " + request.getURI());
                });

        assertThat(client.getPlayer(STEAM_ID)).isEmpty();

        assertThat(output.getAll()).contains("Steam profile lookup failed").doesNotContain(KEY).doesNotContain("key=");
    }

    @Test
    void configurationObjectsNeverPrintTheirSecrets() {
        var steam = new SteamProperties(BASE, KEY, 20, Duration.ofSeconds(1), Duration.ofSeconds(1), 3,
                Duration.ofMillis(1), Duration.ofSeconds(5), 50_000);
        var jwt = new JwtProperties("a-signing-secret-that-must-stay-secret-0123456789", "issuer", Duration.ofDays(7));

        assertThat(steam.toString()).doesNotContain(KEY).contains("apiKey=<set>");
        assertThat(jwt.toString()).doesNotContain("must-stay-secret").contains("secret=<set>");
        assertThat(new JwtProperties(null, "issuer", Duration.ofDays(7)).toString()).contains("secret=<not set>");
    }
}
