package com.nubeero.cia.api.endorsement;

import com.nubeero.cia.api.underwriting.UnderwritingWebItSupport;
import com.nubeero.cia.endorsement.EndorsementStatus;
import com.nubeero.cia.endorsement.EndorsementType;
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
 * Controller-slice IT for {@code GET /api/v1/endorsements} (S5.2 server
 * pagination — greenfield {@code q} + {@code endorsementType} filters).
 *
 * <p>Mirrors {@link com.nubeero.cia.api.policy.PolicyListControllerIT}. The list
 * gates on {@code @PreAuthorize("hasRole('UNDERWRITING_VIEW')")}, so the mock
 * authorities carry the {@code ROLE_} prefix. Each endorsement row's
 * {@code policy_id} is an enforced FK to {@code policies}, so every test first
 * seeds a parent policy via {@link JdbcTemplate}. Per-test unique tokens isolate
 * assertions in the shared (non-transactional) container.
 *
 * @since S5.2 — server pagination for the underwriting list endpoints
 */
@WithMockUser(username = "alice", authorities = {"ROLE_UNDERWRITING_VIEW"})
class EndorsementListControllerIT extends UnderwritingWebItSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ fixture helpers

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Seeds a parent policy — the endorsements.policy_id FK requires one. */
    private UUID seedPolicy() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies (id, policy_number, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "business_type, policy_start_date, policy_end_date) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, "POL-" + uniq(), UUID.randomUUID(), "Parent Customer",
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor", "MOT", "DIRECT",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        return id;
    }

    /** Seeds one endorsement row with the denormalised list columns. */
    private UUID seedEndorsement(UUID policyId, String endorsementNumber, String customerName,
                                 EndorsementStatus status, EndorsementType type) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO endorsements (id, endorsement_number, status, endorsement_type, "
                + "policy_id, policy_number, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, "
                + "effective_date, policy_end_date, description) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, endorsementNumber, status.name(), type.name(),
            policyId, "POL-ENDT", UUID.randomUUID(), customerName,
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor",
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31), "Test endorsement");
        return id;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void paginationMetaIsPopulated() throws Exception {
        UUID policyId = seedPolicy();
        String token = uniq();
        for (int i = 0; i < 25; i++) {
            seedEndorsement(policyId, "END-" + token + "-" + String.format("%02d", i),
                    "Cust " + token, EndorsementStatus.DRAFT, EndorsementType.NON_PREMIUM_BEARING);
        }

        mockMvc.perform(get("/api/v1/endorsements")
                        .param("q", token)
                        .param("sort", "endorsementNumber,asc")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThanOrEqualTo(25)))
                .andExpect(jsonPath("$.data[0].endorsementNumber").value("END-" + token + "-00"));

        mockMvc.perform(get("/api/v1/endorsements")
                        .param("q", token)
                        .param("sort", "endorsementNumber,asc")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[0].endorsementNumber").value("END-" + token + "-10"));
    }

    @Test
    void qNarrowsResults() throws Exception {
        UUID policyId = seedPolicy();
        String token = uniq();
        seedEndorsement(policyId, "END-" + uniq(), "Acme " + token + " Ltd",
                EndorsementStatus.DRAFT, EndorsementType.ADDITIONAL_PREMIUM);
        seedEndorsement(policyId, "END-" + uniq(), "Unrelated Corp",
                EndorsementStatus.DRAFT, EndorsementType.ADDITIONAL_PREMIUM);

        mockMvc.perform(get("/api/v1/endorsements").param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].customerName").value("Acme " + token + " Ltd"));
    }

    @Test
    void endorsementTypeAndStatusCombine() throws Exception {
        UUID policyId = seedPolicy();
        String token = uniq();
        // Same q token — distinguish only by type + status.
        UUID target = seedEndorsement(policyId, "END-" + uniq(), "Beta " + token,
                EndorsementStatus.APPROVED, EndorsementType.ADDITIONAL_PREMIUM);
        seedEndorsement(policyId, "END-" + uniq(), "Beta " + token,
                EndorsementStatus.APPROVED, EndorsementType.RETURN_PREMIUM);
        seedEndorsement(policyId, "END-" + uniq(), "Beta " + token,
                EndorsementStatus.DRAFT, EndorsementType.ADDITIONAL_PREMIUM);

        mockMvc.perform(get("/api/v1/endorsements")
                        .param("q", token)
                        .param("status", "APPROVED")
                        .param("endorsementType", "ADDITIONAL_PREMIUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value(target.toString()))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].endorsementType").value("ADDITIONAL_PREMIUM"));
    }

    @Test
    void sortByEndorsementNumberDesc() throws Exception {
        UUID policyId = seedPolicy();
        String token = uniq();
        seedEndorsement(policyId, "END-" + token + "-1", "Sortable " + token,
                EndorsementStatus.DRAFT, EndorsementType.NON_PREMIUM_BEARING);
        seedEndorsement(policyId, "END-" + token + "-3", "Sortable " + token,
                EndorsementStatus.DRAFT, EndorsementType.NON_PREMIUM_BEARING);
        seedEndorsement(policyId, "END-" + token + "-2", "Sortable " + token,
                EndorsementStatus.DRAFT, EndorsementType.NON_PREMIUM_BEARING);

        mockMvc.perform(get("/api/v1/endorsements")
                        .param("q", token)
                        .param("sort", "endorsementNumber,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].endorsementNumber").value("END-" + token + "-3"));
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"ROLE_CLAIMS_VIEW"})
    void forbiddenWithoutUnderwritingViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/endorsements"))
                .andExpect(status().isForbidden());
    }
}
