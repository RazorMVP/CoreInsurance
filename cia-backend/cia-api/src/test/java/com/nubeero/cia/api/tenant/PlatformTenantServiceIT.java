package com.nubeero.cia.api.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.platform.PlatformAuditService;
import com.nubeero.cia.api.platform.PlatformTenantService;
import com.nubeero.cia.api.platform.RegistryTenantActivationLookup;
import com.nubeero.cia.api.platform.TenantAlreadyExistsException;
import com.nubeero.cia.api.platform.TenantNotFoundException;
import com.nubeero.cia.api.platform.dto.OnboardTenantRequest;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * IT for {@link PlatformTenantService} — covers the full onboard/list/suspend/activate lifecycle
 * against a real PostgreSQL container (Testcontainers).
 *
 * <p>Extends {@link TenantProvisioningItSupport} to reuse the shared Postgres container
 * and DataSource, exactly as {@link TenantProvisioningServiceIT} does. Keycloak is mocked
 * so this IT runs without a Keycloak container — Keycloak-specific provisioning is covered
 * by the separate {@code KeycloakFirstAdminProvisioningIT} / {@code PlatformRealmProvisioningIT}.
 *
 * <p>The tenant schema name {@code "tenant_plat"} is distinct from other ITs in this class
 * ({@code "tenant_orch"}) to avoid inter-IT collisions on the shared container.
 */
class PlatformTenantServiceIT extends TenantProvisioningItSupport {

    private static final String SCHEMA = "tenant_plat";
    private static final String SUBDOMAIN = "plat";

