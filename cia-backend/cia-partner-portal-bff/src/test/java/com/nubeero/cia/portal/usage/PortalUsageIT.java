package com.nubeero.cia.portal.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore;
import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.StatusClass;
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
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT for Task 9 Step 3: {@link PortalUsageService} + {@code GET /portal/apps/{id}/usage} — proves
 * the endpoint composes REAL data from three sources into one response: the live {@link
 * PartnerUsageRollupStore} ("today"), the durable {@code partner_request_daily} table
 * ("history"), and {@code webhook_registrations}/{@code webhook_delivery_logs} ("webhookDeliveries") —
 * grant-gated, with the computed {@code errorRate}.
 *
 * <p>Real {@code hibernate.multiTenancy=SCHEMA} (mirrors {@code PortalAppsIT}/{@code
 * PortalProxyIT}) so {@link PortalUsageService}'s per-app tenant-schema read is genuinely
 * exercised. This module has no Flyway of its own — every table this IT needs is created directly
 * via plain JDBC in {@link #createSchema()}, mirroring the established pattern in this module's
 * other ITs (see {@code webhook_registrations.sql} / {@code webhook_delivery_logs.sql} / {@code
 * partner_request_daily.sql} for the byte-identical-to-the-real-migration rationale).
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PortalUsageTestApplication.class,
        // See PortalAppsIT's identical property — cia-partner-api's bucket4j autoconfiguration
        // activates for any @EnableAutoConfiguration app regardless of component-scan scope.
        properties = "bucket4j.enabled=false"
)
@AutoConfigureMockMvc
class PortalUsageIT {

    private static final String CLIENT_ID = "insurtech-app-acme";
    private static final UUID APP_ID = UUID.randomUUID();
    private static final UUID UNGRANTED_APP_ID = UUID.randomUUID();

    private static final String DEV_EMAIL = "dev@insurtech.example";
    private static final String NO_GRANT_EMAIL = "no-grant-dev@insurtech.example";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciaportalusagetest")
                    .withUsername("ciaportalusagetest")
                    .withPassword("ciaportalusagetest");

