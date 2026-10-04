package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** With no proxy in front (the default), X-Forwarded-For is just something the visitor typed, and is ignored. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.security.rate-limit.login.burst=3", "app.security.rate-limit.login.per-minute=1"})
@ActiveProfiles("test")
class ClientAddressDirectTest {

    @LocalServerPort int port;
    @MockitoBean SteamNewsClient news; // keeps background fetches off the network

    @Test
    void aForwardedForHeaderChangesNothingWhenNoProxyIsConfigured() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(HttpProbe.wasRefused(HttpProbe.startSignIn(port, "198.51.100." + i))).isFalse();
        }

        assertThat(HttpProbe.wasRefused(HttpProbe.startSignIn(port, "198.51.100.200"))).isTrue();
    }
}
