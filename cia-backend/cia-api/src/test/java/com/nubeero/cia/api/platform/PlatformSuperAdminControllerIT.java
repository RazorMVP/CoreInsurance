package com.nubeero.cia.api.platform;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminResponse;
import com.nubeero.cia.api.platform.dto.SuperAdminSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class PlatformSuperAdminControllerIT extends FinanceWebItSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean PlatformSuperAdminService service;

    private static final String PLATFORM_ISS = "https://kc.test/realms/platform";
    private static final String TENANT_ISS   = "https://kc.test/realms/tenant_acme";

    private static MockHttpServletRequestBuilder withSuperAdmin(MockHttpServletRequestBuilder b) {
        return b.with(jwt()
                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "rootadmin"))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
    }

    @Test
    void list_returnsSuperAdmins() throws Exception {
        when(service.list()).thenReturn(List.of(new SuperAdminSummary("rootadmin", "r@x.test", true)));
        mvc.perform(withSuperAdmin(get("/api/v1/platform/super-admins")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username", is("rootadmin")));
    }

    @Test
    void invite_returns201WithTempPassword() throws Exception {
        when(service.invite(any(), anyString(), anyString(), anyString()))
                .thenReturn(new InviteSuperAdminResponse("sa2", "sa2@x.test", "Aa1!secret"));
        String body = objectMapper.writeValueAsString(Map.of("username", "sa2", "email", "sa2@x.test"));
        mvc.perform(withSuperAdmin(post("/api/v1/platform/super-admins"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.temporaryPassword", is("Aa1!secret")));
        verify(service).invite(any(), org.mockito.ArgumentMatchers.eq("rootadmin"), anyString(), anyString());
    }

    @Test
    void invite_nonPlatformRealm_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "sa2", "email", "sa2@x.test"));
        mvc.perform(post("/api/v1/platform/super-admins")
                        .with(jwt().jwt(j -> j.claim("iss", TENANT_ISS).claim("preferred_username", "x"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void invite_missingRole_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "sa2", "email", "sa2@x.test"));
        mvc.perform(post("/api/v1/platform/super-admins")
                        .with(jwt().jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "x"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void revoke_self_returns409() throws Exception {
        doThrow(new SuperAdminExceptions.CannotRevokeSelf())
                .when(service).revoke(anyString(), anyString(), anyString(), anyString());
        mvc.perform(withSuperAdmin(delete("/api/v1/platform/super-admins/rootadmin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CANNOT_REVOKE_SELF")));
    }

    @Test
    void invite_keycloakDisabled_returns503() throws Exception {
        when(service.invite(any(), anyString(), anyString(), anyString()))
                .thenThrow(new SuperAdminExceptions.KeycloakAdminDisabled());
        String body = objectMapper.writeValueAsString(Map.of("username", "sa2", "email", "sa2@x.test"));
        mvc.perform(withSuperAdmin(post("/api/v1/platform/super-admins"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errors[0].code", is("KEYCLOAK_ADMIN_DISABLED")));
    }

    @Test
    void revoke_happyPath_returns200AndPassesActor() throws Exception {
        // revoke returns void; the unstubbed mock is a no-op. Assert 200 + the right actor/path args.
        mvc.perform(withSuperAdmin(delete("/api/v1/platform/super-admins/victim")))
                .andExpect(status().isOk());
        verify(service).revoke(org.mockito.ArgumentMatchers.eq("victim"),
                org.mockito.ArgumentMatchers.eq("rootadmin"), anyString(), anyString());
    }

    @Test
    void revoke_lastSuperAdmin_returns409() throws Exception {
        doThrow(new SuperAdminExceptions.CannotRevokeLast())
                .when(service).revoke(anyString(), anyString(), anyString(), anyString());
        mvc.perform(withSuperAdmin(delete("/api/v1/platform/super-admins/victim")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CANNOT_REVOKE_LAST_SUPER_ADMIN")));
    }

    @Test
    void revoke_unknownUser_returns404() throws Exception {
        doThrow(new SuperAdminExceptions.NotFound("ghost"))
                .when(service).revoke(anyString(), anyString(), anyString(), anyString());
        mvc.perform(withSuperAdmin(delete("/api/v1/platform/super-admins/ghost")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("SUPER_ADMIN_NOT_FOUND")));
    }

    @Test
    void invite_blankUsername_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "", "email", "sa2@x.test"));
        mvc.perform(withSuperAdmin(post("/api/v1/platform/super-admins"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("VALIDATION_ERROR")));
    }
}