    private static UUID REGISTRATION_HEALTHY;
    private static UUID REGISTRATION_INACTIVE;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Deliberately real SCHEMA multi-tenancy — see class javadoc.
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "SCHEMA");
    }

    @org.junit.jupiter.api.BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = conn.createStatement()) {
                st.execute(readResource("/schema/public_tenants.sql"));
                st.execute(readResource("/schema/partner_portal_grant.sql"));

                st.execute("CREATE SCHEMA IF NOT EXISTS tenant_acme");
                st.execute("SET search_path TO tenant_acme");
                st.execute(readResource("/schema/partner_apps.sql"));
                st.execute(readResource("/schema/webhook_registrations.sql"));
                st.execute(readResource("/schema/webhook_delivery_logs.sql"));
                st.execute(readResource("/schema/partner_request_daily.sql"));
                st.execute("RESET search_path");
            }

            insertTenant(conn, "tenant_acme", "Acme Insurance", "acme");
            insertPartnerApp(conn, APP_ID, CLIENT_ID, "Acme Insurtech");

            REGISTRATION_HEALTHY = insertWebhookRegistration(conn, APP_ID, "https://insurtech.example/hooks-a", true);
            REGISTRATION_INACTIVE = insertWebhookRegistration(conn, APP_ID, "https://insurtech.example/hooks-b", false);

            // 3 successes + 1 failure on the healthy registration, 1 more failure on the inactive one.
            insertDeliveryLog(conn, REGISTRATION_HEALTHY, "policy.bound", true, Instant.now().minusSeconds(300));
            insertDeliveryLog(conn, REGISTRATION_HEALTHY, "policy.bound", true, Instant.now().minusSeconds(200));
            insertDeliveryLog(conn, REGISTRATION_HEALTHY, "claim.registered", true, Instant.now().minusSeconds(100));
            insertDeliveryLog(conn, REGISTRATION_HEALTHY, "claim.approved", false, Instant.now().minusSeconds(50));
            insertDeliveryLog(conn, REGISTRATION_INACTIVE, "quote.created", false, Instant.now().minusSeconds(10));

            // 3 days of durable history, most recent 2 days ago (so "today" — seeded via the live
            // store below — is deliberately absent from partner_request_daily, proving the two
            // sources are genuinely composed rather than one masking the other).
            insertRequestDaily(conn, APP_ID, LocalDate.now().minusDays(2), 40, 35, 4, 1);
            insertRequestDaily(conn, APP_ID, LocalDate.now().minusDays(3), 20, 20, 0, 0);
            insertRequestDaily(conn, APP_ID, LocalDate.now().minusDays(4), 10, 8, 2, 0);
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = PortalUsageIT.class.getResourceAsStream(path)) {
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

    private static void insertPartnerApp(Connection conn, UUID id, String clientId, String appName)
            throws Exception {
        try (Statement schemaSt = conn.createStatement()) {
            schemaSt.execute("SET search_path TO tenant_acme");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO partner_apps (id, client_id, app_name, contact_email, scopes, plan, "
                        + "rate_limit_rpm, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, clientId);
            ps.setString(3, appName);
            ps.setString(4, "dev@tenant_acme.test");
            ps.setString(5, "products:read webhooks:manage");
            ps.setString(6, "GROWTH");
            ps.setInt(7, 300);
            ps.setBoolean(8, true);
            ps.executeUpdate();
        }
        try (Statement resetSt = conn.createStatement()) {
            resetSt.execute("RESET search_path");
        }
    }

    private static UUID insertWebhookRegistration(Connection conn, UUID appId, String targetUrl, boolean active)
            throws Exception {
        UUID id = UUID.randomUUID();
        try (Statement schemaSt = conn.createStatement()) {
            schemaSt.execute("SET search_path TO tenant_acme");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO webhook_registrations (id, partner_app_id, target_url, secret, event_types, active) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, appId);
            ps.setString(3, targetUrl);
            ps.setString(4, "signing-secret");
            ps.setString(5, "policy.bound,claim.registered,claim.approved,quote.created");
            ps.setBoolean(6, active);
            ps.executeUpdate();
        }
        try (Statement resetSt = conn.createStatement()) {
            resetSt.execute("RESET search_path");
        }
        return id;
    }

    private static void insertDeliveryLog(Connection conn, UUID registrationId, String eventType,
                                           boolean success, Instant deliveredAt) throws Exception {
        try (Statement schemaSt = conn.createStatement()) {
            schemaSt.execute("SET search_path TO tenant_acme");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO webhook_delivery_logs (id, webhook_registration_id, event_type, payload_json, "
                        + "success, http_status, delivered_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, registrationId);
            ps.setString(3, eventType);
            ps.setString(4, "{}");
            ps.setBoolean(5, success);
            ps.setInt(6, success ? 200 : 500);
            ps.setObject(7, java.sql.Timestamp.from(deliveredAt));
            ps.executeUpdate();
        }
        try (Statement resetSt = conn.createStatement()) {
            resetSt.execute("RESET search_path");
        }
    }

    private static void insertRequestDaily(Connection conn, UUID appId, LocalDate date,
                                            long total, long success, long clientError, long serverError)
            throws Exception {
        try (Statement schemaSt = conn.createStatement()) {
            schemaSt.execute("SET search_path TO tenant_acme");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO partner_request_daily (id, partner_app_id, usage_date, total, success, "
                        + "client_error, server_error) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, appId);
            ps.setObject(2, java.sql.Date.valueOf(date));
            ps.setLong(3, total);
            ps.setLong(4, success);
            ps.setLong(5, clientError);
            ps.setLong(6, serverError);
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
    @Autowired PartnerUsageRollupStore rollupStore;

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

    private static PartnerPortalGrant grant(UUID partnerUserId, String email, UUID partnerAppId, GrantRole role) {
        PartnerPortalGrant g = new PartnerPortalGrant();
        g.setPartnerUserId(partnerUserId);
        g.setPartnerUserEmail(email);
        g.setTenantSchema("tenant_acme");
        g.setPartnerAppId(partnerAppId);
        g.setRole(role);
        g.setCreatedBy("system-admin");
        return g;
    }

    @Test
    void usage_composesLiveToday_durableHistory_andWebhookDeliveries_withComputedErrorRate() throws Exception {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(DEV_EMAIL);
        grantRepository.save(grant(partnerUserId, DEV_EMAIL, APP_ID, GrantRole.VIEWER));
        Cookie sessionCookie = sessionCookieFor(partnerUserId, DEV_EMAIL);

        // "Today" — seeded directly into the live store (the filter's own job, not this IT's
        // concern — Task 9 Step 1 already proves the filter writes here correctly).
        LocalDate today = PartnerUsageRollupStore.today();
        rollupStore.increment("tenant_acme", CLIENT_ID, today, StatusClass.SUCCESS);
        rollupStore.increment("tenant_acme", CLIENT_ID, today, StatusClass.SUCCESS);
        rollupStore.increment("tenant_acme", CLIENT_ID, today, StatusClass.SUCCESS);
        rollupStore.increment("tenant_acme", CLIENT_ID, today, StatusClass.CLIENT_ERROR);
        rollupStore.increment("tenant_acme", CLIENT_ID, today, StatusClass.SERVER_ERROR);

        MvcResult result = mvc.perform(get("/portal/apps/{appId}/usage", APP_ID).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");

        // today: 5 total, 3 success, 1 client_error, 1 server_error.
        JsonNode todayNode = data.path("today");
        assertThat(todayNode.path("total").asLong()).isEqualTo(5);
        assertThat(todayNode.path("success").asLong()).isEqualTo(3);
        assertThat(todayNode.path("clientError").asLong()).isEqualTo(1);
        assertThat(todayNode.path("serverError").asLong()).isEqualTo(1);

        // errorRate = (1 + 1) / 5 = 0.4, computed off "today".
        assertThat(data.path("errorRate").asDouble()).isEqualTo(0.4, org.assertj.core.data.Offset.offset(1e-9));

        // history: 3 durably-flushed rows, most-recent-first — "today" is NOT among them.
        JsonNode history = data.path("history");
        assertThat(history.isArray()).isTrue();
        assertThat(history).hasSize(3);
        assertThat(history.get(0).path("date").asText()).isEqualTo(LocalDate.now().minusDays(2).toString());
        assertThat(history.get(0).path("total").asLong()).isEqualTo(40);
        assertThat(history.get(0).path("success").asLong()).isEqualTo(35);
        assertThat(history.get(0).path("clientError").asLong()).isEqualTo(4);
        assertThat(history.get(0).path("serverError").asLong()).isEqualTo(1);
        assertThat(history.get(1).path("date").asText()).isEqualTo(LocalDate.now().minusDays(3).toString());
        assertThat(history.get(2).path("date").asText()).isEqualTo(LocalDate.now().minusDays(4).toString());
        for (JsonNode row : history) {
            assertThat(row.path("date").asText()).isNotEqualTo(today.toString());
        }

        // webhookDeliveries: 2 registrations (1 active), 5 total deliveries, 3 success, 2 failed.
        JsonNode webhooks = data.path("webhookDeliveries");
        assertThat(webhooks.path("registrations").asInt()).isEqualTo(2);
        assertThat(webhooks.path("activeRegistrations").asInt()).isEqualTo(1);
        assertThat(webhooks.path("totalDeliveries").asLong()).isEqualTo(5);
        assertThat(webhooks.path("successfulDeliveries").asLong()).isEqualTo(3);
        assertThat(webhooks.path("failedDeliveries").asLong()).isEqualTo(2);
        assertThat(webhooks.path("lastDeliveryAt").isNull()).isFalse();
    }

    @Test
    void usage_zeroTraffic_errorRateIsZero_notDivideByZero() throws Exception {
        UUID appIdNoTraffic = UUID.randomUUID();
        String clientIdNoTraffic = "insurtech-app-no-traffic";
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            insertPartnerApp(conn, appIdNoTraffic, clientIdNoTraffic, "No Traffic Insurtech");
        }

        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(DEV_EMAIL + ".no-traffic");
        grantRepository.save(grant(partnerUserId, DEV_EMAIL, appIdNoTraffic, GrantRole.VIEWER));
        Cookie sessionCookie = sessionCookieFor(partnerUserId, DEV_EMAIL + ".no-traffic");

        MvcResult result = mvc.perform(get("/portal/apps/{appId}/usage", appIdNoTraffic).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");

        assertThat(data.path("today").path("total").asLong()).isEqualTo(0);
        assertThat(data.path("errorRate").asDouble()).isEqualTo(0.0);
        assertThat(data.path("history").isArray()).isTrue();
        assertThat(data.path("history")).isEmpty();
        assertThat(data.path("webhookDeliveries").path("registrations").asInt()).isEqualTo(0);
        assertThat(data.path("webhookDeliveries").path("totalDeliveries").asLong()).isEqualTo(0);
        assertThat(data.path("webhookDeliveries").path("lastDeliveryAt").isNull()).isTrue();
    }

    @Test
    void usage_withoutAnyGrant_403_neverLeaksData() throws Exception {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(NO_GRANT_EMAIL);
        Cookie sessionCookie = sessionCookieFor(partnerUserId, NO_GRANT_EMAIL);

        mvc.perform(get("/portal/apps/{appId}/usage", UNGRANTED_APP_ID).cookie(sessionCookie))
                .andExpect(status().isForbidden());
    }
}
