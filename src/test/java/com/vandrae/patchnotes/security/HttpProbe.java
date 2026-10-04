package com.vandrae.patchnotes.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Plain HTTP calls to a running test server, for the tests that need a real connection (and so a real client address). */
final class HttpProbe {

    private static final HttpClient CLIENT = HttpClient.newHttpClient(); // does not follow redirects, which is what we want to see

    private HttpProbe() {
    }

    /** Starts a sign-in from the test machine (127.0.0.1), optionally claiming to be forwarded for {@code forwardedFor}. */
    static HttpResponse<Void> startSignIn(int port, String forwardedFor) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + SteamLoginController.LOGIN_PATH));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    static boolean wasRefused(HttpResponse<Void> response) {
        return response.headers().firstValue("Location").orElse("").equals(SteamLoginController.RATE_LIMITED_REDIRECT);
    }
}
