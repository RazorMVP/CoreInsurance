package com.nubeero.cia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nubeero.cia.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    private Jwt jwtWith(String iss, String tenantClaim) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u");
        if (iss != null) b.claim("iss", iss);
        if (tenantClaim != null) b.claim("tenant_id", tenantClaim);
        return b.build();
    }

    private String capturedTenantDuringChain(Jwt jwt) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = TenantContext.getTenantId();
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);
        return seen[0];
    }

    @Test
    @DisplayName("realm from iss is the tenant (authoritative)")
    void realmFromIssWins() throws Exception {
        String t = capturedTenantDuringChain(
            jwtWith("http://localhost:8280/realms/acme", "ignored-claim"));
        assertThat(t).isEqualTo("acme");
    }

    @Test
    @DisplayName("falls back to tenant_id claim when iss has no realm")
    void fallsBackToClaim() throws Exception {
        String t = capturedTenantDuringChain(jwtWith(null, "cia"));
        assertThat(t).isEqualTo("cia");
    }

    @Test
    @DisplayName("clears tenant after the chain")
    void clearsAfter() throws Exception {
        capturedTenantDuringChain(jwtWith("http://localhost:8280/realms/acme", null));
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("non-JWT principal sets no tenant")
    void nonJwtNoTenant() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p"));
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = TenantContext.getTenantId();
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);
        assertThat(seen[0]).isNull();
    }

    @Test
    @DisplayName("puts tenant into MDC during the chain and removes it after")
    void mdcTenantSetDuringChainAndClearedAfter() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            jwtWith("http://localhost:8280/realms/acme", null)));
        String[] mdcDuring = new String[1];
        FilterChain chain = (req, res) -> mdcDuring[0] = MDC.get(TenantContextFilter.MDC_TENANT_KEY);
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);
        assertThat(mdcDuring[0]).isEqualTo("acme");
        assertThat(MDC.get(TenantContextFilter.MDC_TENANT_KEY)).isNull();
    }

    @Test
    @DisplayName("a non-resolving request leaves no stale tenant in MDC (no pooled-thread bleed)")
    void mdcClearedWhenNoTenantResolves() throws Exception {
        // Simulate a prior request on this pooled thread having left a tenant in MDC.
        MDC.put(TenantContextFilter.MDC_TENANT_KEY, "stale");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p"));
        FilterChain chain = (req, res) -> { /* no-op */ };
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);
        // The unconditional MDC.remove in finally must wipe the stale value even
        // though this request resolved no tenant.
        assertThat(MDC.get(TenantContextFilter.MDC_TENANT_KEY)).isNull();
    }
}
