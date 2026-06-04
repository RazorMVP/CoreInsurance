package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates per-tenant provisioning: schema → migrate → seed → Keycloak (realm/roles/admin) →
 * registry. Generates the Administrators access-group UUID up front and threads it into both the
 * DB seed and the Keycloak admin attribute. Every step is idempotent; any failure propagates
 * (fail-fast — the caller aborts startup).
 *
 * <p>Step order is intentional:
 * <ol>
 *   <li>Ensure schema exists (DDL, idempotent).</li>
 *   <li>Migrate schema (Flyway, idempotent).</li>
 *   <li>Seed defaults (access group, currency, customer-number-format — idempotent).</li>
 *   <li>Provision Keycloak (realm, roles, first-admin user — idempotent).</li>
 *   <li>Write registry row (public.tenants upsert) — last, so the tenant only appears
 *       in the registry once fully provisioned. A registry row is the signal to the
 *       migration sweep runner that this tenant exists and must be migrated on future boots.</li>
 * </ol>
 *
 * <p><strong>Keycloak provisioner is required when {@code provision()} is called.</strong>
 * The {@code Optional<KeycloakTenantProvisioner>} field is present only for bean
 * constructability in contexts where {@code cia.keycloak.admin.enabled=false} and the
 * bootstrap runner is gated off (so {@code provision()} is never invoked). Calling
 * {@code provision()} without the Keycloak admin client enabled is a configuration error
 * and throws {@link IllegalStateException} immediately — a tenant with schema+seed but no
 * Keycloak realm/admin is silently broken (nobody can log in).
 */
@Slf4j
@Service
public class TenantProvisioningService {

    private final TenantSchemaMigrator migrator;
    private final TenantSeeder seeder;
    private final TenantRegistry registry;
    private final Optional<KeycloakTenantProvisioner> keycloak;

    public TenantProvisioningService(TenantSchemaMigrator migrator, TenantSeeder seeder,
                                     TenantRegistry registry,
                                     Optional<KeycloakTenantProvisioner> keycloak) {
        this.migrator = migrator;
        this.seeder = seeder;
        this.registry = registry;
        this.keycloak = keycloak;
    }

    /**
     * Provisions one tenant end-to-end. Idempotent: safe to call on every boot for
     * already-provisioned tenants. Throws on any step failure — the bootstrap runner
     * aborts startup so the operator can fix the problem before the app accepts traffic.
     *
     * <p>Requires {@code cia.keycloak.admin.enabled=true}. If the Keycloak provisioner is
     * absent (admin disabled) this method throws {@link IllegalStateException} immediately
     * rather than silently leaving the tenant without an auth plane.
     *
     * @param spec the tenant specification (schema, realm, display name, subdomain, admin credentials)
     * @throws IllegalStateException if the Keycloak admin client is not enabled
     */
    public void provision(TenantBootstrapProperties.TenantSpec spec) {
        String schema = spec.getSchema();
        String realm = spec.getRealm() != null ? spec.getRealm() : schema;
        UUID adminGroupId = deterministicAdminGroupId(schema);
        log.info("Provisioning tenant: schema={} realm={}", schema, realm);

        migrator.ensureSchema(schema);
        migrator.migrate(schema);
        seeder.seed(schema, adminGroupId);

        KeycloakTenantProvisioner kc = keycloak.orElseThrow(() -> new IllegalStateException(
                "Tenant bootstrap is enabled but cia.keycloak.admin.enabled is not true — "
                + "cannot provision the realm/first-admin for tenant '" + schema + "'. "
                + "Enable the Keycloak admin client to provision tenants."));
        kc.provisionTenantAuth(realm, new FirstAdminSpec(
                spec.getAdminUsername(), spec.getAdminEmail(),
                "Tenant", "Administrator",
                spec.getAdminTempPassword(), adminGroupId));

        registry.upsert(schema, spec.getDisplayName(), spec.getSubdomain());
        log.info("Tenant '{}' provisioned", schema);
    }

    /**
     * Derives a stable UUID from the schema name so that re-running provisioning always
     * targets the same Administrators access-group row in the tenant DB. Using a
     * deterministic UUID (rather than {@code UUID.randomUUID()}) means:
     * <ul>
     *   <li>Repeated calls to {@link #provision} are a no-op (the seed INSERT uses
     *       {@code ON CONFLICT (id) DO NOTHING}).</li>
     *   <li>The same UUID is threaded into both the DB seed and the Keycloak first-admin's
     *       {@code accessGroupId} attribute, so the admin user always resolves to the correct
     *       group regardless of how many times provisioning runs.</li>
     * </ul>
     */
    private static UUID deterministicAdminGroupId(String schema) {
        return UUID.nameUUIDFromBytes(("admin-group::" + schema).getBytes(StandardCharsets.UTF_8));
    }
}
