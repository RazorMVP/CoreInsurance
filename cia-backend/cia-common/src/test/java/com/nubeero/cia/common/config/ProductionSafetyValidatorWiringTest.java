package com.nubeero.cia.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring test — proves {@link ProductionSafetyValidator} is actually
 * DISCOVERED and RUN by Spring Boot via the
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}
 * registration, not merely that its logic is correct when called directly
 * ({@link ProductionSafetyValidatorTest} covers the logic).
 *
 * <p>This complements the unit test specifically because a direct method call
 * cannot catch a registration/discovery failure (wrong filename, missing
 * imports entry, etc.). Here we boot a real (non-web, no-DB) {@link
 * org.springframework.boot.SpringApplication} so Boot's own EPP-loading
 * machinery runs against the real imports file on the classpath.
 */
class ProductionSafetyValidatorWiringTest {

    @Configuration
    static class EmptyApp {}

    private SpringApplicationBuilder app() {
        return new SpringApplicationBuilder(EmptyApp.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF);
    }

    @Test
    @DisplayName("EPP is discovered: boot fails when hardened marker + dev default secret are present")
    void eppDiscoveredAndFiresOnHardenedWithDevDefaults() {
        assertThatThrownBy(() ->
            app().properties(
                    "cia.deployment.environment=production",
                    "cia.security.pii-key=dev-pii-key-do-not-use-in-prod-CHANGE-ME")
                .run().close())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Refusing to start")
            .hasMessageContaining("PII_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("EPP is discovered but no-op: boot succeeds with hardened marker + real secrets")
    void eppDiscoveredButPassesWhenClean() {
        var ctx = app().properties(
                "cia.deployment.environment=production",
                "cia.security.pii-key=a-real-32-byte-base64-key-AAAAAAAAAAAA",
                "cia.partner.webhook.signing-secret=a-real-secret",
                "cia.storage.access-key=AKIAREAL",
                "cia.storage.secret-key=real-storage-secret",
                "spring.datasource.password=real-db-password")
            .profiles("prod")
            .run();
        assertThat(ctx.isRunning()).isTrue();
        ctx.close();
    }

    @Test
    @DisplayName("EPP is no-op when marker unset (default local) even with dev defaults — does not break normal boot")
    void eppNoopWhenLocal() {
        var ctx = app().properties(
                "cia.security.pii-key=dev-pii-key-do-not-use-in-prod-CHANGE-ME")
            .run();
        assertThat(ctx.isRunning()).isTrue();
        ctx.close();
    }
}
