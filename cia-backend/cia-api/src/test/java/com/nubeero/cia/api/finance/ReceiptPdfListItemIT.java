package com.nubeero.cia.api.finance;

import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that after {@code ReceiptService.post()} the database row has
 * {@code pdf_path} set AND the GET /api/v1/receipts list item exposes it
 * for the frontend.
 *
 * <p>End-to-end contract: synchronous PDF generation on post() →
 * DocumentStorageService.upload() → pdf_path persisted → projected into
 * the list-item response.
 *
 * @since Slice β — Task 11, F7 receipt PDF generation
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class ReceiptPdfListItemIT extends FinanceWebItSupport {

    @Autowired MockMvc        mockMvc;
    @Autowired ReceiptService receiptService;
    @Autowired JdbcTemplate   jdbc;

    @BeforeEach
    void setUpFiscalPeriod() {
        // Verbatim copy from ReceiptListControllerIT.setUpFiscalPeriod()
        UUID fyId     = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        LocalDate yearStart = LocalDate.of(today.getYear(), 1, 1);
        LocalDate yearEnd   = LocalDate.of(today.getYear(), 12, 31);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd   = today.withDayOfMonth(today.lengthOfMonth());

        jdbc.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (name) DO NOTHING",
            fyId, "FY-IT-" + today.getYear(), yearStart, yearEnd, "ACTIVE", "test");

        UUID resolvedFyId = jdbc.queryForObject(
            "SELECT id FROM fiscal_year WHERE name = ?",
            UUID.class, "FY-IT-" + today.getYear());

        jdbc.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            periodId, resolvedFyId, "MONTH", monthStart, monthEnd, "OPEN", "test");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("post() populates pdf_path in DB and surfaces it via GET /api/v1/receipts")
    void postReceipt_populatesPdfPathInDbAndApi() throws Exception {
        UUID dnId = createDebitNote();
        Receipt posted = receiptService.post(
            dnId, new BigDecimal("100000.00"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", null, "IT");

        String pdfPath = jdbc.queryForObject(
            "SELECT pdf_path FROM receipts WHERE id = ?", String.class, posted.getId());

        assertThat(pdfPath)
            .as("pdf_path should be populated by ReceiptService.post()")
            .isNotNull()
            .startsWith("receipts/")
            .endsWith(".pdf");

        mockMvc.perform(get("/api/v1/receipts").param("debitNoteId", dnId.toString()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.data[0].pdfPath").value(pdfPath));
    }

    @Test
    @DisplayName("pdf_path matches the receipts/{yyyy}/{MM}/{id}.pdf format")
    void postReceipt_pdfPathFollowsExpectedFormat() {
        UUID dnId = createDebitNote();
        Receipt posted = receiptService.post(
            dnId, new BigDecimal("50000.00"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT");

        assertThat(posted.getPdfPath())
            .matches("^receipts/\\d{4}/\\d{2}/" + posted.getId() + "\\.pdf$");
    }

    private UUID createDebitNote() {
        // Verbatim copy from ReceiptListControllerIT.createDebitNote()
        UUID dnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO debit_notes " +
            "  (id, debit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   customer_id, customer_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'POLICY', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            dnId, "DN-PDF-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(), "POL-PDF-001",
            UUID.randomUUID(), "PDF Test Customer", "Premium",
            new BigDecimal("500000.00"), new BigDecimal("500000.00")
        );
        return dnId;
    }
}
