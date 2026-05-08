package com.nubeero.cia.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalDependenciesHealthIndicatorTest {

    @Test
    void allowsRelaxedDevConfigurationOutsideProductionLikeEnvironments() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cia.environment", "local")
                .withProperty("cia.naicom.mode", "stub")
                .withProperty("cia.niid.mode", "stub")
                .withProperty("cia.kyc.provider", "mock");
        environment.setActiveProfiles("dev");

        assertThat(new ExternalDependenciesHealthIndicator(environment).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenProductionDependenciesAreIncomplete() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cia.environment", "prod")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://auth.example.com/realms/cia")
                .withProperty("cia.storage.endpoint", "https://storage.example.com")
                .withProperty("cia.kyc.provider", "dojah")
                .withProperty("cia.naicom.mode", "live")
                .withProperty("cia.naicom.api-url", "https://naicom.example.com")
                .withProperty("cia.niid.mode", "stub");
        environment.setActiveProfiles("prod");

        assertThat(new ExternalDependenciesHealthIndicator(environment).health().getStatus())
                .isEqualTo(Status.DOWN);
    }

    @Test
    void reportsUpWhenProductionDependenciesAreConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cia.environment", "prod")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://auth.example.com/realms/cia")
                .withProperty("cia.storage.endpoint", "https://storage.example.com")
                .withProperty("cia.kyc.provider", "dojah")
                .withProperty("cia.kyc.provider-url", "https://kyc.example.com")
                .withProperty("cia.naicom.mode", "live")
                .withProperty("cia.naicom.api-url", "https://naicom.example.com")
                .withProperty("cia.niid.mode", "live")
                .withProperty("cia.niid.api-url", "https://niid.example.com");
        environment.setActiveProfiles("prod");

        assertThat(new ExternalDependenciesHealthIndicator(environment).health().getStatus())
                .isEqualTo(Status.UP);
    }
}
