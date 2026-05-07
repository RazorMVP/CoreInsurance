package com.nubeero.cia.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIdentifierResolverTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void resolvesTenantFromContext() {
        TenantContext.setTenantId("tenant_alpha");

        String tenant = resolverWithProfiles("prod").resolveCurrentTenantIdentifier();

        assertThat(tenant).isEqualTo("tenant_alpha");
    }

    @Test
    void fallsBackToPublicInDevProfile() {
        String tenant = resolverWithProfiles("dev").resolveCurrentTenantIdentifier();

        assertThat(tenant).isEqualTo("public");
    }

    @Test
    void fallsBackToPublicInTestProfile() {
        String tenant = resolverWithProfiles("test").resolveCurrentTenantIdentifier();

        assertThat(tenant).isEqualTo("public");
    }

    @Test
    void rejectsMissingTenantOutsideDevAndTestProfiles() {
        TenantIdentifierResolver resolver = resolverWithProfiles("prod");

        assertThatThrownBy(resolver::resolveCurrentTenantIdentifier)
                .isInstanceOf(TenantResolutionException.class)
                .hasMessageContaining("Tenant context is required");
    }

    private TenantIdentifierResolver resolverWithProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new TenantIdentifierResolver(environment);
    }
}
