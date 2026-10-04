package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behind a reverse proxy the app must rate-limit the visitor, not the proxy. These tests use a real connection from
 * 127.0.0.1, which Tomcat counts as a trusted proxy (as it does the private ranges Caddy and a VPC use), the way
 * production runs ({@code server.forward-headers-strategy=native}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.forward-headers-strategy=native",
        "app.security.rate-limit.login.burst=3", "app.security.rate-limit.login.per-minute=1"})
@ActiveProfiles("test")
class ClientAddressBehindProxyTest {

    @LocalServerPort int port;
    @MockitoBean SteamNewsClient news; // keeps background fetches off the network

    @Test
    void theAddressTheProxyForwardsDecidesWhoIsLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(HttpProbe.wasRefused(HttpProbe.startSignIn(port, "198.51.100.21"))).isFalse();
        }

        assertThat(HttpProbe.wasRefused(HttpProbe.startSignIn(port, "198.51.100.21"))).isTrue();
        // a different visitor behind the same proxy has their own allowance
        assertThat(HttpProbe.wasRefused(HttpProbe.startSignIn(port, "198.51.100.22"))).isFalse();
    }

    @Test
    void anAddressTheVisitorAddsToTheHeaderThemselvesDoesNotBuyAFreshAllowance() throws Exception {
        // the proxy appends the address it saw to the END of X-Forwarded-For; anything before that came from the visitor
        for (int i = 0; i < 3; i++) {
            assertThat(HttpProbe.wasRefused(HttpProbe.startSignIn(port, "203.0.113." + i + ", 198.51.100.23"))).isFalse();
        }

        assertThat(HttpProbe.wasRefused(HttpProbe.startSignIn(port, "203.0.113.99, 198.51.100.23"))).isTrue();
    }
}
