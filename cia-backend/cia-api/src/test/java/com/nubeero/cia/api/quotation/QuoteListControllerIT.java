package com.nubeero.cia.api.quotation;

import com.nubeero.cia.api.underwriting.UnderwritingWebItSupport;
import com.nubeero.cia.quotation.QuoteStatus;
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
 * Controller-slice IT for {@code GET /api/v1/quotes} (S5.2 server pagination).
 *
 * <p>Mirrors {@link com.nubeero.cia.api.policy.PolicyListControllerIT}. The
 * quote list gates on {@code @PreAuthorize("hasRole('QUOTATION_VIEW')")}, so the
 * mock authorities carry the {@code ROLE_} prefix. Rows are seeded via
 * {@link JdbcTemplate}; per-test unique tokens isolate assertions in the shared
 * (non-transactional) container.
 *
 * @since S5.2 — server pagination for the underwriting list endpoints
 */
@WithMockUser(username = "alice", authorities = {"ROLE_QUOTATION_VIEW"})
class QuoteListControllerIT extends UnderwritingWebItSupport {

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

    /** Seeds one quote row with the denormalised list columns. */
    private UUID seedQuote(String quoteNumber, String customerName, QuoteStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO quotes (id, quote_number, status, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, policy_start_date, policy_end_date) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, quoteNumber, status.name(), UUID.randomUUID(), customerName,
            UUID.randomUUID(), "Motor Comprehensive", "MOTOR", new BigDecimal("5.0000"),
            UUID.randomUUID(), "Motor", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        return id;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void paginationMetaIsPopulated() throws Exception {
        String token = uniq();
        for (int i = 0; i < 25; i++) {
            seedQuote("QT-" + token + "-" + String.format("%02d", i),
                    "Cust " + token, QuoteStatus.DRAFT);
        }

        mockMvc.perform(get("/api/v1/quotes")
                        .param("q", token)
                        .param("sort", "quoteNumber,asc")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.meta.total", greaterThanOrEqualTo(25)))
                .andExpect(jsonPath("$.data[0].quoteNumber").value("QT-" + token + "-00"));

        mockMvc.perform(get("/api/v1/quotes")
                        .param("q", token)
                        .param("sort", "quoteNumber,asc")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[0].quoteNumber").value("QT-" + token + "-10"));
    }

    @Test
    void qNarrowsResults() throws Exception {
        String token = uniq();
        seedQuote("QT-" + uniq(), "Acme " + token + " Ltd", QuoteStatus.DRAFT);
        seedQuote("QT-" + uniq(), "Unrelated Corp", QuoteStatus.DRAFT);

        mockMvc.perform(get("/api/v1/quotes").param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].customerName").value("Acme " + token + " Ltd"));
    }

    @Test
    void statusAndQCombine() throws Exception {
        String token = uniq();
        UUID approved = seedQuote("QT-" + uniq(), "Beta " + token, QuoteStatus.APPROVED);
        seedQuote("QT-" + uniq(), "Beta " + token, QuoteStatus.DRAFT);

        mockMvc.perform(get("/api/v1/quotes")
                        .param("status", "APPROVED")
                        .param("q", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value(approved.toString()))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"));
    }

    @Test
    void sortByQuoteNumberDesc() throws Exception {
        String token = uniq();
        seedQuote("QT-" + token + "-1", "Sortable " + token, QuoteStatus.DRAFT);
        seedQuote("QT-" + token + "-3", "Sortable " + token, QuoteStatus.DRAFT);
        seedQuote("QT-" + token + "-2", "Sortable " + token, QuoteStatus.DRAFT);

        mockMvc.perform(get("/api/v1/quotes")
                        .param("q", token)
                        .param("sort", "quoteNumber,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].quoteNumber").value("QT-" + token + "-3"));
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"ROLE_CLAIMS_VIEW"})
    void forbiddenWithoutQuotationViewRole() throws Exception {
        mockMvc.perform(get("/api/v1/quotes"))
                .andExpect(status().isForbidden());
    }
}
