package com.nubeero.cia.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.platform.dto.OnboardTenantRequest;
import com.nubeero.cia.api.keycloak.KeycloakItSupport;
import com.nubeero.cia.api.tenant.TenantProvisioningService;
import com.nubeero.cia.api.tenant.TenantRegistry;
import com.nubeero.cia.api.tenant.TenantSchemaMigrator;
import com.nubeero.cia.api.tenant.TenantSeeder;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.auth.TenantContextFilter;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.ws.rs.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * End-to-end IT for the SP1 platform-admin onboarding plane — exercises the real stack:
 * {@link PlatformTenantService} over a real {@link TenantProvisioningService} (schema +
 * Flyway + seed), a <em>real</em> {@link KeycloakTenantProvisioner} against a Testcontainers
 * Keycloak (tenant realm + first-admin actually created), the real {@code public.tenants}
 * registry, the real {@link RegistryTenantActivationLookup}, and the real
 * {@link PlatformAuditService}.
 *
 * <p>This is the load-bearing security IT. Following the plan's sanctioned split, it covers:
 * <ul>
 *   <li><b>Real provisioning (1,2,6):</b> {@code onboard} creates the tenant schema (migrated),
 *       the tenant Keycloak realm (with the bootstrap roles), the {@code public.tenants} row,
 *       returns the one-time temp password, and writes the audit trail; a duplicate is 409.</li>
 *   <li><b>The allowlist gate (3,4):</b> driven by the <em>real</em> registry state — after
 *       {@code suspend} a tenant-realm request is rejected 401 {@code TENANT_INACTIVE} by
 *       {@link TenantContextFilter}; after {@code activate} it passes; the platform realm is
 *       exempt even while a tenant is suspended.</li>
 *   <li><b>No cross-tenant escalation (5):</b> realm isolation — a token minted in a tenant
 *       realm (even one bearing a tenant {@code PLATFORM_ADMIN} role) resolves to that tenant's
 *       schema, never the platform {@code "public"} scope. The controller-level
 *       {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} + platform-realm assertion 403s are
 *       proven separately by {@code PlatformTenantControllerIT} (Task 8), so they are not
 *       re-minted here against a second live realm.</li>
 * </ul>
 *
 * <p>Extends {@link KeycloakItSupport} for the shared Testcontainers Keycloak (and its
 * {@code adminClient()} / {@code newProvisioner(...)} helpers); the Postgres container is
 * inlined here because {@code KeycloakItSupport} supplies Keycloak only. No Spring context —
 * the units are wired by hand (mirrors {@code PlatformRealmProvisioningIT} and
 * {@code PlatformTenantServiceIT}), so the gate/scoping is exercised against the real
 * {@code TenantContextFilter} with crafted JWTs rather than a heavyweight live-token mint.
 */
class PlatformOnboardingE2EIT extends KeycloakItSupport {

    /** Tenant schema == realm name (realm defaults to schema in {@code onboard}). */
    private static final String SCHEMA = "tenant_e2e";
    private static final String SUBDOMAIN = "e2e";
    private static final String PLATFORM_REALM = "platform";

    // ── Postgres (inline — KeycloakItSupport provides Keycloak only) ────────────
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciae2e")
                    .withUsername("ciae2e")
                    .withPassword("ciae2e");

