package com.nubeero.cia.portal.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.portal.auth.PortalSessionFilter;
import com.nubeero.cia.portal.developer.PartnerDeveloperService;
import com.nubeero.cia.portal.grant.GrantRole;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.grant.PartnerPortalGrantRepository;
import com.nubeero.cia.portal.session.PortalSession;
import com.nubeero.cia.portal.session.PortalSessionStore;
import com.nubeero.cia.portal.token.ClientCredentialsTokenGrantor;
import com.nubeero.cia.portal.token.MintedToken;
import com.nubeero.cia.portal.token.PartnerAppSecretRotator;
import com.nubeero.cia.portal.token.PartnerClientSecretResolver;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT for Task 8: {@link PortalProxyController} — the management + "try it" proxy that forwards a
 * developer's {@code /portal/apps/{id}/**} request to the real {@code /partner/v1/**} API,
 * attaching a server-side-minted Bearer token.
 *
 * <h2>Why a stub upstream, not the app's own real {@code /partner/v1/**}</h2>
 * {@link StubPartnerApiUpstream} is a REAL HTTP server (JDK {@code com.sun.net.httpserver}) — every
 * assertion here proves {@link PartnerApiProxyClient} makes a genuine network call and relays
 * whatever comes back verbatim, which is the task's pinned PROXY FIDELITY requirement. Pointing it
 * at a stub instead of this JVM's own {@code /partner/v1/**} (which lives in the downstream
 * {@code cia-api} module this module cannot depend on — dependency direction runs the other way)
 * isolates the assertion to the proxy's own behavior without needing a live Keycloak issuing a
 * signed JWT that {@code TenantIssuerJwtAuthenticationManagerResolver} would validate against a
 * real realm's JWKS — that plumbing belongs to (and is already covered by) {@code cia-partner-api}'s
 * own test suite ({@code PartnerScopeFilterTest} et al.), not this task.
 *
 * <p>Real {@code hibernate.multiTenancy=SCHEMA} (mirrors {@code PortalAppsIT}) so the per-app
 * tenant-schema read Task 7 built ({@code TenantScopedPartnerAppReader}, promoted from {@code
 * PortalAppsService} in this task) is genuinely exercised, not bypassed.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PortalProxyTestApplication.class,
        // See PortalAppsIT's identical property — cia-partner-api's bucket4j autoconfiguration
        // activates for any @EnableAutoConfiguration app regardless of component-scan scope.
        properties = "bucket4j.enabled=false"
)
@AutoConfigureMockMvc
class PortalProxyIT {

    private static final String CLIENT_ID = "insurtech-app-acme";
    private static final UUID APP_ID = UUID.randomUUID();
    private static final UUID UNGRANTED_APP_ID = UUID.randomUUID();

    private static final String MANAGER_EMAIL = "manager-dev@insurtech.example";
    private static final String VIEWER_EMAIL = "viewer-dev@insurtech.example";
    private static final String NO_GRANT_EMAIL = "no-grant-dev@insurtech.example";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciaportalproxytest")
                    .withUsername("ciaportalproxytest")
                    .withPassword("ciaportalproxytest");

    /** Real loopback HTTP server standing in for the app's own {@code /partner/v1/**} — see class javadoc. */
    static final StubPartnerApiUpstream UPSTREAM = startUpstream();

    private static StubPartnerApiUpstream startUpstream() {
        try {
            StubPartnerApiUpstream upstream = new StubPartnerApiUpstream();
            upstream.start();
            return upstream;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start the stub /partner/v1 upstream", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Deliberately real SCHEMA multi-tenancy — see class javadoc.
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "SCHEMA");
        // Point PartnerApiProxyClient at the stub instead of this JVM's own (nonexistent, in this
        // module) /partner/v1/** — see class javadoc.
        registry.add("cia.partner-portal.api-base-url", UPSTREAM::baseUrl);
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
            }

