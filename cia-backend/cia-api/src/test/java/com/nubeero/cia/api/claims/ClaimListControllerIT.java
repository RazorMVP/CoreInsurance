package com.nubeero.cia.api.claims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.underwriting.UnderwritingWebItSupport;
import com.nubeero.cia.claims.ClaimStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-slice IT for {@code GET /api/v1/claims} + {@code /claims/stats}
 * (S5.3 server pagination).
 *
 * <p>Extends {@link UnderwritingWebItSupport} ({@code @SpringBootTest +
 * @AutoConfigureMockMvc}) so the full Spring Security filter chain and
 * {@code @PreAuthorize("hasRole('CLAIMS_VIEW')")} are active; the mock
 * authorities carry the {@code ROLE_} prefix.
 *
 * <p>Claims carry a {@code policy_id} FK to {@code policies(id)}, so each test
 * seeds one parent policy and points its claim rows at it. All other snapshot
 * columns (customer/product/class ids) are plain UUIDs with no enforced FK.
 * Because {@code @SpringBootTest} tests are not transactional, seeded rows
 * persist across methods in the shared container; every test isolates its
 * assertions with a per-test unique token, and {@code stats()} — which
 * aggregates globally — is asserted as a before/after delta.
 *
 * @since S5.3 — server pagination for Claims / Customers / Audit
 */
