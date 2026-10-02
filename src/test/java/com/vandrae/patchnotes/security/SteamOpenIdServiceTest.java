package com.vandrae.patchnotes.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SteamOpenIdServiceTest {

    private static final String ENDPOINT = "https://steam.test/openid/login";
    private static final String BASE = "http://localhost:8080";
    private static final String STATE = "abc123";
    private static final long STEAM_ID = 76561198000000001L;

    private MockRestServiceServer steam;
    private SteamOpenIdService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        steam = MockRestServiceServer.bindTo(builder).build();
        service = new SteamOpenIdService(new SteamLoginProperties(ENDPOINT, BASE), builder.build());
    }

    /** A well-formed callback, as Steam would send it. Tests mutate one field at a time. */
    private Map<String, String> validCallback() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("openid.ns", "http://specs.openid.net/auth/2.0");
        p.put("openid.mode", "id_res");
        p.put("openid.op_endpoint", ENDPOINT);
        p.put("openid.claimed_id", "https://steamcommunity.com/openid/id/" + STEAM_ID);
        p.put("openid.identity", "https://steamcommunity.com/openid/id/" + STEAM_ID);
        p.put("openid.return_to", BASE + "/api/auth/steam/callback?state=" + STATE);
        p.put("openid.response_nonce", Instant.now().truncatedTo(ChronoUnit.SECONDS) + "Zk3j2Lw9");
        p.put("openid.assoc_handle", "1234567890");
        p.put("openid.signed", "signed,op_endpoint,claimed_id,identity,return_to,response_nonce,assoc_handle");
        p.put("openid.sig", "c2lnbmF0dXJl");
        p.put("state", STATE);
        return p;
    }

    private void steamSays(String body) {
        steam.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("openid.mode", "check_authentication")))
                .andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
    }

    private static final String VALID = "ns:http://specs.openid.net/auth/2.0\nis_valid:true\n";
    private static final String INVALID = "ns:http://specs.openid.net/auth/2.0\nis_valid:false\n";

    // ---------------------------------------------------------------- redirect

    @Test
    void buildsTheSteamRedirectWithOurReturnAddressAndRealm() {
        URI redirect = service.redirectUrl(STATE);

        assertThat(redirect.toString()).startsWith(ENDPOINT + "?");
        assertThat(redirect.getRawQuery())
                .contains("openid.mode=checkid_setup")
                .contains("openid.ns=http://specs.openid.net/auth/2.0")
                .contains("openid.identity=http://specs.openid.net/auth/2.0/identifier_select");
        // decoded, the return address is exactly what verify() will demand back
        String decoded = java.net.URLDecoder.decode(redirect.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded).contains("openid.return_to=" + service.returnTo(STATE)).contains("openid.realm=" + BASE);
    }

    // ------------------------------------------------------------------ verify

    @Test
    void acceptsAGenuineResponseAndReturnsTheSteamId() {
        steamSays(VALID);

        assertThat(service.verify(validCallback(), STATE)).contains(STEAM_ID);
        steam.verify();
    }

    @Test
    void sendsSteamBackItsOwnSignedFieldsWhenVerifying() {
        steam.expect(requestTo(ENDPOINT))
                .andExpect(content().formDataContains(Map.of(
                        "openid.mode", "check_authentication",
                        "openid.sig", "c2lnbmF0dXJl",
                        "openid.assoc_handle", "1234567890")))
                .andRespond(withSuccess(VALID, MediaType.TEXT_PLAIN));

        assertThat(service.verify(validCallback(), STATE)).isPresent();
        steam.verify();
    }

    @Test
    void rejectsWhenSteamSaysTheResponseIsNotValid() {
        steamSays(INVALID);

        assertThat(service.verify(validCallback(), STATE)).isEmpty();
        steam.verify();
    }

    @Test
    void rejectsWhenSteamCannotBeReached() {
        steam.expect(requestTo(ENDPOINT)).andRespond(withServerError());

        assertThat(service.verify(validCallback(), STATE)).isEmpty();
    }

    @Test
    void rejectsAReplayedResponseEvenIfSteamWouldAcceptItAgain() {
        steam.expect(org.springframework.test.web.client.ExpectedCount.twice(), requestTo(ENDPOINT))
                .andRespond(withSuccess(VALID, MediaType.TEXT_PLAIN));
        Map<String, String> callback = validCallback();

        assertThat(service.verify(callback, STATE)).isPresent();
        assertThat(service.verify(callback, STATE)).isEmpty();
    }

    // Everything below must be refused without even asking Steam: the mock server has no expectations,
    // so contacting it would fail the test.

    @Test
    void rejectsAReturnAddressThatBelongsToADifferentLoginAttempt() {
        assertThat(service.verify(validCallback(), "someone-elses-state")).isEmpty();
    }

    @Test
    void rejectsAMissingState() {
        assertThat(service.verify(validCallback(), null)).isEmpty();
        assertThat(service.verify(validCallback(), " ")).isEmpty();
    }

    @Test
    void rejectsAReturnAddressOnAnotherSite() {
        Map<String, String> callback = validCallback();
        callback.put("openid.return_to", "https://evil.test/api/auth/steam/callback?state=" + STATE);

        assertThat(service.verify(callback, STATE)).isEmpty();
    }

    @Test
    void rejectsAResponseFromAnotherOpenIdProvider() {
        Map<String, String> callback = validCallback();
        callback.put("openid.op_endpoint", "https://evil.test/openid/login");

        assertThat(service.verify(callback, STATE)).isEmpty();
    }

    @Test
    void rejectsAnIdentityThatIsNotASteamId() {
        for (String forged : new String[]{
                "https://evil.test/openid/id/" + STEAM_ID,
                "http://steamcommunity.com/openid/id/" + STEAM_ID,
                "https://steamcommunity.com/openid/id/12345",
                "https://steamcommunity.com/openid/id/" + STEAM_ID + "9",
                "https://steamcommunity.com/openid/id/" + STEAM_ID + "/../x",
                "https://steamcommunity.com/openid/id/abc"}) {
            Map<String, String> callback = validCallback();
            callback.put("openid.claimed_id", forged);
            callback.put("openid.identity", forged);
            assertThat(service.verify(callback, STATE)).as(forged).isEmpty();
        }
    }

    @Test
    void rejectsAClaimedIdThatDiffersFromTheIdentity() {
        Map<String, String> callback = validCallback();
        callback.put("openid.identity", "https://steamcommunity.com/openid/id/76561198000000002");

        assertThat(service.verify(callback, STATE)).isEmpty();
    }

    @Test
    void rejectsWhenKeyFieldsAreNotCoveredBySteamsSignature() {
        Map<String, String> callback = validCallback();
        callback.put("openid.signed", "signed,op_endpoint,claimed_id,identity,response_nonce,assoc_handle"); // no return_to

        assertThat(service.verify(callback, STATE)).isEmpty();
    }

    @Test
    void rejectsAnythingThatIsNotAnOpenId2PositiveAssertion() {
        Map<String, String> cancelled = validCallback();
        cancelled.put("openid.mode", "cancel");
        Map<String, String> wrongVersion = validCallback();
        wrongVersion.put("openid.ns", "http://openid.net/signon/1.1");

        assertThat(service.verify(cancelled, STATE)).isEmpty();
        assertThat(service.verify(wrongVersion, STATE)).isEmpty();
    }

    @Test
    void rejectsStaleMalformedAndMissingNonces() {
        Map<String, String> stale = validCallback();
        stale.put("openid.response_nonce", Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "Zabc");
        Map<String, String> future = validCallback();
        future.put("openid.response_nonce", Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS) + "Zabc");
        Map<String, String> malformed = validCallback();
        malformed.put("openid.response_nonce", "not-a-timestamp-at-all-xyz");
        Map<String, String> missing = validCallback();
        missing.remove("openid.response_nonce");

        assertThat(service.verify(stale, STATE)).isEmpty();
        assertThat(service.verify(future, STATE)).isEmpty();
        assertThat(service.verify(malformed, STATE)).isEmpty();
        assertThat(service.verify(missing, STATE)).isEmpty();
    }

    @Test
    void rejectsAnEmptyCallback() {
        assertThat(service.verify(Map.of(), STATE)).isEmpty();
    }
}
