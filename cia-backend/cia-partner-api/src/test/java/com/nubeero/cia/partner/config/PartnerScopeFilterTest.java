package com.nubeero.cia.partner.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerScopeFilterTest {

    private final PartnerScopeFilter filter = new PartnerScopeFilter();

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(textBlock = """
            # Products
            GET,    /partner/v1/products,                products:read
            GET,    /partner/v1/products/abc-123,        products:read
            GET,    /partner/v1/products/abc-123/classes, products:read

            # Quotes
            POST,   /partner/v1/quotes,                  quotes:create
            GET,    /partner/v1/quotes/q-1,              quotes:read

            # Customers — typed-create routes
            POST,   /partner/v1/customers/individual,    customers:create
            POST,   /partner/v1/customers/corporate,     customers:create
            GET,    /partner/v1/customers/cus-99,        customers:read

            # Policies
            POST,   /partner/v1/policies,                policies:create
            GET,    /partner/v1/policies/p-1,            policies:read
            GET,    /partner/v1/policies/p-1/document,   policies:read

            # The collision case — claims under a policy must NOT resolve as policies:create
            POST,   /partner/v1/policies/p-1/claims,     claims:create

            # Claims
            GET,    /partner/v1/claims/c-1,              claims:read

            # Webhooks
            POST,   /partner/v1/webhooks,                webhooks:manage
            GET,    /partner/v1/webhooks,                webhooks:manage
            DELETE, /partner/v1/webhooks/wh-1,           webhooks:manage
            """)
    void resolvesScopeForKnownRoutes(String method, String path, String expectedScope) {
        assertThat(filter.resolveRequiredScope(method, path)).isEqualTo(expectedScope);
    }

    @Test
    void unknownPathReturnsNull() {
        assertThat(filter.resolveRequiredScope("GET", "/partner/v1/unknown")).isNull();
    }

    @Test
    void wrongMethodReturnsNull() {
        assertThat(filter.resolveRequiredScope("PATCH", "/partner/v1/policies")).isNull();
    }

    @Test
    void caseInsensitiveMethodMatch() {
        assertThat(filter.resolveRequiredScope("post", "/partner/v1/quotes"))
                .isEqualTo("quotes:create");
    }

    @Test
    void exactRouteDoesNotMatchExtraSegments() {
        // POST /partner/v1/policies must NOT match POST /partner/v1/policies/p-1/claims
        // via prefix — claims:create takes precedence (verified above), but here we
        // confirm the segment-aware matcher rejects unrelated extensions.
        assertThat(filter.resolveRequiredScope("POST", "/partner/v1/policies/p-1/extra/segments"))
                .isNull();
    }

    @Test
    void rejectsRequestWhenJwtLacksRequiredScope() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication("products:read"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        try {
            filter.doFilter(
                    request("POST", "/partner/v1/quotes"),
                    response,
                    chainThatMarks(chainCalled)
            );
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("quotes:create");
    }

    @Test
    void allowsRequestWhenJwtHasRequiredScope() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication("products:read quotes:create"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        try {
            filter.doFilter(
                    request("POST", "/partner/v1/quotes"),
                    response,
                    chainThatMarks(chainCalled)
            );
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsRequestWhenAuthenticationIsMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        try {
            filter.doFilter(
                    request("GET", "/partner/v1/products"),
                    response,
                    chainThatMarks(chainCalled)
            );
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("No valid authentication");
    }

    private MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private JwtAuthenticationToken authentication(String scope) {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("scope", scope)
        );
        return new JwtAuthenticationToken(jwt);
    }

    private FilterChain chainThatMarks(AtomicBoolean chainCalled) {
        return (request, response) -> chainCalled.set(true);
    }
}
