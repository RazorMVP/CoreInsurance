package com.nubeero.cia.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionSafetyValidatorTest {

    private final ProductionSafetyValidator validator = new ProductionSafetyValidator();

    @Test
    @DisplayName("requires an explicit Spring profile")
    void requiresExplicitProfile() {
        MockEnvironment env = new MockEnvironment();

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("allows dev profile to use local defaults")
    void allowsDevProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        assertDoesNotThrow(() -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("rejects dev profile in a production-like environment")
    void rejectsDevProfileInProductionEnvironment() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("cia.environment", "production");
        env.setActiveProfiles("dev");

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("accepts complete protected environment config")
    void acceptsCompleteProtectedEnvironmentConfig() {
        MockEnvironment env = productionEnvironment();

        assertDoesNotThrow(() -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("rejects dev PII key outside dev/test")
    void rejectsDevPiiKeyOutsideDevTest() {
        MockEnvironment env = productionEnvironment()
                .withProperty("cia.security.pii-key", "dev-pii-key-do-not-use-in-prod-CHANGE-ME");

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("rejects local JWT issuer outside dev/test")
    void rejectsLocalJwtIssuerOutsideDevTest() {
        MockEnvironment env = productionEnvironment()
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "http://localhost:8280/realms/cia");

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("rejects default database password outside dev/test")
    void rejectsDefaultDatabasePasswordOutsideDevTest() {
        MockEnvironment env = productionEnvironment()
                .withProperty("spring.datasource.password", "cia_dev");

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("rejects mock KYC provider outside dev/test")
    void rejectsMockKycProviderOutsideDevTest() {
        MockEnvironment env = productionEnvironment()
                .withProperty("cia.kyc.provider", "mock");

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @ParameterizedTest
    @DisplayName("rejects stub regulatory modes outside dev/test")
    @CsvSource({
            "cia.naicom.mode,stub",
            "cia.niid.mode,stub"
    })
    void rejectsStubRegulatoryModesOutsideDevTest(String propertyName, String value) {
        MockEnvironment env = productionEnvironment()
                .withProperty(propertyName, value);

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @ParameterizedTest
    @DisplayName("requires production integration URLs")
    @CsvSource({
            "cia.kyc.provider-url",
            "cia.naicom.api-url",
            "cia.niid.api-url"
    })
    void requiresProductionIntegrationUrls(String propertyName) {
        MockEnvironment env = productionEnvironment()
                .withProperty(propertyName, "");

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @ParameterizedTest
    @DisplayName("requires production core properties")
    @CsvSource({
            "spring.datasource.username",
            "cia.storage.bucket-name"
    })
    void requiresProductionCoreProperties(String propertyName) {
        MockEnvironment env = productionEnvironment()
                .withProperty(propertyName, "");

        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    private MockEnvironment productionEnvironment() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("cia.environment", "production")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/cia")
                .withProperty("spring.datasource.username", "cia")
                .withProperty("spring.datasource.password", "prod-database-password")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "https://auth.example.com/realms/cia")
                .withProperty("cia.storage.type", "s3")
                .withProperty("cia.storage.endpoint", "https://s3.example.com")
                .withProperty("cia.storage.bucket-name", "cia-prod-documents")
                .withProperty("cia.storage.access-key", "prod-storage-access-key")
                .withProperty("cia.storage.secret-key", "prod-storage-secret-key")
                .withProperty("cia.partner.webhook.signing-secret",
                        "prod-webhook-signing-secret-32chars")
                .withProperty("cia.security.pii-key", "prod-pii-key-32-random-bytes-value")
                .withProperty("cia.kyc.provider", "dojah")
                .withProperty("cia.kyc.provider-url", "https://kyc.example.com")
                .withProperty("cia.naicom.mode", "live")
                .withProperty("cia.naicom.api-url", "https://naicom.example.com")
                .withProperty("cia.niid.mode", "live")
                .withProperty("cia.niid.api-url", "https://niid.example.com");
        env.setActiveProfiles("prod");
        return env;
    }
}
