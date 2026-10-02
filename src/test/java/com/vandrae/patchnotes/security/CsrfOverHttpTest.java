package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.externalapi.SteamNewsClient;
import com.vandrae.patchnotes.users.UserAccounts;
import com.vandrae.patchnotes.users.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSRF behaviour against a real server port instead of MockMvc. MockMvc's {@code csrf()} helper permanently
 * swaps the shared filter's token repository, which would hide the real cookie from any test that runs after it;
 * a real HTTP round trip is both immune to that and closer to what a browser does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CsrfOverHttpTest {

    @Value("${local.server.port}") int port;
    @Autowired UserAccounts users;
    @Autowired TokenService tokens;
    @MockitoBean SteamNewsClient news;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void theAppGetsACsrfCookieItCanReadAndEchoOnItsFirstCall() throws Exception {
        // the app's first request on load is GET /api/me; even the 401 hands out the token
        HttpResponse<String> response = send("GET", "/api/me", null);

        assertThat(response.statusCode()).isEqualTo(401);
        String xsrf = setCookies(response).stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(xsrf).contains("SameSite=Lax").doesNotContain("HttpOnly"); // JavaScript must be able to read it
    }

    @Test
    void aSessionCookieAloneCannotChangeAnything_theCsrfTokenIsRequired() throws Exception {
        UserSummary user = users.findOrCreateBySteamId(76561198300000001L, "Victim", null);
        String session = SessionCookies.SESSION + "=" + tokens.issue(user.id());

        // what a forged cross-site request looks like: the browser attaches the cookie, but the page can't add the header
        HttpResponse<String> forged = send("DELETE", "/api/me", session);
        assertThat(forged.statusCode()).isEqualTo(403);
        assertThat(users.findById(user.id())).isPresent();

        // the real app: it read the XSRF-TOKEN cookie and echoes it in a header
        String xsrfToken = setCookies(send("GET", "/api/me", session)).stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow()
                .split(";")[0].substring("XSRF-TOKEN=".length());
        HttpResponse<String> genuine = send("DELETE", "/api/me", session + "; XSRF-TOKEN=" + xsrfToken, xsrfToken);
        assertThat(genuine.statusCode()).isEqualTo(204);
        assertThat(users.findById(user.id())).isEmpty();
    }

    @Test
    void theCsrfTokenStaysStableAcrossSignedInRequests_soAPageThatPollsCannotInvalidateItsOwnToken() throws Exception {
        // Spring's CSRF layer treats every request of a stateless signed-in user as a brand new login and rotates the token
        // (clearing the cookie and issuing another) on every response. A page that polls, like this app's while details
        // load, would then keep invalidating the token another request had just read, and writes would fail with 403.
        UserSummary user = users.findOrCreateBySteamId(76561198300000002L, "Poller", null);
        String session = SessionCookies.SESSION + "=" + tokens.issue(user.id());
        String token = xsrfCookieValue(send("GET", "/api/me", session)).orElseThrow();

        for (int i = 0; i < 5; i++) {
            HttpResponse<String> poll = send("GET", "/api/me", session + "; XSRF-TOKEN=" + token);
            assertThat(poll.statusCode()).isEqualTo(200);
            assertThat(setCookies(poll)).as("poll %d must not re-issue or clear the CSRF cookie", i + 1)
                    .noneMatch(c -> c.startsWith("XSRF-TOKEN="));
        }

        // and the token read before all that polling is still good
        HttpResponse<String> write = send("DELETE", "/api/me", session + "; XSRF-TOKEN=" + token, token);
        assertThat(write.statusCode()).isEqualTo(204);
        assertThat(users.findById(user.id())).isEmpty();
    }

    private static java.util.Optional<String> xsrfCookieValue(HttpResponse<?> response) {
        return setCookies(response).stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=") && !c.startsWith("XSRF-TOKEN=;"))
                .map(c -> c.split(";")[0].substring("XSRF-TOKEN=".length()))
                .findFirst();
    }

    private HttpResponse<String> send(String method, String path, String cookie) throws Exception {
        return send(method, path, cookie, null);
    }

    private HttpResponse<String> send(String method, String path, String cookie, String csrfHeader) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (csrfHeader != null) {
            request.header("X-XSRF-TOKEN", csrfHeader);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> setCookies(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie");
    }
}
