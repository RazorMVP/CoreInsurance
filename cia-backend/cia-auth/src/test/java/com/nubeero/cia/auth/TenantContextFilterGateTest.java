package com.nubeero.cia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nubeero.cia.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantContextFilterGateTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private Jwt jwt(String iss) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("u").claim("iss", iss).build();
    }

    private PlatformRealmProperties props(boolean gateOn) {
        PlatformRealmProperties p = new PlatformRealmProperties();
        p.setRealm("platform");
        p.getTenantAllowlist().setEnabled(gateOn);
        return p;
    }

    @Test
    void suspendedTenantRealmIsRejected401WhenGateOn() throws Exception {
        TenantActivationLookup lookup = mock(TenantActivationLookup.class);
        when(lookup.isActive("acme")).thenReturn(false);
        var filter = new TenantContextFilter(props(true), lookup);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("http://localhost:8280/realms/acme")));
        var res = new MockHttpServletResponse();
        boolean[] chainRan = {false};
        FilterChain chain = (rq, rs) -> chainRan[0] = true;
        filter.doFilter(new MockHttpServletRequest(), res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chainRan[0]).isFalse();
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void platformRealmExemptFromGate() throws Exception {
        TenantActivationLookup lookup = mock(TenantActivationLookup.class);  // never consulted
        var filter = new TenantContextFilter(props(true), lookup);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("http://localhost:8280/realms/platform")));
        var res = new MockHttpServletResponse();
        boolean[] chainRan = {false};
        filter.doFilter(new MockHttpServletRequest(), res, (rq, rs) -> chainRan[0] = true);
        assertThat(chainRan[0]).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
        verifyNoInteractions(lookup);
    }

    @Test
    void gateOffPassesThrough() throws Exception {
        TenantActivationLookup lookup = mock(TenantActivationLookup.class);
        var filter = new TenantContextFilter(props(false), lookup);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("http://localhost:8280/realms/acme")));
        var res = new MockHttpServletResponse();
        boolean[] chainRan = {false};
        filter.doFilter(new MockHttpServletRequest(), res, (rq, rs) -> chainRan[0] = true);
        assertThat(chainRan[0]).isTrue();
    }

    @Test
    void gateEnabledButNullLookupIsFailClosed() throws Exception {
        var filter = new TenantContextFilter(props(true), null);   // no lookup bean wired
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt("http://localhost:8280/realms/acme")));
        var res = new MockHttpServletResponse();
        boolean[] chainRan = {false};
        filter.doFilter(new MockHttpServletRequest(), res, (rq, rs) -> chainRan[0] = true);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chainRan[0]).isFalse();
        assertThat(TenantContext.getTenantId()).isNull();   // no TenantContext leak on the short-circuit
    }
}
