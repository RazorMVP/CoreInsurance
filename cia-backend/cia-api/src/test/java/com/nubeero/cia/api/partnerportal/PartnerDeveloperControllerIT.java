package com.nubeero.cia.api.partnerportal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.partner.app.PartnerApp;
import com.nubeero.cia.partner.app.PartnerAppRepository;
import com.nubeero.cia.partner.app.PartnerPlan;
import com.nubeero.cia.portal.grant.GrantRole;
import com.nubeero.cia.portal.grant.PartnerPortalGrant;
import com.nubeero.cia.portal.grant.PartnerPortalGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice IT for the internal "invite developer" endpoints:
 * {@code POST /api/v1/partner-apps/{id}/developers}, {@code GET .../developers},
 * {@code DELETE .../developers/{grantId}}.
 *
 * <p>Auth uses the {@code jwt()} post-processor (see {@link PartnerDeveloperWebItSupport}) so
 * {@code TenantContextFilter} populates {@code TenantContext} from the token's {@code iss} realm —
 * required because {@code PartnerDeveloperService.invite} writes {@code tenant_schema}.
 */
class PartnerDeveloperControllerIT extends PartnerDeveloperWebItSupport {

    private static final String TENANT_ISS = "https://kc.test/realms/tenant_acme";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PartnerAppRepository partnerAppRepository;
    @Autowired PartnerPortalGrantRepository grantRepository;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.execute("DELETE FROM public.partner_portal_grant");
        jdbc.execute("DELETE FROM partner_apps");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private PartnerApp seedPartnerApp() {
        return partnerAppRepository.save(PartnerApp.builder()
                .clientId("insurtech-" + UUID.randomUUID())
                .appName("Test Insurtech")
                .contactEmail("contact@insurtech.test")
                .scopes("products:read quotes:create")
                .plan(PartnerPlan.STARTER)
                .rateLimitRpm(60)
                .active(true)
                .build());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminJwt() {
        return jwt()
                .jwt(j -> j.claim("iss", TENANT_ISS).claim("preferred_username", "admin1"))
                .authorities(
                        new SimpleGrantedAuthority("setup:create"),
                        new SimpleGrantedAuthority("setup:view"),
                        new SimpleGrantedAuthority("setup:update"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor viewOnlyJwt() {
        return jwt()
                .jwt(j -> j.claim("iss", TENANT_ISS).claim("preferred_username", "viewer1"))
                .authorities(new SimpleGrantedAuthority("setup:view"));
    }

    private String inviteBody(String email, GrantRole role) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "role", role.name()));
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /developers with admin authority creates a grant row (201 + body)")
    void invite_admin_createsGrant() throws Exception {
        PartnerApp app = seedPartnerApp();

        mvc.perform(post("/api/v1/partner-apps/" + app.getId() + "/developers")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("Dev@Example.com", GrantRole.MANAGER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.partnerAppId", is(app.getId().toString())))
                .andExpect(jsonPath("$.data.email", is("dev@example.com")))
                .andExpect(jsonPath("$.data.role", is("MANAGER")));

        // Real-context verification: the row genuinely landed with a non-blank tenant_schema,
        // proving TenantContext resolved from the jwt() iss claim through to the write path.
        var rows = grantRepository.findByPartnerAppIdAndDeletedAtIsNull(app.getId());
        assertTrue(rows.size() == 1 && rows.get(0).getTenantSchema() != null
                && !rows.get(0).getTenantSchema().isBlank());
    }

    @Test
    @DisplayName("POST /developers twice for the same (email, app) is idempotent — second call returns 409")
    void invite_duplicate_returns409() throws Exception {
        PartnerApp app = seedPartnerApp();

        mvc.perform(post("/api/v1/partner-apps/" + app.getId() + "/developers")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("dev@example.com", GrantRole.MANAGER)))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/partner-apps/" + app.getId() + "/developers")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("dev@example.com", GrantRole.VIEWER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("DUPLICATE_GRANT")));

        // No second row was created.
        var rows = grantRepository.findByPartnerAppIdAndDeletedAtIsNull(app.getId());
        assertTrue(rows.size() == 1);
    }

    @Test
    @DisplayName("POST /developers without setup:create returns 403")
    void invite_withoutAuthority_403() throws Exception {
        PartnerApp app = seedPartnerApp();

        mvc.perform(post("/api/v1/partner-apps/" + app.getId() + "/developers")
                        .with(viewOnlyJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("dev@example.com", GrantRole.MANAGER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /developers lists active grants for the app")
    void list_returnsGrant() throws Exception {
        PartnerApp app = seedPartnerApp();
        grantRepository.save(seededGrant(app.getId(), "dev@example.com", GrantRole.VIEWER));

        mvc.perform(get("/api/v1/partner-apps/" + app.getId() + "/developers").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(1)))
                .andExpect(jsonPath("$.data[0].email", is("dev@example.com")))
                .andExpect(jsonPath("$.data[0].role", is("VIEWER")));
    }

    @Test
    @DisplayName("GET /developers without setup:view returns 403")
    void list_withoutAuthority_403() throws Exception {
        PartnerApp app = seedPartnerApp();

        mvc.perform(get("/api/v1/partner-apps/" + app.getId() + "/developers")
                        .with(jwt().jwt(j -> j.claim("iss", TENANT_ISS))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /developers/{grantId} soft-deletes — grant disappears from the list")
    void delete_softDeletes() throws Exception {
        PartnerApp app = seedPartnerApp();
        PartnerPortalGrant grant =
                grantRepository.save(seededGrant(app.getId(), "dev@example.com", GrantRole.MANAGER));

        mvc.perform(delete("/api/v1/partner-apps/" + app.getId() + "/developers/" + grant.getId())
                        .with(adminJwt()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/partner-apps/" + app.getId() + "/developers").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", is(0)));

        PartnerPortalGrant reloaded = grantRepository.findById(grant.getId()).orElseThrow();
        assertTrue(reloaded.getDeletedAt() != null);
    }

    @Test
    @DisplayName("DELETE /developers/{grantId} without setup:update returns 403")
    void delete_withoutAuthority_403() throws Exception {
        PartnerApp app = seedPartnerApp();
        PartnerPortalGrant grant =
                grantRepository.save(seededGrant(app.getId(), "dev@example.com", GrantRole.MANAGER));

        mvc.perform(delete("/api/v1/partner-apps/" + app.getId() + "/developers/" + grant.getId())
                        .with(viewOnlyJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /developers for an unknown partner app returns 404")
    void invite_unknownApp_404() throws Exception {
        mvc.perform(post("/api/v1/partner-apps/" + UUID.randomUUID() + "/developers")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inviteBody("dev@example.com", GrantRole.MANAGER)))
                .andExpect(status().isNotFound());
    }

    private PartnerPortalGrant seededGrant(UUID appId, String email, GrantRole role) {
        PartnerPortalGrant grant = new PartnerPortalGrant();
        grant.setPartnerUserId(UUID.nameUUIDFromBytes(email.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        grant.setPartnerUserEmail(email);
        grant.setTenantSchema("tenant_acme");
        grant.setPartnerAppId(appId);
        grant.setRole(role);
        grant.setCreatedBy("test-fixture");
        return grant;
    }
}
