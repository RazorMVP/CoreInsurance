package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TenantProvisioningServiceIT extends TenantProvisioningItSupport {

    @BeforeEach
    void ensureRegistry() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS public.tenants (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              schema_name VARCHAR(63) NOT NULL UNIQUE,
              name VARCHAR(255) NOT NULL,
              subdomain VARCHAR(63) NOT NULL UNIQUE,
              active BOOLEAN NOT NULL DEFAULT TRUE,
              created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""");
        jdbc.update("DELETE FROM public.tenants WHERE schema_name = 'tenant_orch'");
    }

    @Test
    void provisionRunsAllStepsAndThreadsAdminGroupIdToKeycloak() {
        var migrator = new TenantSchemaMigrator(dataSource());
        var seeder = new TenantSeeder(dataSource());
        var registry = new TenantRegistry(dataSource());
        var keycloak = mock(KeycloakTenantProvisioner.class);
        AtomicReference<UUID> keycloakGroupId = new AtomicReference<>();
        doAnswer(inv -> {
            FirstAdminSpec spec = inv.getArgument(1);
            keycloakGroupId.set(spec.accessGroupId());
            return null;
        }).when(keycloak).provisionTenantAuth(anyString(), any(FirstAdminSpec.class));

        var service = new TenantProvisioningService(migrator, seeder, registry, keycloak);
        var spec = new TenantBootstrapProperties.TenantSpec();
        spec.setSchema("tenant_orch"); spec.setRealm("tenant_orch");
        spec.setDisplayName("Orch"); spec.setSubdomain("orch");
        spec.setAdminUsername("admin"); spec.setAdminEmail("admin@orch.example");
        spec.setAdminTempPassword("Temp!123");

        service.provision(spec);

        // Schema migrated + seeded — use schema-qualified reads to avoid search_path flakiness.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        UUID seededGroupId = jdbc.queryForObject(
            "SELECT id FROM tenant_orch.access_groups WHERE name = 'Administrators'", UUID.class);

        // Registry row written.
        Integer regRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM public.tenants WHERE schema_name = 'tenant_orch'", Integer.class);
        assertThat(regRows).isEqualTo(1);

        // The SAME admin-group UUID flowed to Keycloak.
        verify(keycloak).provisionTenantAuth(eq("tenant_orch"), any(FirstAdminSpec.class));
        assertThat(keycloakGroupId.get()).isEqualTo(seededGroupId);
    }
}
