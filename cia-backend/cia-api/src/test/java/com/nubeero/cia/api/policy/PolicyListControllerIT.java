package com.nubeero.cia.api.policy;

import com.nubeero.cia.api.underwriting.UnderwritingWebItSupport;
import com.nubeero.cia.policy.PolicyStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice IT for {@code GET /api/v1/policies} (S5.2 server pagination).
 *
 * <p>Extends {@link UnderwritingWebItSupport} ({@code @SpringBootTest +
 * @AutoConfigureMockMvc}) so the full Spring Security filter chain and
 * {@code @PreAuthorize("hasRole('UNDERWRITING_VIEW')")} are active. The
 * controllers gate on {@code hasRole(...)}, so the mock authorities carry the
 * {@code ROLE_} prefix.
 *
 * <p>Rows are seeded directly via {@link JdbcTemplate} (no FK parents needed —
 * the denormalised policy columns are plain UUID/VARCHAR with no enforced FKs).
 * Because {@code @SpringBootTest} tests are not transactional, seeded rows
 * persist across methods within the class; every filter/sort test therefore
 * isolates its assertions with a per-test unique token so it is unaffected by
 * rows other tests seed into the shared container.
 *
 * @since S5.2 — server pagination for the underwriting list endpoints
 */
@WithMockUser(username = "alice", authorities = {"ROLE_UNDERWRITING_VIEW"})
class PolicyListControllerIT extends UnderwritingWebItSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ fixture helper

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Seeds one policy row with the denormalised list columns. */
    private UUID seedPolicy(String policyNumber, String customerName,
                            PolicyStatus status, String brokerName, String quoteNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, policy_number, status, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "business_type, policy_start_date, policy_end_date, broker_name, quote_number) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, policyNumber, status.name(), UUID.randomUUID(), customerName,
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor", "MOT", "DIRECT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), brokerName, quoteNumber);
        return id;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void paginationMetaIsPopulated() throws Exception {
        String token = uniq();
        for (int i = 0; i < 25; i++) {
            seedPolicy("POL-" + token + "-" + String.format("%02d", i),
                    "Cust " + token, PolicyStatus.ACTIVE, null, null);
        }

        // page 0 — first 10 of the 25 token rows, sorted ascending by number.
        mockMvc.perform(get("/api/v1/policies")
                        .param("q", token)
                        .param("sort", "policyNumber,asc")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThanOrEqualTo(25)))
                .andExpect(jsonPath("$.data[0].policyNumber").value("POL-" + token + "-00"));

        // page 1 — deterministically disjoint from page 0 under the stable sort.
        mockMvc.perform(get("/api/v1/policies")
                        .param("q", token)
                        .param("sort", "policyNumber,asc")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[0].policyNumber").value("POL-" + token + "-10"));
    }

    @Test
    void qNarrowsResults() throws Exception {
        String token = uniq();
        seedPolicy("POL-" + uniq(), "Acme " + token + " Ltd", PolicyStatus.ACTIVE, null, null);
        seedPolicy("POL-" + uniq(), "Unrelated Corp", PolicyStatus.ACTIVE, null, null);

        mockMvc.perform(get("/api/v1/policies").param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].customerName").value("Acme " + token + " Ltd"));
    }

    @Test
    void statusAndQCombine() throws Exception {
        String token = uniq();
        // Same q token, different status — only the ACTIVE one should match both.
        UUID active = seedPolicy("POL-" + uniq(), "Beta " + token, PolicyStatus.ACTIVE, null, null);
        seedPolicy("POL-" + uniq(), "Beta " + token, PolicyStatus.DRAFT, null, null);

        mockMvc.perform(get("/api/v1/policies")
                        .param("status", "ACTIVE")
                        .param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value(active.toString()))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
    }

    @Test
    void sortByPolicyNumberDesc() throws Exception {
        String token = uniq();
        seedPolicy("POL-" + token + "-1", "Sortable " + token, PolicyStatus.ACTIVE, null, null);
        seedPolicy("POL-" + token + "-3", "Sortable " + token, PolicyStatus.ACTIVE, null, null);
        seedPolicy("POL-" + token + "-2", "Sortable " + token, PolicyStatus.ACTIVE, null, null);

        mockMvc.perform(get("/api/v1/policies")
                        .param("q", token)
                        .param("sort", "policyNumber,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].policyNumber").value("POL-" + token + "-3"));
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"ROLE_CLAIMS_VIEW"})
    void forbiddenWithoutUnderwritingViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/policies"))
                .andExpect(status().isForbidden());
    }
}
