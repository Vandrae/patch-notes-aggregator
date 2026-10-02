package com.vandrae.patchnotes.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectTargetsTest {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/feed", "/games/7", "/games/7?tab=notes&x=1", "/search?q=elden%20ring"})
    void keepsPlainSameOriginPaths(String next) {
        assertThat(RedirectTargets.sanitize(next)).isEqualTo(next);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "https://evil.test",
            "http://evil.test/feed",
            "//evil.test",
            "//evil.test/feed",
            "/\\evil.test",
            "\\\\evil.test",
            "feed",
            "javascript:alert(1)",
            "/ok\r\nSet-Cookie: pwned=1",
            "/ok\u0000",
            "/%0d%0a" // percent-encoded is harmless as-is, but must still be a valid relative URI
    })
    void replacesAnythingElseWithTheHomePage(String next) {
        String result = RedirectTargets.sanitize(next);
        if ("/%0d%0a".equals(next)) {
            assertThat(result).isEqualTo(next);
        } else {
            assertThat(result).isEqualTo("/");
        }
    }

    @Test
    void rejectsAbsurdlyLongValues() {
        assertThat(RedirectTargets.sanitize("/" + "a".repeat(600))).isEqualTo("/");
    }
}
