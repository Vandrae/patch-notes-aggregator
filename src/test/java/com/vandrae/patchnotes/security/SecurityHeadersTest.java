package com.vandrae.patchnotes.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** The browser-facing hardening headers, on every kind of response: the app's pages, the API, and the health check. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class SecurityHeadersTest {

    @Autowired MockMvc mvc;

    private static final List<String> PATHS = List.of("/feed", "/api/me", "/actuator/health");

    @Test
    void everyResponseCarriesTheHardeningHeaders() throws Exception {
        for (String path : PATHS) {
            ResultActions result = mvc.perform(get(path));
            result.andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Permissions-Policy", SecurityConfig.PERMISSIONS_POLICY))
                    .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }

    @Test
    void theContentSecurityPolicyAllowsNoInlineScriptNoEvalAndNoForeignOrigins() {
        String csp = SecurityConfig.CONTENT_SECURITY_POLICY;

        assertThat(csp).doesNotContain("unsafe-eval").doesNotContain("unsafe-inline");
        assertThat(csp).contains("default-src 'self'", "script-src 'self'", "object-src 'none'", "frame-ancestors 'none'",
                "base-uri 'self'", "form-action 'self'", "connect-src 'self'");
        // the one outside origin: Steam's CDN, for images only (covers, icons, avatars)
        assertThat(csp).contains("img-src 'self' data: https://*.steamstatic.com");
        assertThat(csp.split("; ")).filteredOn(d -> !d.startsWith("img-src")).noneMatch(d -> d.contains("steamstatic"));
        assertThat(csp.split("; ")).noneMatch(d -> d.matches(".*(^|\\s)\\*(\\s|$).*")); // no bare wildcard anywhere
    }

    @Test
    void strictTransportSecurityIsSentOnlyOverHttps() throws Exception {
        mvc.perform(get("/api/me")).andExpect(header().doesNotExist("Strict-Transport-Security"));

        String hsts = mvc.perform(get("/api/me").secure(true)).andReturn().getResponse().getHeader("Strict-Transport-Security");

        assertThat(hsts).contains("includeSubDomains");
        assertThat(hsts).matches("max-age=\\d+.*");
        assertThat(Long.parseLong(hsts.replaceAll("max-age=(\\d+).*", "$1"))).isGreaterThanOrEqualTo(31_536_000L); // a year
    }

    @Test
    void apiResponsesAreNeverCached() throws Exception {
        mvc.perform(get("/api/me")).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }
}
