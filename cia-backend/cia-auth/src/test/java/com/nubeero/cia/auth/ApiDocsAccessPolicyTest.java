package com.nubeero.cia.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ApiDocsAccessPolicyTest {

    @Test
    void disablesPublicDocsByDefault() {
        assertThat(new ApiDocsAccessPolicy(false).publicDocsEnabled()).isFalse();
    }

    @Test
    void matchesInternalAndPartnerDocsRoutes() {
        ApiDocsAccessPolicy policy = new ApiDocsAccessPolicy(true);

        assertThat(matches(policy, "/partner/docs")).isTrue();
        assertThat(matches(policy, "/partner/v3/api-docs/internal-api")).isTrue();
        assertThat(matches(policy, "/internal/docs")).isTrue();
        assertThat(matches(policy, "/internal/v3/api-docs")).isTrue();
    }

    @Test
    void doesNotMatchBusinessApiRoutes() {
        assertThat(matches(new ApiDocsAccessPolicy(true), "/partner/v1/products")).isFalse();
    }

    private boolean matches(ApiDocsAccessPolicy policy, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return Arrays.stream(policy.requestMatchers()).anyMatch(matcher -> matches(matcher, request));
    }

    private boolean matches(RequestMatcher matcher, MockHttpServletRequest request) {
        return matcher.matches(request);
    }
}
