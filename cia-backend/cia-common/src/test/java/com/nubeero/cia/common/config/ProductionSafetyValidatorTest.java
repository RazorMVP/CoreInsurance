package com.nubeero.cia.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link ProductionSafetyValidator}. No Spring context — the
 * validator is a plain {@code EnvironmentPostProcessor} exercised against a
 * {@link MockEnvironment}, exactly like {@link PiiKeyValidatorTest}.
 */
class ProductionSafetyValidatorTest {

    private final ProductionSafetyValidator validator = new ProductionSafetyValidator();

    /** A configuration with every secret set to a non-default real value. */
    private MockEnvironment hardenedAndClean(String marker) {
        MockEnvironment env = new MockEnvironment()
            .withProperty(ProductionSafetyValidator.ENV_MARKER, marker)
            .withProperty("cia.security.pii-key", "a-real-32-byte-base64-key-AAAAAAAAAAAA")
            .withProperty("cia.partner.webhook.signing-secret", "a-real-webhook-secret")
            .withProperty("cia.storage.access-key", "AKIAREAL")
            .withProperty("cia.storage.secret-key", "real-storage-secret")
            .withProperty("spring.datasource.password", "real-db-password");
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    @DisplayName("no-op when marker is unset (default local) — even with all dev defaults present")
    void noopWhenUnset() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("cia.security.pii-key", "dev-pii-key-do-not-use-in-prod-CHANGE-ME")
            .withProperty("cia.partner.webhook.signing-secret", "dev-secret-replace-in-prod")
            .withProperty("cia.storage.access-key", "minioadmin")
            .withProperty("spring.datasource.password", "cia_dev");
        env.setActiveProfiles("dev");
        assertThatCode(() -> validator.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("no-op for explicitly non-hardened markers")
    @ValueSource(strings = {"local", "test", "ci", "LOCAL"})
    void noopForNonHardenedMarkers(String marker) {
        MockEnvironment env = new MockEnvironment()
            .withProperty(ProductionSafetyValidator.ENV_MARKER, marker)
            .withProperty("cia.security.pii-key", "dev-pii-key-do-not-use-in-prod-CHANGE-ME");
        env.setActiveProfiles("dev");
        assertThatCode(() -> validator.postProcessEnvironment(env, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @DisplayName("passes when hardened AND fully configured (production/prod/staging, case-insensitive)")
    @ValueSource(strings = {"production", "prod", "staging", "PRODUCTION", " Production "})
    void passesWhenHardenedAndClean(String marker) {
        assertThatCode(() -> validator.postProcessEnvironment(hardenedAndClean(marker), null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects active dev profile in a hardened environment")
    void rejectsDevProfileWhenHardened() {
        MockEnvironment env = hardenedAndClean("production");
        env.setActiveProfiles("dev");
        assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'dev' is active");
    }

    @Test
    @DisplayName("rejects the weak default PII key when hardened")
    void rejectsWeakPiiKey() {
        MockEnvironment env = hardenedAndClean("production");
        env.withProperty("cia.security.pii-key", "dev-pii-key-do-not-use-in-prod-CHANGE-ME");
        assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cia.security.pii-key")
            .hasMessageContaining("PII_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("rejects the weak default webhook signing secret when hardened")
    void rejectsWeakWebhookSecret() {
        MockEnvironment env = hardenedAndClean("production");
        env.withProperty("cia.partner.webhook.signing-secret", "dev-secret-replace-in-prod");
        assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("WEBHOOK_SIGNING_SECRET");
    }

    @Test
    @DisplayName("rejects default minioadmin storage credentials when hardened")
    void rejectsWeakStorageCreds() {
        MockEnvironment env = hardenedAndClean("production");
        env.withProperty("cia.storage.secret-key", "minioadmin");
        assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("STORAGE_SECRET_KEY");
    }

    @Test
    @DisplayName("collects ALL violations in a single exception message")
    void collectsAllViolations() {
        MockEnvironment env = new MockEnvironment()
            .withProperty(ProductionSafetyValidator.ENV_MARKER, "production")
            .withProperty("cia.security.pii-key", "dev-pii-key-do-not-use-in-prod-CHANGE-ME")
            .withProperty("cia.partner.webhook.signing-secret", "dev-secret-replace-in-prod")
            .withProperty("cia.storage.access-key", "minioadmin")
            .withProperty("cia.storage.secret-key", "minioadmin")
            .withProperty("spring.datasource.password", "cia_dev");
        env.setActiveProfiles("dev");
        assertThatThrownBy(() -> validator.postProcessEnvironment(env, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'dev' is active")
            .hasMessageContaining("PII_ENCRYPTION_KEY")
            .hasMessageContaining("WEBHOOK_SIGNING_SECRET")
            .hasMessageContaining("STORAGE_ACCESS_KEY")
            .hasMessageContaining("STORAGE_SECRET_KEY")
            .hasMessageContaining("DB_PASSWORD");
    }
}