    private PlatformTenantService service;
    private TenantRegistry registry;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource());

        // Ensure public.tenants exists (idempotent — mirrors TenantProvisioningServiceIT).
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

        // Ensure public.platform_audit_log exists (mirrors V67 DDL / PlatformAuditServiceIT).
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.platform_audit_log ("
            + " id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),"
            + " action         VARCHAR(32)  NOT NULL,"
            + " target_schema  VARCHAR(63)  NOT NULL,"
            + " actor_username VARCHAR(255) NOT NULL,"
            + " actor_realm    VARCHAR(63)  NOT NULL,"
            + " detail         JSONB,"
            + " source_ip      VARCHAR(64),"
            + " at             TIMESTAMPTZ  NOT NULL DEFAULT now()"
            + ")");

        // Clean up any rows left by a previous run.
        jdbc.update("DELETE FROM public.tenants WHERE schema_name IN (?, ?, ?)",
                SCHEMA, "tenant_acme", "tenant_beta");
        jdbc.update("DELETE FROM public.platform_audit_log WHERE target_schema IN (?, ?, ?)",
                SCHEMA, "tenant_acme", "tenant_beta");

        registry = new TenantRegistry(dataSource());
        var migrator = new TenantSchemaMigrator(dataSource());
        var seeder = new TenantSeeder(dataSource());

        // Mock Keycloak — Keycloak provisioning is covered by the dedicated Keycloak ITs.
        var keycloak = mock(KeycloakTenantProvisioner.class);

        var provisioning = new TenantProvisioningService(migrator, seeder, registry,
                Optional.of(keycloak));
        var activationLookup = new RegistryTenantActivationLookup(jdbc, 60L);
        var auditService = new PlatformAuditService(jdbc);

        service = new PlatformTenantService(provisioning, registry, activationLookup,
                auditService, new ObjectMapper());
    }

    @Test
    @DisplayName("onboard — provisions schema, writes registry row, returns credentials")
    void onboard_provisions_and_returnsCredentials() {
        var resp = service.onboard(
                new OnboardTenantRequest(SCHEMA, null, "Plat Corp", SUBDOMAIN, "admin", "a@plat.test"),
                "superadmin", "platform", "10.0.0.1");

        assertThat(resp.firstAdmin().temporaryPassword())
                .as("temp password must be non-blank and satisfy the 'Aa1!' prefix convention")
                .isNotBlank()
                .startsWith("Aa1!");

        assertThat(registry.exists(SCHEMA))
                .as("registry row must exist after provision")
                .isTrue();

        assertThat(resp.tenant().schema()).isEqualTo(SCHEMA);
        assertThat(resp.tenant().subdomain()).isEqualTo(SUBDOMAIN);
        assertThat(resp.tenant().active()).isTrue();
    }

    @Test
    @DisplayName("onboard — duplicate schema throws TenantAlreadyExistsException (HTTP 409)")
    void onboard_duplicate_throws() {
        // First onboard succeeds.
        service.onboard(
                new OnboardTenantRequest(SCHEMA, null, "Plat Corp", SUBDOMAIN, "admin", "a@plat.test"),
                "superadmin", "platform", "10.0.0.1");

        // Second onboard with same schema must throw 409.
        assertThatThrownBy(() -> service.onboard(
                new OnboardTenantRequest(SCHEMA, null, "Plat Corp Again", SUBDOMAIN, "admin", "a@plat.test"),
                "superadmin", "platform", "10.0.0.1"))
                .isInstanceOf(TenantAlreadyExistsException.class);
    }

    @Test
    @DisplayName("onboard — duplicate subdomain (different schema) throws TenantAlreadyExistsException (HTTP 409)")
    void onboard_duplicateSubdomain_throws() {
        // First onboard succeeds (schema tenant_acme, subdomain acme).
        service.onboard(
                new OnboardTenantRequest("tenant_acme", null, "Acme Corp", "acme", "admin", "a@acme.test"),
                "superadmin", "platform", "10.0.0.1");

        // Second onboard with a DIFFERENT schema but the SAME subdomain must throw 409 —
        // exercises the registry.subdomainTaken(...) branch (the duplicate-schema test
        // short-circuits on registry.exists(...) before subdomainTaken is reached).
        assertThatThrownBy(() -> service.onboard(
                new OnboardTenantRequest("tenant_beta", null, "Beta Corp", "acme", "admin", "a@beta.test"),
                "superadmin", "platform", "10.0.0.1"))
                .isInstanceOf(TenantAlreadyExistsException.class);
    }

    @Test
    @DisplayName("onboard — a realm that differs from schema is rejected (REALM_SCHEMA_MISMATCH)")
    void onboard_divergentRealm_throws() {
        // The realm-per-tenant routing model requires realm == schema; a divergent realm would
        // silently produce a tenant whose JWTs route to a nonexistent schema. Reject it before
        // touching the registry or Keycloak.
        assertThatThrownBy(() -> service.onboard(
                new OnboardTenantRequest(SCHEMA, "different_realm", "Plat Corp", SUBDOMAIN, "admin", "a@plat.test"),
                "superadmin", "platform", "10.0.0.1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REALM_SCHEMA_MISMATCH");

        // Nothing was provisioned — the guard fires before registry/Keycloak.
        assertThat(registry.exists(SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("suspend/activate — flips active flag correctly")
    void suspendAndActivate_flipActiveFlag() {
        service.onboard(
                new OnboardTenantRequest(SCHEMA, null, "Plat Corp", SUBDOMAIN, "admin", "a@plat.test"),
                "superadmin", "platform", "10.0.0.1");

        service.suspend(SCHEMA, "superadmin", "platform", "10.0.0.1");
        assertThat(registry.find(SCHEMA).orElseThrow().active())
                .as("tenant must be inactive after suspend")
                .isFalse();

        service.activate(SCHEMA, "superadmin", "platform", "10.0.0.1");
        assertThat(registry.find(SCHEMA).orElseThrow().active())
                .as("tenant must be active after activate")
                .isTrue();
    }

    @Test
    @DisplayName("suspend on missing schema — throws TenantNotFoundException (HTTP 404)")
    void suspend_missing_throws() {
        assertThatThrownBy(() ->
                service.suspend("no_such_schema", "superadmin", "platform", "10.0.0.1"))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    @DisplayName("onboard — writes exactly one ONBOARD row to platform_audit_log")
    void onboard_writesAuditRow() {
        service.onboard(
                new OnboardTenantRequest(SCHEMA, null, "Plat Corp", SUBDOMAIN, "admin", "a@plat.test"),
                "superadmin", "platform", "10.0.0.1");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.platform_audit_log"
                        + " WHERE target_schema = ? AND action = 'ONBOARD'",
                Integer.class, SCHEMA);
        assertThat(count)
                .as("exactly one ONBOARD audit row must exist")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("list — includes the newly provisioned tenant")
    void list_returnsProvisionedTenant() {
        service.onboard(
                new OnboardTenantRequest(SCHEMA, null, "Plat Corp", SUBDOMAIN, "admin", "a@plat.test"),
                "superadmin", "platform", "10.0.0.1");

        var all = service.list();
        assertThat(all).extracting("schema").contains(SCHEMA);
    }

    @Test
    @DisplayName("registry — paged findAll + counts reflect active/suspended split")
    void registry_pagedAndCounts() {
        registry.upsert("tenant_acme", "Acme", "acme");
        registry.upsert("tenant_beta", "Beta", "beta");
        registry.setActive("tenant_beta", false);

        long total = registry.countAll();
        long active = registry.countActive();
        assertThat(total).isGreaterThanOrEqualTo(2);
        assertThat(active).isLessThan(total); // beta is suspended

        var firstPage = registry.findAll(0, 1);
        assertThat(firstPage).hasSize(1);
        var secondPage = registry.findAll(1, 1);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage.get(0).schema()).isNotEqualTo(secondPage.get(0).schema());
    }
}
