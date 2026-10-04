package com.vandrae.patchnotes.security;

import com.vandrae.patchnotes.PatchNotesApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code prod} profile: what it switches on, and the settings it refuses to start without. The first group are fast
 * unit tests of the checks; the last two start the whole application with the production profile.
 */
class ProductionProfileTest {

    private static JwtProperties secret(String value) {
        return new JwtProperties(value, "patch-notes-aggregator", java.time.Duration.ofDays(7));
    }

    // ------------------------------------------------------------------ the signing key

    @Test
    void productionRefusesToStartWithoutASigningKey() {
        var production = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        production.setActiveProfiles("prod");

        for (String blank : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> new SecurityConfig().jwtSigningKey(secret(blank), production))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        }
    }

    @Test
    void outsideProductionAMissingKeyGetsARandomOneSoDevelopmentNeedsNoSetup() {
        var key = new SecurityConfig().jwtSigningKey(secret(""), new MockEnvironment());

        assertThat(key.getEncoded()).hasSize(32);
    }

    @Test
    void aKeyShorterThanTheHashNeedsIsRefusedInEveryProfile() {
        for (String profile : new String[]{"prod", "dev"}) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles(profile);
            assertThatThrownBy(() -> new SecurityConfig().jwtSigningKey(secret("x".repeat(31)), environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 bytes");
        }
        assertThat(new SecurityConfig().jwtSigningKey(secret("x".repeat(32)), new MockEnvironment()).getEncoded()).hasSize(32);
    }

    // ------------------------------------------------------------------ the public address

    @Test
    void anHttpsAddressIsAccepted() {
        for (String ok : new String[]{"https://patchnotes.example.com", "https://patchnotes.example.com:8443", "https://a.b"}) {
            assertThatCode(() -> ProductionSettingsCheck.requireSecureBaseUrl(ok)).as(ok).doesNotThrowAnyException();
        }
    }

    @Test
    void plainHttpIsAcceptedOnlyForTheLocalMachine() {
        for (String local : new String[]{"http://localhost", "http://localhost:8080", "http://127.0.0.1:9000", "http://[::1]:8080"}) {
            assertThatCode(() -> ProductionSettingsCheck.requireSecureBaseUrl(local)).as(local).doesNotThrowAnyException();
        }
    }

    @Test
    void everythingElseIsRefusedIncludingTricksThatLookLocal() {
        for (String bad : new String[]{
                "http://patchnotes.example.com",         // plain http on a real host
                "http://localhost.evil.com",             // starts with "localhost"
                "http://localhost@evil.com",             // "localhost" is only the user name
                "http://evil.com/#localhost",            // "localhost" is only a fragment
                "https://",                              // no host
                "ftp://patchnotes.example.com",
                "patchnotes.example.com",                // no scheme
                "not a url"}) {
            assertThatThrownBy(() -> ProductionSettingsCheck.requireSecureBaseUrl(bad))
                    .as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("PUBLIC_BASE_URL");
        }
    }

    // ------------------------------------------------------------------ the whole application, started with prod

    /** Starts the application the way a deployment would, with the production profile, and returns its context. */
    private static ConfigurableApplicationContext start(String... arguments) {
        // command-line style arguments have the highest precedence, so they override the test profile's own values
        String[] fixed = {"--server.port=0", "--spring.main.banner-mode=off"};
        String[] all = java.util.stream.Stream.concat(java.util.Arrays.stream(fixed), java.util.Arrays.stream(arguments)).toArray(String[]::new);
        return new SpringApplicationBuilder(PatchNotesApplication.class).profiles("test", "prod").run(all);
    }

    @Test
    void theApplicationStartsWithTheProductionProfileAndAppliesItsSettings() {
        try (ConfigurableApplicationContext context = start("--app.security.steam.public-base-url=https://patchnotes.example.test")) {
            Environment env = context.getEnvironment();

            assertThat(env.getProperty("server.shutdown")).isEqualTo("graceful");
            assertThat(env.getProperty("server.error.include-stacktrace")).isEqualTo("never");
            assertThat(env.getProperty("server.error.include-message")).isEqualTo("never");
            assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("native");
            assertThat(env.getProperty("spring.task.scheduling.shutdown.await-termination")).isEqualTo("true");
            assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
            assertThat(context.getBean(SteamLoginProperties.class).secureCookies()).isTrue();
            assertThat(context.getBeansOfType(ProductionSettingsCheck.class)).hasSize(1);
        }
    }

    @Test
    void startingWithTheProductionProfileAndNoSigningKeyFailsWithAClearMessage() {
        assertThatThrownBy(() -> start("--app.security.jwt.secret=", "--app.security.steam.public-base-url=https://patchnotes.example.test"))
                .hasStackTraceContaining("JWT_SECRET");
    }

    @Test
    void startingWithTheProductionProfileAndAnInsecureAddressFailsWithAClearMessage() {
        assertThatThrownBy(() -> start("--app.security.steam.public-base-url=http://patchnotes.example.test"))
                .hasStackTraceContaining("PUBLIC_BASE_URL must be an https:// address");
    }

    @Test
    void theOrdinaryProfileDoesNotHaveTheProductionCheck() {
        // the default and test profiles must keep working with the local http address and no setup
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PatchNotesApplication.class)
                .profiles("test").run("--server.port=0", "--spring.main.banner-mode=off")) {
            assertThat(context.getBeansOfType(ProductionSettingsCheck.class)).isEmpty();
            assertThat(context.getEnvironment().getProperty("server.shutdown")).isNull();
        }
    }
}
