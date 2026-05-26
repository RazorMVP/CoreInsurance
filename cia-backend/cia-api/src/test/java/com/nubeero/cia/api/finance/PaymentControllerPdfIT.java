package com.nubeero.cia.api.finance;

import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins {@code GET /api/v1/credit-notes/{cnId}/payments/{id}/pdf} behaviour:
 * <ul>
 *   <li>Happy path returns 200 + application/pdf + Content-Disposition: attachment.</li>
 *   <li>404 when {@code payment.pdfPath IS NULL}.</li>
 *   <li>404 when payment id is unknown.</li>
 *   <li>403 when caller lacks FINANCE_VIEW.</li>
 *   <li>The MinIO object path passed to storage.download matches the one in pdf_path.</li>
 * </ul>
 *
 * @since Slice β — Task 15, F7 payment voucher PDF generation
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class PaymentControllerPdfIT extends FinanceWebItSupport {

    @Autowired MockMvc        mockMvc;
    @Autowired PaymentService paymentService;
    @Autowired JdbcTemplate   jdbc;
    // Reuse the @MockBean DocumentStorageService declared on FinanceWebItSupport
    // (same package, package-private field). Redeclaring @MockBean here is
    // rejected as a duplicate definition.

    @BeforeEach
    void setUpFiscalPeriod() {
        // Verbatim copy from PaymentListControllerIT.setUpFiscalPeriod()
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

    /**
     * Stubs {@code documentStorageService.download()} to return a minimal valid
     * PDF byte stream. Called only from happy-path tests; 404/403 paths return
     * before {@code download()} fires.
     *
     * <p>{@code any()} (not {@code anyString()}) is required because
     * {@code TenantContext.getTenantId()} returns {@code null} in this
     * controller-IT context — there's no JWT and {@code @WithMockUser} doesn't
     * carry a tenant claim, so the {@code TenantContextFilter} never sets a
     * value. {@code anyString()} refuses to match null, which would silently
     * fall through to the default {@code null} return and trip
     * {@code InputStreamResource}'s not-null assert.
     */
    private void stubDownloadToReturnPdfBytes() {
        Mockito.doAnswer(inv -> new ByteArrayInputStream("%PDF-1.4 mock content".getBytes()))
            .when(documentStorageService).download(Mockito.any(), Mockito.any());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /pdf returns 200 + application/pdf + Content-Disposition attachment + non-empty body")
    void downloadPdf_streamsBytes() throws Exception {
        UUID cnId = createCreditNote();
        Payment p = paymentService.post(
            cnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank",
            "John Doe", "0123456789", "IT");
        assertThat(p.getPdfPath()).isNotNull();
        stubDownloadToReturnPdfBytes();

        MvcResult res = mockMvc.perform(get("/api/v1/credit-notes/{cnId}/payments/{id}/pdf",
                                              cnId, p.getId()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                                        org.hamcrest.Matchers.containsString("PAY-")))
            .andReturn();

        byte[] body = res.getResponse().getContentAsByteArray();
        assertThat(body).isNotEmpty();
        assertThat(new String(body, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @DisplayName("GET /pdf returns 404 when payment's pdf_path is null")
    void downloadPdf_404_whenPdfPathNull() throws Exception {
        UUID cnId = createCreditNote();
        UUID paymentId = UUID.randomUUID();
        // INSERT payment directly via JDBC with pdf_path NULL
        jdbc.update(
            "INSERT INTO payments (id, payment_number, credit_note_id, amount, payment_date, " +
            "                       payment_method, status, created_by) " +
            "VALUES (?, ?, ?, ?, CURRENT_DATE, 'CASH', 'POSTED', 'test')",
            paymentId, "PAY-NULL-" + paymentId.toString().substring(0, 6),
            cnId, new BigDecimal("100000")
        );
        mockMvc.perform(get("/api/v1/credit-notes/{cnId}/payments/{id}/pdf", cnId, paymentId))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"CLAIMS_VIEW"})
    @DisplayName("GET /pdf returns 403 without FINANCE_VIEW")
    void downloadPdf_403_withoutFinanceView() throws Exception {
        mockMvc.perform(get("/api/v1/credit-notes/{cnId}/payments/{id}/pdf",
                              UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /pdf returns 404 when payment does not exist")
    void downloadPdf_404_whenPaymentDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/credit-notes/{cnId}/payments/{id}/pdf",
                              UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("storage.download() is invoked with the payment's pdf_path on happy path")
    void downloadPdf_callsStorageWithExpectedPath() throws Exception {
        UUID cnId = createCreditNote();
        Payment p = paymentService.post(
            cnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank",
            "John Doe", "0123456789", "IT");
        stubDownloadToReturnPdfBytes();

        mockMvc.perform(get("/api/v1/credit-notes/{cnId}/payments/{id}/pdf", cnId, p.getId()))
            .andExpect(status().isOk());

        Mockito.verify(documentStorageService).download(Mockito.any(),
                                                          Mockito.eq(p.getPdfPath()));
    }

    private UUID createCreditNote() {
        // Verbatim copy from PaymentListControllerIT.createCreditNote()
        UUID cnId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'CLAIM', ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-PDF-" + cnId.toString().substring(0, 8),
            entityId,
            "CLM-PDF-" + cnId.toString().substring(0, 4),
            "PDF Test Beneficiary",
            "Claim settlement",
            new BigDecimal("500000.00"),
            new BigDecimal("500000.00")
        );
        return cnId;
    }
}