@WithMockUser(username = "alice", authorities = {"ROLE_CLAIMS_VIEW"})
class ClaimListControllerIT extends UnderwritingWebItSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ fixture helpers

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Seeds one parent policy (satisfies the claims.policy_id FK) and returns its id. */
    private UUID seedParentPolicy() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, policy_number, status, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "business_type, policy_start_date, policy_end_date) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, "POL-PARENT-" + uniq(), "ACTIVE", UUID.randomUUID(), "Parent Cust",
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor", "MOT", "DIRECT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        return id;
    }

    /** Seeds one claim row with the denormalised list columns. */
    private UUID seedClaim(UUID policyId, String claimNumber, String customerName,
                           String policyNumber, ClaimStatus status,
                           BigDecimal reserve, BigDecimal approved) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO claims (id, claim_number, status, "
                + "policy_id, policy_number, policy_start_date, policy_end_date, "
                + "customer_id, customer_name, "
                + "product_id, product_name, class_of_business_id, class_of_business_name, "
                + "incident_date, reported_date, description, "
                + "reserve_amount, approved_amount, currency_code, created_by) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, claimNumber, status.name(),
            policyId, policyNumber, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            UUID.randomUUID(), customerName,
            UUID.randomUUID(), "Motor Comprehensive", UUID.randomUUID(), "Motor",
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2), "Test loss",
            reserve, approved, "NGN", "test");
        return id;
    }

    private record Stats(long open, BigDecimal reserve, BigDecimal approved) {}

    private Stats fetchStats() throws Exception {
        String body = mockMvc.perform(get("/api/v1/claims/stats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        return new Stats(
                data.get("openCount").asLong(),
                data.get("totalReserve").decimalValue(),
                data.get("totalApproved").decimalValue());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void paginationMetaIsPopulated() throws Exception {
        String token = uniq();
        UUID policyId = seedParentPolicy();
        for (int i = 0; i < 25; i++) {
            seedClaim(policyId, "CLM-" + token + "-" + String.format("%02d", i),
                    "Cust " + token, "POL-" + token, ClaimStatus.REGISTERED,
                    new BigDecimal("100.00"), null);
        }

        mockMvc.perform(get("/api/v1/claims")
                        .param("q", token)
                        .param("sort", "claimNumber,asc")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThanOrEqualTo(25)))
                .andExpect(jsonPath("$.data[0].claimNumber").value("CLM-" + token + "-00"));

        mockMvc.perform(get("/api/v1/claims")
                        .param("q", token)
                        .param("sort", "claimNumber,asc")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[0].claimNumber").value("CLM-" + token + "-10"));
    }

    @Test
    void qNarrowsResults() throws Exception {
        UUID policyId = seedParentPolicy();
        String token = uniq();
        seedClaim(policyId, "CLM-" + uniq(), "Acme " + token + " Ltd", "POL-1",
                ClaimStatus.REGISTERED, new BigDecimal("100.00"), null);
        seedClaim(policyId, "CLM-" + uniq(), "Unrelated Corp", "POL-2",
                ClaimStatus.REGISTERED, new BigDecimal("100.00"), null);

        mockMvc.perform(get("/api/v1/claims").param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].customerName").value("Acme " + token + " Ltd"));
    }

    @Test
    void statusAndQCombine() throws Exception {
        UUID policyId = seedParentPolicy();
        String token = uniq();
        // Same q token, different status — only the APPROVED one should match both.
        UUID approved = seedClaim(policyId, "CLM-" + uniq(), "Beta " + token, "POL-A",
                ClaimStatus.APPROVED, new BigDecimal("100.00"), new BigDecimal("50.00"));
        seedClaim(policyId, "CLM-" + uniq(), "Beta " + token, "POL-B",
                ClaimStatus.REGISTERED, new BigDecimal("100.00"), null);

        mockMvc.perform(get("/api/v1/claims")
                        .param("status", "APPROVED")
                        .param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value(approved.toString()))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"));
    }

    @Test
    void sortByClaimNumberDesc() throws Exception {
        UUID policyId = seedParentPolicy();
        String token = uniq();
        seedClaim(policyId, "CLM-" + token + "-1", "Sortable " + token, "POL-S",
                ClaimStatus.REGISTERED, new BigDecimal("100.00"), null);
        seedClaim(policyId, "CLM-" + token + "-3", "Sortable " + token, "POL-S",
                ClaimStatus.REGISTERED, new BigDecimal("100.00"), null);
        seedClaim(policyId, "CLM-" + token + "-2", "Sortable " + token, "POL-S",
                ClaimStatus.REGISTERED, new BigDecimal("100.00"), null);

        mockMvc.perform(get("/api/v1/claims")
                        .param("q", token)
                        .param("sort", "claimNumber,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].claimNumber").value("CLM-" + token + "-3"));
    }

    @Test
    void statsAggregatesOpenReserveAndApproved() throws Exception {
        Stats before = fetchStats();

        UUID policyId = seedParentPolicy();
        String token = uniq();
        // 20 open (REGISTERED) + 3 SETTLED + 2 WITHDRAWN = 25 rows.
        // reserve 100.00 each → +2500.00; approved 500.00 on the 3 SETTLED → +1500.00.
        for (int i = 0; i < 20; i++) {
            seedClaim(policyId, "CLM-" + token + "-O" + i, "Open " + token, "POL-O",
                    ClaimStatus.REGISTERED, new BigDecimal("100.00"), null);
        }
        for (int i = 0; i < 3; i++) {
            seedClaim(policyId, "CLM-" + token + "-S" + i, "Settled " + token, "POL-S",
                    ClaimStatus.SETTLED, new BigDecimal("100.00"), new BigDecimal("500.00"));
        }
        for (int i = 0; i < 2; i++) {
            seedClaim(policyId, "CLM-" + token + "-W" + i, "Withdrawn " + token, "POL-W",
                    ClaimStatus.WITHDRAWN, new BigDecimal("100.00"), null);
        }

        Stats after = fetchStats();

        // SETTLED + WITHDRAWN excluded from openCount.
        assertThat(after.open() - before.open()).isEqualTo(20L);
        // Reserve summed across ALL non-deleted (25 × 100).
        assertThat(after.reserve().subtract(before.reserve())).isEqualByComparingTo("2500.00");
        // Approved summed (3 × 500).
        assertThat(after.approved().subtract(before.approved())).isEqualByComparingTo("1500.00");
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"ROLE_UNDERWRITING_VIEW"})
    void forbiddenWithoutClaimsViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/claims"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"ROLE_UNDERWRITING_VIEW"})
    void statsForbiddenWithoutClaimsViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/claims/stats"))
                .andExpect(status().isForbidden());
    }
}
