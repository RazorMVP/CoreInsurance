package com.nubeero.cia.api.finance.audit;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.storage.DocumentStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins {@code GET /api/v1/finance/pdf-downloads} + the downloadPdf side-effect
 * write integration (downloadPdf writes a pdf_download_log row).
 *
 * @since F11 — Task 7
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE"})
class PdfDownloadLogControllerIT extends FinanceWebItSupport {

    @Autowired MockMvc               mockMvc;
    @Autowired ReceiptService        receiptService;
    @Autowired JdbcTemplate          jdbc;
    @Autowired DocumentStorageService storage;

    @BeforeEach
    void setUpFiscalPeriod() {
        UUID fyId     = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        jdbc.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', 'test') ON CONFLICT (name) DO NOTHING",
            fyId, "FY-IT-" + today.getYear(),
            LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear(), 12, 31));
        UUID resolvedFyId = jdbc.queryForObject(
            "SELECT id FROM fiscal_year WHERE name = ?", UUID.class, "FY-IT-" + today.getYear());
        jdbc.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, 'MONTH', ?, ?, 'OPEN', 'test') ON CONFLICT DO NOTHING",
            periodId, resolvedFyId, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET pdf-downloads returns today's entries scoped to JWT user")
    void getRecent_returnsTodaysEntries() throws Exception {
        jdbc.update(
            "INSERT INTO pdf_download_log " +
            "  (id, user_id, entity_type, entity_id, reference, downloaded_at, created_by) " +
            "VALUES (?, 'alice', 'RECEIPT', ?, ?, NOW(), 'alice')",
            UUID.randomUUID(), UUID.randomUUID(), "REC-IT-001");

        mockMvc.perform(get("/api/v1/finance/pdf-downloads"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.reference == 'REC-IT-001')]").exists());
    }

    @Test
    @DisplayName("GET pdf-downloads with days=7 returns last week's rows; days=1 excludes them")
    void getRecent_days7() throws Exception {
        jdbc.update(
            "INSERT INTO pdf_download_log " +
            "  (id, user_id, entity_type, entity_id, reference, downloaded_at, created_by) " +
            "VALUES (?, 'alice', 'PAYMENT', ?, ?, NOW() - INTERVAL '3 days', 'alice')",
            UUID.randomUUID(), UUID.randomUUID(), "PAY-IT-OLD");

        mockMvc.perform(get("/api/v1/finance/pdf-downloads?days=7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.reference == 'PAY-IT-OLD')]").exists());

        mockMvc.perform(get("/api/v1/finance/pdf-downloads?days=1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.reference == 'PAY-IT-OLD')]").doesNotExist());
    }

    @Test
    @DisplayName("GET /pdf writes a pdf_download_log row (side-effect of download)")
    void downloadPdf_writesLogRow() throws Exception {
        UUID customerId = seedCustomerWithEmail("download@test.local");
        UUID dnId       = createDebitNote(customerId);
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-log");
        org.assertj.core.api.Assertions.assertThat(r.getPdfPath()).isNotNull();

        Mockito.doAnswer(inv -> new ByteArrayInputStream("%PDF-1.4 mock".getBytes()))
            .when(storage).download(Mockito.any(), Mockito.any());

        mockMvc.perform(get("/api/v1/debit-notes/{dnId}/receipts/{id}/pdf", dnId, r.getId()))
            .andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pdf_download_log WHERE entity_id = ? AND entity_type = 'RECEIPT'",
            Integer.class, r.getId());
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"CLAIMS_VIEW"})
    @DisplayName("GET pdf-downloads returns 403 without FINANCE_VIEW")
    void getRecent_403_withoutFinanceView() throws Exception {
        mockMvc.perform(get("/api/v1/finance/pdf-downloads"))
            .andExpect(status().isForbidden());
    }

    private UUID seedCustomerWithEmail(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, email, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", email);
        return id;
    }

    private UUID createDebitNote(UUID customerId) {
        UUID dnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO debit_notes " +
            "  (id, debit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   customer_id, customer_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'POLICY', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            dnId, "DN-LOG-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(), "POL-LOG-001",
            customerId, "Log Test Customer", "Premium",
            new BigDecimal("500000.00"), new BigDecimal("500000.00"));
        return dnId;
    }
}
