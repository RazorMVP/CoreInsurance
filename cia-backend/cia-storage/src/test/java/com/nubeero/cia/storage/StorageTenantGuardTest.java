package com.nubeero.cia.storage;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageTenantGuardTest {

    @Test
    void rejectsMissingTenant() {
        StorageTenantGuard guard = new StorageTenantGuard(new MockEnvironment());

        assertThatThrownBy(() -> guard.requireAllowedTenant(" "))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Tenant context is required");
    }

    @Test
    void rejectsPublicTenantOutsideDevTest() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        StorageTenantGuard guard = new StorageTenantGuard(environment);

        assertThatThrownBy(() -> guard.requireAllowedTenant("public"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("public storage tenant is not allowed");
    }

    @Test
    void allowsPublicTenantInDevOnly() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        StorageTenantGuard guard = new StorageTenantGuard(environment);

        assertThat(guard.requireAllowedTenant("public")).isEqualTo("public");
    }

    @Test
    void allowsRealTenantOutsideDevTest() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        StorageTenantGuard guard = new StorageTenantGuard(environment);

        assertThat(guard.requireAllowedTenant("tenant_alpha")).isEqualTo("tenant_alpha");
    }
}
