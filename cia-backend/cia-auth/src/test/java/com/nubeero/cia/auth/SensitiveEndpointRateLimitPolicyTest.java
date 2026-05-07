package com.nubeero.cia.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveEndpointRateLimitPolicyTest {

    private final SensitiveEndpointRateLimitPolicy policy =
            new SensitiveEndpointRateLimitPolicy(true, 300, 60, 10);

    @Test
    void matchesPartnerApiRequests() {
        var rule = policy.match(request("POST", "/partner/v1/quotes"));

        assertThat(rule).isPresent();
        assertThat(rule.get().id()).isEqualTo("partner-api");
        assertThat(rule.get().capacity()).isEqualTo(300);
    }

    @Test
    void matchesSensitiveBackOfficeRequests() {
        assertThat(policy.match(request("POST", "/admin/v1/tenants"))).isPresent();
        assertThat(policy.match(request("GET", "/api/v1/audit/export"))).isPresent();
        assertThat(policy.match(request("POST", "/api/v1/claims/claim-1/documents"))).isPresent();
        assertThat(policy.match(request("PUT", "/api/v1/customers/customer-1"))).isPresent();
    }

    @Test
    void leavesOrdinaryReadRequestsUnmatched() {
        assertThat(policy.match(request("GET", "/api/v1/customers"))).isEmpty();
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
