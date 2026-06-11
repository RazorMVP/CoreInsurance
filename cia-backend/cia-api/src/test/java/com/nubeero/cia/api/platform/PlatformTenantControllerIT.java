package com.nubeero.cia.api.platform;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.api.platform.dto.OnboardTenantResponse;
import com.nubeero.cia.api.platform.dto.TenantSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-slice IT for {@link PlatformTenantController}.
 *
 * <p>The real onboarding stack (Keycloak realm provisioning, V67 platform tables) is a separate
 * later task; here {@link PlatformTenantService} and {@link PlatformAuditService} are mocked so the
 * test exercises ONLY the controller's auth gating (SUPER_ADMIN role + platform-realm assertion),
 * the 201/403/409 status mapping, and the response envelope shape.
 *
 * <p>Auth uses the {@code jwt()} post-processor (NOT {@code @WithMockUser}) because the controller
 * reads {@code @AuthenticationPrincipal Jwt} — a {@code User} principal would make
 * {@code assertPlatformRealm} see a null realm and never exercise the real path.
 */
class PlatformTenantControllerIT extends FinanceWebItSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PlatformTenantService service;
    @MockBean PlatformAuditService audit;

    private static final String PLATFORM_ISS = "https://kc.test/realms/platform";
    private static final String TENANT_ISS = "https://kc.test/realms/tenant_acme";

    private String validBody() throws Exception {
        // Map.of can't hold null, so omit the optional realm key.
        return objectMapper.writeValueAsString(Map.of(
                "schema", "tenant_acme",
                "displayName", "Acme",
                "subdomain", "acme",
                "adminUsername", "admin",
                "adminEmail", "a@acme.test"));
    }

    private static OnboardTenantResponse fixedResponse() {
        TenantSummary summary =
                new TenantSummary("tenant_acme", "Acme", "acme", true, Instant.parse("2026-06-10T00:00:00Z"));
        return new OnboardTenantResponse(
                summary,
                new OnboardTenantResponse.FirstAdmin("admin", "a@acme.test", "Aa1!secret"));
    }

    @Test
    void onboard_superAdmin_returns201WithTempPassword() throws Exception {
        when(service.onboard(any(), any(), any(), any())).thenReturn(fixedResponse());

        mvc.perform(post("/api/v1/platform/tenants")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.firstAdmin.temporaryPassword", is("Aa1!secret")));

        verify(service).onboard(any(), org.mockito.ArgumentMatchers.eq("superadmin"), any(), any());
    }

    @Test
    void onboard_missingSuperAdminRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/platform/tenants")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "joe"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void onboard_nonPlatformRealm_returns403() throws Exception {
        mvc.perform(post("/api/v1/platform/tenants")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", TENANT_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden());

        verify(service, never()).onboard(any(), any(), any(), any());
    }

    @Test
    void onboard_duplicate_returns409() throws Exception {
        when(service.onboard(any(), any(), any(), any()))
                .thenThrow(new TenantAlreadyExistsException("tenant_acme", "acme"));

        mvc.perform(post("/api/v1/platform/tenants")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("TENANT_ALREADY_EXISTS")));
    }

    @Test
    void audit_returnsRecentEntries() throws Exception {
        when(audit.recent(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new PlatformAuditService.PlatformAuditEntry(
                        java.util.UUID.randomUUID(), "ONBOARD", "tenant_acme",
                        "superadmin", "platform", null, "127.0.0.1", Instant.now())));

        mvc.perform(get("/api/v1/platform/audit?limit=5")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action", is("ONBOARD")));
    }

    @Test
    void get_unknownSchema_returns404() throws Exception {
        when(service.get(anyString())).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/v1/platform/tenants/tenant_ghost")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("TENANT_NOT_FOUND")));
    }
}
