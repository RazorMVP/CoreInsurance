package com.nubeero.cia.auth;

import com.nubeero.cia.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextFilterTest {

    @AfterEach
    void clearSecurityAndTenantContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void resolvesTenantClaimToRegisteredSchemaBeforeContinuingChain() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(claim -> Optional.of("tenant_alpha"));
        SecurityContextHolder.getContext().setAuthentication(authentication("alpha"));
        AtomicReference<String> tenantInsideChain = new AtomicReference<>();

        filter.doFilter(request(), new MockHttpServletResponse(),
                chainThatRuns(() -> tenantInsideChain.set(TenantContext.getTenantId())));

        assertThat(tenantInsideChain).hasValue("tenant_alpha");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void rejectsJwtWithoutTenantClaim() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(claim -> Optional.of("tenant_alpha"));
        SecurityContextHolder.getContext().setAuthentication(authentication(null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request(), response, chainThatRuns(() -> chainCalled.set(true)));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Missing tenant_id claim");
    }

    @Test
    void allowsTenantlessPlatformProvisioningRequestForAuthorizationLayer() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(claim -> Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(authentication(null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(new MockHttpServletRequest("POST", "/admin/v1/tenants"),
                response, chainThatRuns(() -> chainCalled.set(true)));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void rejectsUnknownTenantClaim() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(claim -> Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(authentication("unknown"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request(), response, chainThatRuns(() -> chainCalled.set(true)));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Unknown or inactive tenant");
    }

    @Test
    void leavesUnauthenticatedRequestsForSpringSecurityToReject() throws Exception {
        TenantContextFilter filter = new TenantContextFilter(claim -> Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request(), response, chainThatRuns(() -> chainCalled.set(true)));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    private JwtAuthenticationToken authentication(String tenantId) {
        Map<String, Object> claims = tenantId == null
                ? Map.of("sub", "user-1")
                : Map.of("sub", "user-1", "tenant_id", tenantId);
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                claims
        );
        return new JwtAuthenticationToken(jwt);
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/customers");
    }

    private FilterChain chainThatRuns(ThrowingRunnable runnable) {
        return (request, response) -> runnable.run();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException, ServletException;
    }
}
