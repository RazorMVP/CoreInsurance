package com.nubeero.cia.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end proof that the CORS policy is wired into the live internal security chain — i.e.
 * a browser preflight ({@code OPTIONS} + {@code Origin} + {@code Access-Control-Request-Method})
 * is answered with the right headers <em>before</em> authentication, so the SPA can call
 * {@code /api/**} cross-origin.
 *
 * <p>Reuses {@link FinanceWebItSupport} verbatim (no extra mocks/properties) so it shares the
 * cached full-boot context. The default {@code cia.cors.allowed-origins} from application.yml
 * ({@code http://localhost:5173,5175}) is in effect, so {@code localhost:5173} is the allowed
 * origin under test and an arbitrary external origin is rejected. The assertions hold under either
 * security profile because CORS is wired into both {@code SecurityConfig} and {@code DevSecurityConfig}.
 */
class CorsPreflightIT extends FinanceWebItSupport {

    @Autowired MockMvc mvc;

    @Test
    void preflightFromAllowedOrigin_returnsCorsHeaders() throws Exception {
        mvc.perform(options("/api/v1/customers")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void preflightFromDisallowedOrigin_isForbidden() throws Exception {
        mvc.perform(options("/api/v1/customers")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }
}
