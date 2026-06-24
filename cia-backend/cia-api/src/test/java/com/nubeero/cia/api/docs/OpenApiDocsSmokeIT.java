package com.nubeero.cia.api.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.finance.FinanceWebItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression guard for the springdoc ↔ Spring runtime compatibility.
 *
 * <p>springdoc 2.5.0 called {@code ControllerAdviceBean(Object)}, which Spring
 * 6.2 (Boot 3.5.14) removed — so live {@code /partner/v3/api-docs/**} and the
 * {@code /partner/docs} Swagger UI 500'd with a {@code NoSuchMethodError} once
 * the S144 Boot bump landed. The breakage was latent because the committed
 * OpenAPI/Postman snapshots are static files; nothing exercised the live
 * generator. The springdoc 2.8.17 bump restores it.
 *
 * <p>This boots the full context and asserts BOTH springdoc groups actually
 * render — 200 with a non-empty {@code paths} object containing a representative
 * route — so a future springdoc/Spring version skew fails CI instead of silently
 * 500-ing the developer docs.
 *
 * <p>Extends {@link FinanceWebItSupport} to reuse its already-cached
 * {@code @SpringBootTest} context (no extra boot); adds no context config.
 */
class OpenApiDocsSmokeIT extends FinanceWebItSupport {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void internalApiGroupRenders() throws Exception {
        JsonNode spec = fetch("/partner/v3/api-docs/internal-api");
        assertThat(spec.path("paths").size())
                .as("internal-api group must expose paths").isGreaterThan(0);
        assertThat(spec.path("paths").fieldNames())
                .toIterable().anyMatch(p -> p.startsWith("/api/v1/"));
    }

    @Test
    void partnerApiGroupRenders() throws Exception {
        JsonNode spec = fetch("/partner/v3/api-docs/partner-api");
        assertThat(spec.path("paths").size())
                .as("partner-api group must expose paths").isGreaterThan(0);
        assertThat(spec.path("paths").fieldNames())
                .toIterable().anyMatch(p -> p.startsWith("/partner/v1/"));
    }

    private JsonNode fetch(String docsPath) throws Exception {
        String body = mockMvc.perform(get(docsPath))
                .andExpect(status().isOk())   // 500 before the springdoc 2.8.17 bump
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
