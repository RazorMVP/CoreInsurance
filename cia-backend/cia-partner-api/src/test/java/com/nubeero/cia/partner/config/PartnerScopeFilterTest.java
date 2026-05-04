package com.nubeero.cia.partner.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}
