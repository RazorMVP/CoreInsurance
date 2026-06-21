package com.nubeero.cia.partner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nubeero.cia.partner.config.PartnerRateLimitService.RateLimitVerdict;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Unit tests for {@link PartnerRateLimitFilter} — the per-client wrapper that
 * extracts client_id from the validated JWT, consumes a token, sets the
 * {@code X-RateLimit-*} headers, and returns {@code 429} when the bucket is empty.
 * Pure unit test (mocked {@link PartnerRateLimitService}, mock servlet objects).
 */
@ExtendWith(MockitoExtension.class)
class PartnerRateLimitFilterTest {

    @Mock PartnerRateLimitService rateLimitService;
    @Mock FilterChain chain;

    private final PartnerRateLimitProperties props = new PartnerRateLimitProperties();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PartnerRateLimitFilter filter() {
        return new PartnerRateLimitFilter(rateLimitService, props);
    }

    private static void authenticateAs(String clientId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("client_id", clientId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static MockHttpServletRequest partnerRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/partner/v1/products");
        req.setRequestURI("/partner/v1/products");
        return req;
    }

    @Test
    void allowed_setsRateLimitHeaders_andContinuesChain() throws Exception {
        authenticateAs("c-a");
        when(rateLimitService.tryConsume(eq("c-a")))
                .thenReturn(new RateLimitVerdict(true, 60, 59, 1, 1_700_000_000L));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilter(partnerRequest(), res, chain);

        verify(chain).doFilter(any(), any());
        assertThat(res.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(res.getHeader("X-RateLimit-Remaining")).isEqualTo("59");
        assertThat(res.getHeader("X-RateLimit-Reset")).isEqualTo("1700000000");
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void denied_returns429_withRetryAfter_andDoesNotContinueChain() throws Exception {
        authenticateAs("c-a");
        when(rateLimitService.tryConsume(eq("c-a")))
                .thenReturn(new RateLimitVerdict(false, 60, 0, 30, 1_700_000_030L));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilter(partnerRequest(), res, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("30");
        assertThat(res.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(res.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    void disabled_passesThroughWithoutConsuming() throws Exception {
        props.setEnabled(false);
        authenticateAs("c-a");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilter(partnerRequest(), res, chain);

        verify(chain).doFilter(any(), any());
        verify(rateLimitService, never()).tryConsume(any());
    }

    @Test
    void noJwt_passesThroughWithoutConsuming() throws Exception {
        // No authentication in the context — nothing to key a bucket on.
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter().doFilter(partnerRequest(), res, chain);

        verify(chain).doFilter(any(), any());
        verify(rateLimitService, never()).tryConsume(any());
    }

    @Test
    void nonPartnerPath_isNotFiltered() {
        MockHttpServletRequest internal = new MockHttpServletRequest("GET", "/api/v1/policies");
        internal.setRequestURI("/api/v1/policies");
        assertThat(filter().shouldNotFilter(internal)).isTrue();

        assertThat(filter().shouldNotFilter(partnerRequest())).isFalse();
    }
}
