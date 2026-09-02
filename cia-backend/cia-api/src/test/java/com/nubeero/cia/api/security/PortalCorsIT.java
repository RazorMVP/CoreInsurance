package com.nubeero.cia.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof that the {@code /portal/**} CORS policy (Task 11, {@code
 * PortalSecurityConfig.portalCorsConfigurationSource} in {@code cia-partner-portal-bff}) is wired
 * into the live portal security chain and sourced from {@code cia.partner-portal.allowed-origins}
 * (not the unrelated {@code appUrl} redirect-target property) — mirrors {@link CorsPreflightIT},
 * the equivalent proof for the internal {@code /api/**} policy.
 *
 * <p>Overrides {@code cia.partner-portal.allowed-origins} to a two-origin CSV list via {@code
 * @DynamicPropertySource} (additive across the {@link FinanceWebItSupport} hierarchy — this spins
 * a distinct, separately-cached context) so the test genuinely exercises the config-driven origin
 * set rather than merely confirming the coincidental default already equals the SPA's dev origin.
 *
 * <p>The third assertion proves the negative half of the Task 11 brief: {@code /partner/**}
 * ({@code PartnerSecurityConfig}, cia-partner-api) has no {@code .cors(...)} call at all, so no
 * {@code CorsFilter} is attached to that chain — a preflight there carries no
 * {@code Access-Control-Allow-Origin} header regardless of the request's {@code Origin}.
 */
class PortalCorsIT extends FinanceWebItSupport {

    private static final String CONFIGURED_ORIGIN_1 = "http://localhost:5174";
    private static final String CONFIGURED_ORIGIN_2 = "https://partner-preview.example.test";
    private static final String RANDOM_ORIGIN = "https://evil.example";

    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void portalCorsProps(DynamicPropertyRegistry registry) {
        registry.add("cia.partner-portal.allowed-origins",
                () -> CONFIGURED_ORIGIN_1 + "," + CONFIGURED_ORIGIN_2);
    }

    @Test
    void preflightFromConfiguredPortalOrigin_returnsCorsHeaders() throws Exception {
        mvc.perform(options("/portal/apps")
                        .header(HttpHeaders.ORIGIN, CONFIGURED_ORIGIN_1)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONFIGURED_ORIGIN_1))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void preflightFromSecondConfiguredPortalOrigin_returnsCorsHeaders() throws Exception {
        // Proves cia.partner-portal.allowed-origins is a genuine CSV-bindable list, not a
        // single-value shim — both entries must be honoured independently.
        mvc.perform(options("/portal/apps")
                        .header(HttpHeaders.ORIGIN, CONFIGURED_ORIGIN_2)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONFIGURED_ORIGIN_2))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void preflightFromRandomOrigin_isForbidden() throws Exception {
        mvc.perform(options("/portal/apps")
                        .header(HttpHeaders.ORIGIN, RANDOM_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightOnPartnerApi_carriesNoCorsHeaders() throws Exception {
        // /partner/** stays CORS-free by design (M2M, OAuth2 client-credentials, no browser
        // origin) — PartnerSecurityConfig never calls .cors(...), so no CorsFilter runs on this
        // chain at all. Whatever status the plain (non-preflight-aware) chain returns, the one
        // thing that must never appear is an Access-Control-Allow-Origin header.
        mvc.perform(options("/partner/v1/products")
                        .header(HttpHeaders.ORIGIN, CONFIGURED_ORIGIN_1)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
