package com.nubeero.cia.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveEndpointRateLimitFilterTest {

    @Test
    void returnsTooManyRequestsAfterLimitIsConsumed() throws Exception {
        SensitiveEndpointRateLimitPolicy policy =
                new SensitiveEndpointRateLimitPolicy(true, 2, 2, 2);
        SensitiveEndpointRateLimitFilter filter = new SensitiveEndpointRateLimitFilter(
                policy, Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneOffset.UTC));
        AtomicInteger downstreamCalls = new AtomicInteger();
        FilterChain chain = (request, response) -> downstreamCalls.incrementAndGet();

        MockHttpServletResponse first = response();
        filter.doFilter(request(), first, chain);
        MockHttpServletResponse second = response();
        filter.doFilter(request(), second, chain);
        MockHttpServletResponse third = response();
        filter.doFilter(request(), third, chain);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getContentAsString()).contains("rate_limit_exceeded");
        assertThat(downstreamCalls).hasValue(2);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/partner/v1/quotes");
        request.setServletPath("/partner/v1/quotes");
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }
}
