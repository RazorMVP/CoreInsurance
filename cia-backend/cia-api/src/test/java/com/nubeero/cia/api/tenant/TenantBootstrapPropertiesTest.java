package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBootstrapPropertiesTest {

    @Test
    void bindsEnabledAndTenantList() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("cia.tenants.bootstrap.enabled", "true");
        env.setProperty("cia.tenants.bootstrap.tenants[0].schema", "tenant_acme");
        env.setProperty("cia.tenants.bootstrap.tenants[0].realm", "tenant_acme");
        env.setProperty("cia.tenants.bootstrap.tenants[0].display-name", "Acme");
        env.setProperty("cia.tenants.bootstrap.tenants[0].subdomain", "acme");
        env.setProperty("cia.tenants.bootstrap.tenants[0].admin-username", "admin");
        env.setProperty("cia.tenants.bootstrap.tenants[0].admin-email", "admin@acme.example");
        env.setProperty("cia.tenants.bootstrap.tenants[0].admin-temp-password", "Temp!123");

        TenantBootstrapProperties props = new Binder(
            ConfigurationPropertySources.get(env))
            .bind("cia.tenants.bootstrap", TenantBootstrapProperties.class)
            .get();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getTenants()).hasSize(1);
        assertThat(props.getTenants().get(0).getSchema()).isEqualTo("tenant_acme");
        assertThat(props.getTenants().get(0).getAdminTempPassword()).isEqualTo("Temp!123");
    }
}
