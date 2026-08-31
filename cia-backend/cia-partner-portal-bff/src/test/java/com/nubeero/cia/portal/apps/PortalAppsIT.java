package com.nubeero.cia.portal.apps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.portal.auth.PortalSessionFilter;
import com.nubeero.cia.portal.developer.PartnerDeveloperService;
import com.nubeero.cia.portal.grant.GrantRole;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.grant.PartnerPortalGrantRepository;
import com.nubeero.cia.portal.session.PortalSession;
import com.nubeero.cia.portal.session.PortalSessionStore;
import jakarta.servlet.http.Cookie;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT for Task 7: {@link GrantAuthorizationService} + {@code GET /portal/apps}.
 *
 * <p>Uses the real {@code hibernate.multiTenancy=SCHEMA} strategy (mirrors {@code
 * PortalAuthFlowIT}) so {@link #getPortalApps_returnsAppsAcrossTenants_provingThePerAppTenantSwitch()}
 * genuinely exercises {@link PortalAppsService}'s cross-tenant per-app read: two grants for the
 * same developer point at two Partner Apps living in two DIFFERENT tenant schemas
 * ({@code tenant_acme}, {@code tenant_leadway}); a broken/missing tenant-context switch (or a
 * broken restore back to {@code "public"} between the two reads) would resolve the wrong schema's
 * {@code partner_apps} table for the second app and either return the wrong data or nothing.
 *
 * <p>This module has no Flyway of its own (see {@code PortalAuthFlowIT}'s javadoc) — every table
 * this IT needs ({@code public.tenants} V1, {@code public.partner_portal_grant} V80, and a
 * byte-identical-to-V12 {@code partner_apps} inside each tenant schema) is created directly via
 * plain JDBC in {@link #createSchema()}, before the Spring context starts.
 *
 * <p>Sessions are seeded directly via {@link PortalSessionStore#create} rather than the full OAuth
 * login round trip {@code PortalAuthFlowIT} exercises — this IT's concern is the authorization +
 * enrichment logic downstream of an authenticated session, not the login flow itself.
 *
 * <p>{@link StubKeycloakMetadataConfig} replaces the real Keycloak-backed {@link
 * KeycloakPartnerAppMetadataResolver} with a canned stub — the injectable seam the task brief calls
 * for so this IT needs no live Keycloak, while the DB-sourced fields (rate tier, status, tenant
 * label) stay real and genuinely exercise the per-app tenant switch.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PortalAppsTestApplication.class,
        // See PortalAuthFlowIT's identical property — cia-partner-api's bucket4j autoconfiguration
        // activates for any @EnableAutoConfiguration app regardless of component-scan scope.
        properties = "bucket4j.enabled=false"
)
@AutoConfigureMockMvc
class PortalAppsIT {

    private static final String DEV_EMAIL = "dev@insurtech.example";
    private static final String NO_GRANTS_EMAIL = "no-apps-dev@insurtech.example";
    private static final String VIEWER_EMAIL = "viewer-dev@insurtech.example";

    private static final UUID APP_A_ID = UUID.randomUUID(); // lives in tenant_acme
    private static final UUID APP_B_ID = UUID.randomUUID(); // lives in tenant_leadway
    private static final UUID UNGRANTED_APP_ID = UUID.randomUUID();

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciaportalappstest")
                    .withUsername("ciaportalappstest")
                    .withPassword("ciaportalappstest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Deliberately real SCHEMA multi-tenancy — see class javadoc.
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "SCHEMA");
    }

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = conn.createStatement()) {
                st.execute(readResource("/schema/public_tenants.sql"));
                st.execute(readResource("/schema/partner_portal_grant.sql"));

                st.execute("CREATE SCHEMA IF NOT EXISTS tenant_acme");
                st.execute("SET search_path TO tenant_acme");
                st.execute(readResource("/schema/partner_apps.sql"));
                st.execute("RESET search_path");

                st.execute("CREATE SCHEMA IF NOT EXISTS tenant_leadway");
                st.execute("SET search_path TO tenant_leadway");
                st.execute(readResource("/schema/partner_apps.sql"));
                st.execute("RESET search_path");
            }

            insertTenant(conn, "tenant_acme", "Acme Insurance", "acme");
            insertTenant(conn, "tenant_leadway", "Leadway Assurance", "leadway");

            insertPartnerApp(conn, "tenant_acme", APP_A_ID, "insurtech-app-acme", "Acme Insurtech",
                    "products:read quotes:create", "GROWTH", 300, true);
            insertPartnerApp(conn, "tenant_leadway", APP_B_ID, "insurtech-app-leadway", "Leadway Insurtech",
                    "claims:read", "STARTER", 60, true);
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = PortalAppsIT.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void insertTenant(Connection conn, String schema, String name, String subdomain)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO public.tenants (id, schema_name, name, subdomain, active) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, TRUE)")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            ps.setString(3, subdomain);
            ps.executeUpdate();
        }
    }

    private static void insertPartnerApp(Connection conn, String schema, UUID id, String clientId,
                                          String appName, String scopes, String plan, int rateLimitRpm,
                                          boolean active) throws Exception {
        try (Statement schemaSt = conn.createStatement()) {
            schemaSt.execute("SET search_path TO " + schema);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO partner_apps (id, client_id, app_name, contact_email, scopes, plan, "
                        + "rate_limit_rpm, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, clientId);
            ps.setString(3, appName);
            ps.setString(4, "dev@" + schema + ".test");
            ps.setString(5, scopes);
            ps.setString(6, plan);
            ps.setInt(7, rateLimitRpm);
            ps.setBoolean(8, active);
            ps.executeUpdate();
        }
        try (Statement resetSt = conn.createStatement()) {
            resetSt.execute("RESET search_path");
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PartnerPortalGrantRepository grantRepository;
    @Autowired PortalSessionStore sessionStore;
    @Autowired GrantAuthorizationService grantAuthorizationService;
    @Autowired PortalAppsService appsService;

    /** Replaces the real Keycloak-backed resolver — see class javadoc. */
    @TestConfiguration
    static class StubKeycloakMetadataConfig {

        @Bean
        @Primary
        PartnerAppKeycloakMetadataResolver stubKeycloakMetadataResolver() {
            return (tenantRealm, clientId) -> new PartnerAppKeycloakMetadata(
                    clientId, List.of("stub:" + clientId + ":read"));
        }
    }

    private Cookie sessionCookieFor(UUID partnerUserId, String email) {
        PortalSession session = new PortalSession(
                UUID.randomUUID().toString(),
                partnerUserId,
                email,
                "Dev Example",
                "access-token-not-used",
                "refresh-token-not-used",
                Instant.now().plus(Duration.ofHours(8)),
                Instant.now().plus(Duration.ofMinutes(15)),
                "csrf-token-not-used");
        String sessionId = sessionStore.create(session);
        return new Cookie(PortalSessionFilter.SESSION_COOKIE_NAME, sessionId);
    }

    private static PartnerPortalGrant grant(UUID partnerUserId, String email, String tenantSchema,
                                             UUID partnerAppId, GrantRole role) {
        PartnerPortalGrant g = new PartnerPortalGrant();
        g.setPartnerUserId(partnerUserId);
        g.setPartnerUserEmail(email);
        g.setTenantSchema(tenantSchema);
        g.setPartnerAppId(partnerAppId);
        g.setRole(role);
        g.setCreatedBy("system-admin");
        return g;
    }

    @Test
    void getPortalApps_returnsAppsAcrossTenants_provingThePerAppTenantSwitch() throws Exception {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(DEV_EMAIL);
        grantRepository.save(grant(partnerUserId, DEV_EMAIL, "tenant_acme", APP_A_ID, GrantRole.MANAGER));
        grantRepository.save(grant(partnerUserId, DEV_EMAIL, "tenant_leadway", APP_B_ID, GrantRole.VIEWER));

        Cookie sessionCookie = sessionCookieFor(partnerUserId, DEV_EMAIL);

        MvcResult result = mvc.perform(get("/portal/apps").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).hasSize(2);

        JsonNode appA = findByPartnerAppId(data, APP_A_ID);
        assertThat(appA.path("clientId").asText()).isEqualTo("insurtech-app-acme");
        assertThat(appA.path("tenantSchema").asText()).isEqualTo("tenant_acme");
        assertThat(appA.path("tenantLabel").asText()).isEqualTo("Acme Insurance");
        assertThat(appA.path("rateTier").asText()).isEqualTo("GROWTH");
        assertThat(appA.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(appA.path("role").asText()).isEqualTo("MANAGER");
        assertThat(appA.path("scopes")).extracting(JsonNode::asText)
                .contains("stub:insurtech-app-acme:read");

        JsonNode appB = findByPartnerAppId(data, APP_B_ID);
        assertThat(appB.path("clientId").asText()).isEqualTo("insurtech-app-leadway");
        assertThat(appB.path("tenantSchema").asText()).isEqualTo("tenant_leadway");
        assertThat(appB.path("tenantLabel").asText()).isEqualTo("Leadway Assurance");
        assertThat(appB.path("rateTier").asText()).isEqualTo("STARTER");
        assertThat(appB.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(appB.path("role").asText()).isEqualTo("VIEWER");
        assertThat(appB.path("scopes")).extracting(JsonNode::asText)
                .contains("stub:insurtech-app-leadway:read");

        // The next public-schema read on this same request-handling path still works — proves
        // PortalAppsService restored TenantContext to "public" after each per-app switch (a
        // stuck tenant_leadway identifier would make this 500/misbehave under real multi-tenancy).
        mvc.perform(get("/portal/apps").cookie(sessionCookie)).andExpect(status().isOk());
    }

    @Test
    void getPortalApps_returnsEmptyArray_whenDeveloperHasNoGrants() throws Exception {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(NO_GRANTS_EMAIL);
        Cookie sessionCookie = sessionCookieFor(partnerUserId, NO_GRANTS_EMAIL);

        MvcResult result = mvc.perform(get("/portal/apps").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).isEmpty();
    }

    @Test
    void assertGrant_forUngrantedApp_throwsPortalAccessDenied403() {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(NO_GRANTS_EMAIL);

        assertThatThrownBy(() -> grantAuthorizationService.assertGrant(partnerUserId, UNGRANTED_APP_ID))
                .isInstanceOf(PortalAccessDeniedException.class)
                .hasFieldOrPropertyWithValue("httpStatus", HttpStatus.FORBIDDEN)
                .hasFieldOrPropertyWithValue("errorCode", "PORTAL_ACCESS_DENIED");
    }

    @Test
    void assertManager_forViewerGrant_throwsPortalAccessDenied403() {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(VIEWER_EMAIL);
        grantRepository.save(grant(partnerUserId, VIEWER_EMAIL, "tenant_acme", APP_A_ID, GrantRole.VIEWER));

        // A VIEWER grant satisfies assertGrant...
        assertThat(grantAuthorizationService.assertGrant(partnerUserId, APP_A_ID).getRole())
                .isEqualTo(GrantRole.VIEWER);

        // ...but not assertManager.
        assertThatThrownBy(() -> grantAuthorizationService.assertManager(partnerUserId, APP_A_ID))
                .isInstanceOf(PortalAccessDeniedException.class)
                .hasFieldOrPropertyWithValue("httpStatus", HttpStatus.FORBIDDEN);
    }

    @Test
    void listApps_restoresTenantContextToPublic_afterCrossTenantReads() {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(DEV_EMAIL + ".direct");
        grantRepository.save(grant(partnerUserId, DEV_EMAIL + ".direct", "tenant_acme", APP_A_ID, GrantRole.MANAGER));
        grantRepository.save(grant(partnerUserId, DEV_EMAIL + ".direct", "tenant_leadway", APP_B_ID, GrantRole.VIEWER));

        // Simulate exactly what PortalSessionFilter pins for every /portal/** request, then call
        // the service directly (bypassing MockMvc/the filter chain) so this test observes
        // TenantContext on its own thread, unmasked by the filter's own unconditional clear().
        TenantContext.setTenantId("public");
        try {
            List<PortalAppSummary> apps = appsService.listApps(partnerUserId);

            assertThat(apps).hasSize(2);
            assertThat(apps).extracting(PortalAppSummary::tenantSchema)
                    .containsExactlyInAnyOrder("tenant_acme", "tenant_leadway");

            // The whole point of the try/finally in PortalAppsService.readAppInTenantSchema: after
            // switching into each app's own tenant schema to read it, the context must be back on
            // "public" before this method returns — not left on whichever tenant was read last.
            assertThat(TenantContext.getTenantId()).isEqualTo("public");
        } finally {
            TenantContext.clear();
        }
    }

    private static JsonNode findByPartnerAppId(JsonNode array, UUID partnerAppId) {
        for (JsonNode node : array) {
            if (partnerAppId.toString().equals(node.path("partnerAppId").asText())) {
                return node;
            }
        }
        throw new AssertionError("No entry with partnerAppId=" + partnerAppId + " in " + array);
    }
}
