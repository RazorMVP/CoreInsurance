package com.nubeero.cia.portal.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.portal.developer.PartnerDeveloperService;
import com.nubeero.cia.portal.grant.GrantRole;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.grant.PartnerPortalGrantRepository;
import jakarta.servlet.http.Cookie;
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

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end IT for the token-handler auth flow: {@code login → callback → me → logout}.
 *
 * <p>Uses the real {@code hibernate.multiTenancy=SCHEMA} strategy (NOT overridden to
 * {@code NONE}, unlike most controller-slice ITs in this codebase — see e.g. {@code
 * FinanceWebItSupport}) precisely so {@link #fullFlow_login_callback_me_logout()}'s {@code /me}
 * step exercises the real {@code MultiTenantConnectionProvider} connection-borrow path: {@link
 * PortalSessionFilter} pins {@link com.nubeero.cia.common.tenant.TenantContext} to
 * {@code "public"} for the request, and the {@code /me} read against
 * {@link PartnerPortalGrantRepository} only succeeds because that scoping is in place (see
 * {@link PortalSessionFilter}'s javadoc for why).
 *
 * <p>Since this module has no Flyway of its own, the {@code public.partner_portal_grant} table is
 * created by loading the byte-identical V80 copy already used by {@code
 * PartnerPortalGrantRepositoryIT}, via plain JDBC, before the Spring context starts.
 *
 * <p>{@link StubOAuthConfig} replaces the real {@link PortalOAuthClient} (which would otherwise
 * dial a live Keycloak) with a canned test double — the injectable seam {@link
 * PortalOAuthClient}'s javadoc calls for.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Explicit, not auto-discovered: since fix round 1 added PortalDevProfileOrderingIT's own
        // nested @SpringBootConfiguration in this same package (com.nubeero.cia.portal.auth),
        // @SpringBootTest's default package-walk config discovery finds two candidates and throws
        // "Found multiple @SpringBootConfiguration annotated classes". Naming this one explicitly
        // sidesteps the ambiguity regardless of what other fixtures live in the package.
        classes = PortalAuthTestApplication.class,
        // cia-partner-api (a compile dependency of this module, transitively on the classpath)
        // brings bucket4j-spring-boot-starter along; its autoconfiguration activates for ANY
        // @EnableAutoConfiguration app regardless of component-scan scope and fails fast with
        // NoCacheConfiguredException absent a full rate-limit cache config — disable it, mirroring
        // FinanceWebItSupport / PartnerDeveloperWebItSupport in cia-api.
        properties = "bucket4j.enabled=false"
)
@AutoConfigureMockMvc
class PortalAuthFlowIT {

    private static final String STUB_EMAIL = "dev@insurtech.example";
    private static final String STUB_DISPLAY_NAME = "Dev Example";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciaportalauthtest")
                    .withUsername("ciaportalauthtest")
                    .withPassword("ciaportalauthtest");

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
        String sql;
        try (InputStream in = PortalAuthFlowIT.class.getResourceAsStream("/schema/partner_portal_grant.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PartnerPortalGrantRepository grantRepository;

    /** Replaces the real {@link KeycloakPortalOAuthClient} — no live Keycloak in the IT. */
    @TestConfiguration
    static class StubOAuthConfig {

        @Bean
        @Primary
        PortalOAuthClient stubPortalOAuthClient() {
            return new PortalOAuthClient() {
                @Override
                public String buildAuthorizeUrl(String state, String codeChallenge, String redirectUri) {
                    return "https://kc.test/realms/partner/protocol/openid-connect/auth"
                            + "?client_id=cia-partner-portal"
                            + "&redirect_uri=" + redirectUri
                            + "&response_type=code&scope=openid%20email%20profile"
                            + "&state=" + state
                            + "&code_challenge=" + codeChallenge
                            + "&code_challenge_method=S256";
                }

                @Override
                public PortalOAuthTokens exchangeCode(String code, String codeVerifier, String redirectUri) {
                    return new PortalOAuthTokens(
                            "test-access-token-" + code, "test-refresh-token-" + code, null,
                            STUB_EMAIL, STUB_DISPLAY_NAME);
                }

                @Override
                public String buildLogoutUrl(String idTokenHint, String postLogoutRedirectUri) {
                    return "https://kc.test/realms/partner/protocol/openid-connect/logout"
                            + "?post_logout_redirect_uri=" + postLogoutRedirectUri;
                }
            };
        }
    }

    @Test
    void fullFlow_login_callback_me_logout() throws Exception {
        // Seed a grant for the developer the stub token exchange will resolve — proves the /me
        // read really goes through PartnerPortalGrantRepository (public schema) under the real
        // multi-tenant connection provider, not a bypassed/mocked path.
        UUID partnerUserId = PartnerDeveloperService.derivePartnerUserId(STUB_EMAIL);
        UUID partnerAppId = UUID.randomUUID();
        PartnerPortalGrant grant = new PartnerPortalGrant();
        grant.setPartnerUserId(partnerUserId);
        grant.setPartnerUserEmail(STUB_EMAIL);
        grant.setTenantSchema("tenant_acme");
        grant.setPartnerAppId(partnerAppId);
        grant.setRole(GrantRole.MANAGER);
        grant.setCreatedBy("system-admin");
        grantRepository.save(grant);

        // ── Step 1: GET /portal/auth/login ──────────────────────────────────────────────
        MvcResult loginResult = mvc.perform(get("/portal/auth/login"))
                .andExpect(status().isFound())
                .andReturn();

        String location = loginResult.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        assertThat(location).contains("code_challenge=");
        assertThat(location).contains("state=");
        assertThat(location).contains("code_challenge_method=S256");

        String state = queryParam(location, "state");
        assertThat(state).isNotBlank();

        Cookie loginStateCookie = loginResult.getResponse().getCookie("cia_portal_login_state");
        assertThat(loginStateCookie).isNotNull();
        assertThat(loginStateCookie.isHttpOnly()).isTrue();

        // ── Step 2: GET /portal/auth/callback?code=...&state=... ───────────────────────
        MvcResult callbackResult = mvc.perform(get("/portal/auth/callback")
                        .param("code", "test-authz-code")
                        .param("state", state)
                        .cookie(loginStateCookie))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(callbackResult.getResponse().getHeader("Location")).isEqualTo("http://localhost:5174");

        String rawSetCookie = callbackResult.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("cia_portal_session="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cia_portal_session Set-Cookie header"));
        assertThat(rawSetCookie).contains("HttpOnly");
        assertThat(rawSetCookie).contains("Secure");
        assertThat(rawSetCookie).contains("SameSite=Strict");
        // The cookie carries only an opaque id — never a JWT (three dot-separated base64 segments)
        // and never either canned token value the stub returned.
        assertThat(rawSetCookie).doesNotContain("test-access-token");
        assertThat(rawSetCookie).doesNotContain("test-refresh-token");

        Cookie sessionCookie = callbackResult.getResponse().getCookie("cia_portal_session");
        assertThat(sessionCookie).isNotNull();
        assertThat(sessionCookie.getValue()).doesNotContain(".");   // not JWT-shaped

        // ── Step 3: GET /portal/auth/me ─────────────────────────────────────────────────
        MvcResult meResult = mvc.perform(get("/portal/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn();

        String meBody = meResult.getResponse().getContentAsString();
        assertThat(meBody).doesNotContain("accessToken");
        assertThat(meBody).doesNotContain("refreshToken");
        assertThat(meBody).doesNotContain("test-access-token");
        assertThat(meBody).doesNotContain("test-refresh-token");

        JsonNode meJson = objectMapper.readTree(meBody);
        assertThat(meJson.path("data").path("email").asText()).isEqualTo(STUB_EMAIL);
        assertThat(meJson.path("data").path("partnerUserId").asText()).isEqualTo(partnerUserId.toString());
        String csrfToken = meJson.path("data").path("csrfToken").asText();
        assertThat(csrfToken).isNotBlank();
        assertThat(meJson.path("data").path("apps")).hasSize(1);
        assertThat(meJson.path("data").path("apps").get(0).path("partnerAppId").asText())
                .isEqualTo(partnerAppId.toString());

        // ── Step 4: POST /portal/auth/logout without CSRF header → 403 ─────────────────
        mvc.perform(post("/portal/auth/logout").cookie(sessionCookie))
                .andExpect(status().isForbidden());

        // ── Step 5: POST /portal/auth/logout with CSRF header → 200, session cleared ───
        MvcResult logoutResult = mvc.perform(post("/portal/auth/logout")
                        .cookie(sessionCookie)
                        .header("X-CSRF-Token", csrfToken))
                .andExpect(status().isOk())
                .andReturn();

        String logoutBody = logoutResult.getResponse().getContentAsString();
        assertThat(objectMapper.readTree(logoutBody).path("data").path("logoutUrl").asText()).contains("logout");

        String clearCookieHeader = logoutResult.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("cia_portal_session="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no session-clearing Set-Cookie header"));
        assertThat(clearCookieHeader).contains("Max-Age=0");

        // ── Step 6: the now-deleted session no longer authenticates ────────────────────
        mvc.perform(get("/portal/auth/me").cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withoutSessionCookie_returns401() throws Exception {
        mvc.perform(get("/portal/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void callback_withoutLoginStateCookie_returns400() throws Exception {
        mvc.perform(get("/portal/auth/callback").param("code", "x").param("state", "y"))
                .andExpect(status().isBadRequest());
    }

    private static String queryParam(String url, String name) throws Exception {
        Pattern pattern = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&]*)");
        Matcher matcher = pattern.matcher(url);
        assertThat(matcher.find()).as("query param '%s' present in %s", name, url).isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}