    static final HikariDataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        DATA_SOURCE = new HikariDataSource(cfg);
    }

    private static Keycloak ADMIN;

    @BeforeAll
    static void connect() {
        // adminClient() runs disableMasterRealmSsl() + pollUntilAdminReady() internally.
        ADMIN = adminClient();
    }

    private JdbcTemplate jdbc;
    private TenantRegistry registry;
    private RegistryTenantActivationLookup activationLookup;
    private PlatformAuditService auditService;
    private PlatformTenantService service;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(DATA_SOURCE);

        // public.tenants + public.platform_audit_log (idempotent; mirrors V1 / V67 DDL).
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

        // Start each test from a clean slate (robust to a prior crashed run).
        cleanTenant();

        registry = new TenantRegistry(DATA_SOURCE);
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(DATA_SOURCE);
        TenantSeeder seeder = new TenantSeeder(DATA_SOURCE);
        // REAL Keycloak provisioner against the Testcontainers Keycloak (vs the mock in
        // PlatformTenantServiceIT) — this is what makes the onboard path genuinely end-to-end.
        KeycloakTenantProvisioner realKeycloak = newProvisioner(ADMIN);

        TenantProvisioningService provisioning =
                new TenantProvisioningService(migrator, seeder, registry, Optional.of(realKeycloak));
        activationLookup = new RegistryTenantActivationLookup(jdbc, 60L);
        auditService = new PlatformAuditService(jdbc);
        service = new PlatformTenantService(provisioning, registry, activationLookup,
                auditService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        cleanTenant();
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void cleanTenant() {
        jdbc.update("DELETE FROM public.tenants WHERE schema_name = ?", SCHEMA);
        jdbc.update("DELETE FROM public.platform_audit_log WHERE target_schema = ?", SCHEMA);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        try {
            ADMIN.realm(SCHEMA).remove();
        } catch (NotFoundException ignored) {
            // realm was never created by this test — nothing to clean
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private OnboardTenantRequest onboardRequest() {
        return new OnboardTenantRequest(SCHEMA, null, "E2E Corp", SUBDOMAIN, "admin", "a@e2e.test");
    }

    /** Crafted token whose {@code iss} carries the given realm (no decoder — placed straight
     *  into the SecurityContext, exactly as TenantContextFilterGateTest does). */
    private Jwt jwtForRealm(String realm) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .claim("iss", "https://kc.test/realms/" + realm).build();
    }

    private void authenticateAs(String realm) {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtForRealm(realm)));
    }

    private TenantContextFilter gateFilter(boolean gateOn) {
        PlatformRealmProperties props = new PlatformRealmProperties();
        props.setRealm(PLATFORM_REALM);
        props.getTenantAllowlist().setEnabled(gateOn);
        return new TenantContextFilter(props, activationLookup);
    }

    // ── (1)(2)(6) Real provisioning ─────────────────────────────────────────────

    @Test
    @DisplayName("onboard — creates migrated schema + Keycloak realm + registry row + audit + temp password")
    void onboard_provisionsEntireStack() throws Exception {
        var resp = service.onboard(onboardRequest(), "superadmin", PLATFORM_REALM, "10.0.0.1");

        // One-time temp password surfaced to the caller.
        assertThat(resp.firstAdmin().temporaryPassword()).isNotBlank().startsWith("Aa1!");
        assertThat(resp.tenant().schema()).isEqualTo(SCHEMA);
        assertThat(resp.tenant().active()).isTrue();

        // Registry row written last (signals "fully provisioned").
        assertThat(registry.exists(SCHEMA)).isTrue();

        // Schema was migrated, not merely created — the tenant schema holds many business tables.
        Integer tableCount = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = ?",
                Integer.class, SCHEMA);
        assertThat(tableCount).as("tenant schema must be Flyway-migrated").isGreaterThan(10);

        // Real Keycloak realm created, carrying the bootstrap roles (proves provisionTenantAuth ran).
        assertThat(ADMIN.realm(SCHEMA).toRepresentation().getRealm()).isEqualTo(SCHEMA);
        var roleNames = ADMIN.realm(SCHEMA).roles().list().stream()
                .map(RoleRepresentation::getName).toList();
        assertThat(roleNames).containsAll(BootstrapRoles.PATTERN_B);

        // Audit trail recorded the onboard.
        assertThat(auditService.recent(50))
                .filteredOn(e -> SCHEMA.equals(e.targetSchema()))
                .extracting(PlatformAuditService.PlatformAuditEntry::action)
                .containsExactly("ONBOARD");
    }

    @Test
    @DisplayName("onboard — duplicate schema is rejected 409 before any re-provisioning")
    void onboard_duplicate_throws409() {
        service.onboard(onboardRequest(), "superadmin", PLATFORM_REALM, "10.0.0.1");

        assertThatThrownBy(() ->
                service.onboard(onboardRequest(), "superadmin", PLATFORM_REALM, "10.0.0.1"))
                .isInstanceOf(TenantAlreadyExistsException.class);
    }

    // ── (3)(4) Allowlist gate, driven by real registry state ────────────────────

    @Test
    @DisplayName("suspend then activate — the gate rejects a tenant-realm request, then accepts it")
    void suspendThenActivate_gateRejectsThenAccepts() throws Exception {
        service.onboard(onboardRequest(), "superadmin", PLATFORM_REALM, "10.0.0.1");
        TenantContextFilter filter = gateFilter(true);

        // Active tenant → lookup reports active.
        assertThat(activationLookup.isActive(SCHEMA)).isTrue();

        // Suspend → service evicts the cache; lookup now reports inactive; gate returns 401.
        service.suspend(SCHEMA, "superadmin", PLATFORM_REALM, "10.0.0.1");
        assertThat(activationLookup.isActive(SCHEMA)).isFalse();

        authenticateAs(SCHEMA);
        MockHttpServletResponse suspendedRes = new MockHttpServletResponse();
        boolean[] ranWhileSuspended = {false};
        filter.doFilter(new MockHttpServletRequest(), suspendedRes,
                (rq, rs) -> ranWhileSuspended[0] = true);
        assertThat(suspendedRes.getStatus()).isEqualTo(401);
        assertThat(suspendedRes.getContentAsString()).contains("TENANT_INACTIVE");
        assertThat(ranWhileSuspended[0]).as("suspended tenant request must not reach the chain").isFalse();

        // Activate → cache evicted; lookup reports active again; gate passes through.
        service.activate(SCHEMA, "superadmin", PLATFORM_REALM, "10.0.0.1");
        assertThat(activationLookup.isActive(SCHEMA)).isTrue();

        authenticateAs(SCHEMA);
        MockHttpServletResponse activeRes = new MockHttpServletResponse();
        boolean[] ranWhileActive = {false};
        filter.doFilter(new MockHttpServletRequest(), activeRes,
                (rq, rs) -> ranWhileActive[0] = true);
        assertThat(ranWhileActive[0]).as("reactivated tenant request must reach the chain").isTrue();
        assertThat(activeRes.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("platform realm is exempt from the gate even while a tenant is suspended")
    void platformRealmExemptFromGate_whileTenantSuspended() throws Exception {
        service.onboard(onboardRequest(), "superadmin", PLATFORM_REALM, "10.0.0.1");
        service.suspend(SCHEMA, "superadmin", PLATFORM_REALM, "10.0.0.1");

        TenantContextFilter filter = gateFilter(true);
        authenticateAs(PLATFORM_REALM);
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] ran = {false};
        filter.doFilter(new MockHttpServletRequest(), res, (rq, rs) -> ran[0] = true);

        assertThat(ran[0]).as("platform realm must bypass the tenant allowlist gate").isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    // ── (6) Audit trail over the real recent() read ──────────────────────────────

    @Test
    @DisplayName("audit trail records ONBOARD, SUSPEND and ACTIVATE for the tenant")
    void auditTrail_recordsLifecycleActions() {
        service.onboard(onboardRequest(), "superadmin", PLATFORM_REALM, "10.0.0.1");
        service.suspend(SCHEMA, "superadmin", PLATFORM_REALM, "10.0.0.1");
        service.activate(SCHEMA, "superadmin", PLATFORM_REALM, "10.0.0.1");

        assertThat(auditService.recent(100))
                .filteredOn(e -> SCHEMA.equals(e.targetSchema()))
                .extracting(PlatformAuditService.PlatformAuditEntry::action)
                .contains("ONBOARD", "SUSPEND", "ACTIVATE");
    }

    // ── (5) No cross-tenant escalation: realm isolation at the scoping layer ──────

    @Test
    @DisplayName("realm isolation — a tenant-realm token resolves to the tenant schema, never platform scope")
    void tenantRealmTokenNeverResolvesToPlatformScope() throws Exception {
        // Gate OFF to isolate the scoping decision from the allowlist. A tenant PLATFORM_ADMIN
        // token carries a tenant-realm iss, so the filter scopes it to the tenant schema — it can
        // never reach the platform "public" scope. That, plus the controller's SUPER_ADMIN +
        // platform-realm assertion (PlatformTenantControllerIT), is why a tenant PLATFORM_ADMIN
        // cannot operate /api/v1/platform/**.
        TenantContextFilter filter = gateFilter(false);

        authenticateAs(SCHEMA);
        String[] tenantResolved = {null};
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (rq, rs) -> tenantResolved[0] = TenantContext.getTenantId());
        assertThat(tenantResolved[0]).isEqualTo(SCHEMA);

        authenticateAs(PLATFORM_REALM);
        String[] platformResolved = {null};
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (rq, rs) -> platformResolved[0] = TenantContext.getTenantId());
        assertThat(platformResolved[0]).isEqualTo("public");
    }
}