            insertTenant(conn, "tenant_acme", "Acme Insurance", "acme");
            insertPartnerApp(conn, "tenant_acme", APP_ID, CLIENT_ID, "Acme Insurtech",
                    "products:read webhooks:manage", "GROWTH", 300, true);
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = PortalProxyIT.class.getResourceAsStream(path)) {
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

    /**
     * Replaces the real Keycloak-backed token seams — see {@link PortalProxyTestApplication}'s
     * javadoc for why the concrete Keycloak classes are excluded from component-scan entirely
     * rather than merely overridden. {@link StatefulSecretStub} backs BOTH {@link
     * PartnerClientSecretResolver} and {@link PartnerAppSecretRotator} off the SAME in-memory
     * secret-per-clientId map — exactly like the real Keycloak client-secret store, rotating
     * through {@link PartnerAppSecretRotator#rotateSecret} genuinely changes what {@link
     * PartnerClientSecretResolver#resolveSecret} returns on the NEXT call, which is what lets
     * {@link #credentialsRotate_managerOnly_returnsNewSecret_andForcesTokenReMint_viewerGets403()}
     * prove the post-rotate token-cache eviction actually forces a re-mint under the new secret.
     */
    @TestConfiguration
    static class StubTokenSeamsConfig {

        @Bean
        @Primary
        StatefulSecretStub statefulSecretStub() {
            return new StatefulSecretStub();
        }

        @Bean
        @Primary
        ClientCredentialsTokenGrantor stubTokenGrantor() {
            // Deliberately bakes the secret INTO the token text so tests can tell, purely by
            // comparing token strings, whether a mint used the pre- or post-rotate secret.
            return (tenantRealm, clientId, clientSecret) -> new MintedToken(
                    "minted-token-for-" + clientId + "-secret-" + clientSecret,
                    Instant.now().plus(Duration.ofHours(1)));
        }
    }

    static class StatefulSecretStub implements PartnerClientSecretResolver, PartnerAppSecretRotator {
        private final Map<String, String> secrets = new ConcurrentHashMap<>();
        private final AtomicInteger rotations = new AtomicInteger();

        @Override
        public String resolveSecret(String tenantRealm, String clientId) {
            return secrets.computeIfAbsent(clientId, id -> "initial-secret-for-" + id);
        }

        @Override
        public String rotateSecret(String tenantRealm, String clientId) {
            String newSecret = "rotated-secret-" + clientId + "-" + rotations.incrementAndGet();
            secrets.put(clientId, newSecret);
            return newSecret;
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private Cookie sessionCookieFor(UUID partnerUserId, String email, String csrfToken) {
        PortalSession session = new PortalSession(
                UUID.randomUUID().toString(),
                partnerUserId,
                email,
                "Dev Example",
                "access-token-not-used",
                "refresh-token-not-used",
                Instant.now().plus(Duration.ofHours(8)),
                Instant.now().plus(Duration.ofMinutes(15)),
                csrfToken);
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

    // ── try-it ──────────────────────────────────────────────────────────────────────────

    @Test
    void tryIt_get_proxiesRealHttpCall_relays200AndBody_bearerAttachedServerSide() throws Exception {
        UPSTREAM.stub("GET", "/products", 200, "application/json",
                "[{\"id\":\"prod-1\",\"name\":\"Motor Comprehensive\"}]");
        UPSTREAM.clearRecordedRequests();

        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(MANAGER_EMAIL);
        grantRepository.save(grant(partnerUserId, MANAGER_EMAIL, APP_ID, GrantRole.MANAGER));
        Cookie sessionCookie = sessionCookieFor(partnerUserId, MANAGER_EMAIL, "csrf-tryit-get");

        MvcResult result = mvc.perform(get("/portal/apps/{appId}/try/products", APP_ID).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();

        // The /portal/** request itself carried ONLY the session cookie — never an Authorization
        // header. The BFF, not the browser, is what attaches the Bearer token.
        assertThat(result.getRequest().getHeader("Authorization")).isNull();
        assertThat(result.getResponse().getContentAsString())
                .isEqualTo("[{\"id\":\"prod-1\",\"name\":\"Motor Comprehensive\"}]");

        List<StubPartnerApiUpstream.RecordedRequest> requests = UPSTREAM.recordedRequests();
        assertThat(requests).hasSize(1);
        StubPartnerApiUpstream.RecordedRequest upstreamRequest = requests.get(0);
        assertThat(upstreamRequest.method()).isEqualTo("GET");
        assertThat(upstreamRequest.path()).isEqualTo("/partner/v1/products");
        // ...while the UPSTREAM genuinely received a server-side-minted Bearer token.
        assertThat(upstreamRequest.authorizationHeader())
                .isEqualTo("Bearer minted-token-for-" + CLIENT_ID + "-secret-initial-secret-for-" + CLIENT_ID);
    }

    @Test
    void tryIt_scopeDenied_relaysUpstream403Verbatim() throws Exception {
        String forbiddenBody = "{\"errors\":[{\"code\":\"INSUFFICIENT_SCOPE\","
                + "\"message\":\"Missing required scope: claims:read\"}]}";
        UPSTREAM.stub("GET", "/claims/CLM-999", 403, "application/json", forbiddenBody);

        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(MANAGER_EMAIL + ".scope-denied");
        grantRepository.save(grant(partnerUserId, MANAGER_EMAIL, APP_ID, GrantRole.VIEWER));
        Cookie sessionCookie = sessionCookieFor(partnerUserId, MANAGER_EMAIL, "csrf-scope-denied");

        mvc.perform(get("/portal/apps/{appId}/try/claims/CLM-999", APP_ID).cookie(sessionCookie))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(forbiddenBody));
    }

    @Test
    void tryIt_withoutAnyGrant_403_neverReachesUpstream() throws Exception {
        UPSTREAM.stub("GET", "/products", 200, "application/json", "[]");
        UPSTREAM.clearRecordedRequests();

        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(NO_GRANT_EMAIL);
        Cookie sessionCookie = sessionCookieFor(partnerUserId, NO_GRANT_EMAIL, "csrf-no-grant");

        mvc.perform(get("/portal/apps/{appId}/try/products", UNGRANTED_APP_ID).cookie(sessionCookie))
                .andExpect(status().isForbidden());

        // The authorization gate runs BEFORE any proxy call — the upstream never saw a request.
        assertThat(UPSTREAM.recordedRequests()).isEmpty();
    }

    @Test
    void tryIt_post_gatedByAssertManager_viewerGets403_managerSucceeds() throws Exception {
        UPSTREAM.stub("POST", "/quotes", 201, "application/json", "{\"data\":{\"id\":\"quote-1\"}}");
        UPSTREAM.clearRecordedRequests();

        UUID viewerId = PartnerDeveloperService.derivePartnerUserId(VIEWER_EMAIL + ".tryit-post");
        grantRepository.save(grant(viewerId, VIEWER_EMAIL, APP_ID, GrantRole.VIEWER));
        Cookie viewerCookie = sessionCookieFor(viewerId, VIEWER_EMAIL, "csrf-viewer-tryit-post");

        mvc.perform(post("/portal/apps/{appId}/try/quotes", APP_ID)
                        .cookie(viewerCookie)
                        .header(PortalSessionFilter.CSRF_HEADER_NAME, "csrf-viewer-tryit-post")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
        assertThat(UPSTREAM.recordedRequests()).isEmpty();

        UUID managerId = PartnerDeveloperService.derivePartnerUserId(MANAGER_EMAIL + ".tryit-post");
        grantRepository.save(grant(managerId, MANAGER_EMAIL, APP_ID, GrantRole.MANAGER));
        Cookie managerCookie = sessionCookieFor(managerId, MANAGER_EMAIL, "csrf-manager-tryit-post");

        mvc.perform(post("/portal/apps/{appId}/try/quotes", APP_ID)
                        .cookie(managerCookie)
                        .header(PortalSessionFilter.CSRF_HEADER_NAME, "csrf-manager-tryit-post")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isCreated());
        assertThat(UPSTREAM.recordedRequests()).hasSize(1);
        assertThat(UPSTREAM.recordedRequests().get(0).path()).isEqualTo("/partner/v1/quotes");
    }

    @Test
    void tryIt_upstreamSetCookie_isNeverRelayedToThePortalResponse() throws Exception {
        UPSTREAM.stub("GET", "/products", 200, "application/json", "[]",
                Map.of("Set-Cookie", "JSESSIONID=upstream-should-never-leak; Path=/"));

        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(MANAGER_EMAIL + ".set-cookie");
        grantRepository.save(grant(partnerUserId, MANAGER_EMAIL, APP_ID, GrantRole.MANAGER));
        Cookie sessionCookie = sessionCookieFor(partnerUserId, MANAGER_EMAIL, "csrf-set-cookie");

        MvcResult result = mvc.perform(get("/portal/apps/{appId}/try/products", APP_ID).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();

        // The upstream genuinely sent a Set-Cookie — the assertion is that PortalProxyController
        // strips it before it reaches the browser, not that the upstream never sends one.
        assertThat(result.getResponse().getHeaderNames())
                .as("no Set-Cookie/Set-Cookie2 header should be relayed from the upstream response")
                .noneMatch(name -> name.equalsIgnoreCase("Set-Cookie") || name.equalsIgnoreCase("Set-Cookie2"));
    }

    // ── Webhooks CRUD ───────────────────────────────────────────────────────────────────

    @Test
    void webhooks_createListDelete_roundTripThroughProxy_asManager() throws Exception {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(MANAGER_EMAIL + ".webhooks");
        grantRepository.save(grant(partnerUserId, MANAGER_EMAIL, APP_ID, GrantRole.MANAGER));
        Cookie sessionCookie = sessionCookieFor(partnerUserId, MANAGER_EMAIL, "csrf-webhooks");

        // Create.
        MvcResult createResult = mvc.perform(post("/portal/apps/{appId}/webhooks", APP_ID)
                        .cookie(sessionCookie)
                        .header(PortalSessionFilter.CSRF_HEADER_NAME, "csrf-webhooks")
                        .contentType("application/json")
                        .content("{\"url\":\"https://insurtech.example/hooks\",\"events\":[\"policy.bound\"]}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String webhookId = created.path("data").path("id").asText();
        assertThat(webhookId).isNotBlank();

        // List — the created webhook is present.
        MvcResult listResult = mvc.perform(get("/portal/apps/{appId}/webhooks", APP_ID).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString()).path("data");
        assertThat(list.isArray()).isTrue();
        assertThat(containsId(list, webhookId)).isTrue();

        // Delete.
        mvc.perform(delete("/portal/apps/{appId}/webhooks/{whId}", APP_ID, webhookId)
                        .cookie(sessionCookie)
                        .header(PortalSessionFilter.CSRF_HEADER_NAME, "csrf-webhooks"))
                .andExpect(status().isNoContent());

        // List again — gone.
        MvcResult listAfterDelete = mvc.perform(get("/portal/apps/{appId}/webhooks", APP_ID).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode listAfter = objectMapper.readTree(listAfterDelete.getResponse().getContentAsString()).path("data");
        assertThat(containsId(listAfter, webhookId)).isFalse();
    }

    @Test
    void webhooks_create_asViewer_403_neverReachesUpstream() throws Exception {
        UPSTREAM.clearRecordedRequests();

        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(VIEWER_EMAIL + ".webhooks-create");
        grantRepository.save(grant(partnerUserId, VIEWER_EMAIL, APP_ID, GrantRole.VIEWER));
        Cookie sessionCookie = sessionCookieFor(partnerUserId, VIEWER_EMAIL, "csrf-viewer-webhooks");

        mvc.perform(post("/portal/apps/{appId}/webhooks", APP_ID)
                        .cookie(sessionCookie)
                        .header(PortalSessionFilter.CSRF_HEADER_NAME, "csrf-viewer-webhooks")
                        .contentType("application/json")
                        .content("{\"url\":\"https://insurtech.example/hooks\"}"))
                .andExpect(status().isForbidden());

        assertThat(UPSTREAM.recordedRequests()).isEmpty();
    }

    private static boolean containsId(JsonNode array, String id) {
        for (JsonNode node : array) {
            if (id.equals(node.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    // ── Credentials ─────────────────────────────────────────────────────────────────────

    @Test
    void credentials_get_returnsClientIdAndScopes_neverTheSecret() throws Exception {
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(MANAGER_EMAIL + ".credentials-get");
        grantRepository.save(grant(partnerUserId, MANAGER_EMAIL, APP_ID, GrantRole.VIEWER));
        Cookie sessionCookie = sessionCookieFor(partnerUserId, MANAGER_EMAIL, "csrf-credentials-get");

        MvcResult result = mvc.perform(get("/portal/apps/{appId}/credentials", APP_ID).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(data.path("clientId").asText()).isEqualTo(CLIENT_ID);
        assertThat(data.path("scopes")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("products:read", "webhooks:manage");
        assertThat(body.toLowerCase()).doesNotContain("secret");
    }

    @Test
    void credentialsRotate_managerOnly_returnsNewSecret_andForcesTokenReMint_viewerGets403() throws Exception {
        UPSTREAM.stub("GET", "/products", 200, "application/json", "[]");

        UUID viewerId = PartnerDeveloperService.derivePartnerUserId(VIEWER_EMAIL + ".rotate");
        grantRepository.save(grant(viewerId, VIEWER_EMAIL, APP_ID, GrantRole.VIEWER));
        Cookie viewerCookie = sessionCookieFor(viewerId, VIEWER_EMAIL, "csrf-viewer-rotate");

        mvc.perform(post("/portal/apps/{appId}/credentials/rotate", APP_ID)
                        .cookie(viewerCookie)
                        .header(PortalSessionFilter.CSRF_HEADER_NAME, "csrf-viewer-rotate"))
                .andExpect(status().isForbidden());

        UUID managerId = PartnerDeveloperService.derivePartnerUserId(MANAGER_EMAIL + ".rotate");
        grantRepository.save(grant(managerId, MANAGER_EMAIL, APP_ID, GrantRole.MANAGER));
        Cookie managerCookie = sessionCookieFor(managerId, MANAGER_EMAIL, "csrf-manager-rotate");

        // Mint (and cache) a token under whatever secret is currently active.
        UPSTREAM.clearRecordedRequests();
        mvc.perform(get("/portal/apps/{appId}/try/products", APP_ID).cookie(managerCookie))
                .andExpect(status().isOk());
        String bearerBeforeRotate = UPSTREAM.recordedRequests().get(0).authorizationHeader();

        // Rotate — MANAGER only, new secret returned exactly once.
        MvcResult rotateResult = mvc.perform(post("/portal/apps/{appId}/credentials/rotate", APP_ID)
                        .cookie(managerCookie)
                        .header(PortalSessionFilter.CSRF_HEADER_NAME, "csrf-manager-rotate"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rotateData = objectMapper.readTree(rotateResult.getResponse().getContentAsString()).path("data");
        assertThat(rotateData.path("clientId").asText()).isEqualTo(CLIENT_ID);
        String newSecret = rotateData.path("clientSecret").asText();
        assertThat(newSecret).isNotBlank().startsWith("rotated-secret-" + CLIENT_ID + "-");

        // The NEXT proxy call must re-mint under the new secret — not keep serving the pre-rotate
        // cached token — proving credentials/rotate evicted the cache.
        UPSTREAM.clearRecordedRequests();
        mvc.perform(get("/portal/apps/{appId}/try/products", APP_ID).cookie(managerCookie))
                .andExpect(status().isOk());
        String bearerAfterRotate = UPSTREAM.recordedRequests().get(0).authorizationHeader();

        assertThat(bearerAfterRotate).isNotEqualTo(bearerBeforeRotate);
        assertThat(bearerAfterRotate).isEqualTo("Bearer minted-token-for-" + CLIENT_ID + "-secret-" + newSecret);
    }
}
