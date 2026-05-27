package com.nubeero.cia.api.finance.email;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.finance.email.SendReceiptEmailWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins {@code POST /api/v1/debit-notes/{dnId}/receipts/{id}/email} behaviour:
 * <ul>
 *   <li>202 Accepted on happy path; body carries {@code workflowId}.</li>
 *   <li>422 with {@code RECEIPT_PDF_UNAVAILABLE} when {@code pdf_path} is null.</li>
 *   <li>422 with {@code RECEIPT_RECIPIENT_UNRESOLVED} when the customer
 *       row has a null/blank email.</li>
 *   <li>403 when the caller lacks {@code FINANCE_UPDATE}.</li>
 * </ul>
 *
 * <p>The {@code @MockBean WorkflowClient} declared on {@link FinanceWebItSupport}
 * is implicitly used here — {@code requestEmail()} calls
 * {@code workflowClient.newWorkflowStub(...)} which returns a null proxy on the
 * default mock and {@code WorkflowClient.start(...)} silently no-ops. We
 * don't verify the workflow start; the preflight 202/422 routing is the
 * contract these tests pin.
 *
 * @since Slice γ — Task 25, F7 email transmission
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class ReceiptControllerEmailIT extends FinanceWebItSupport {

    @Autowired MockMvc        mockMvc;
    @Autowired ReceiptService receiptService;
    @Autowired JdbcTemplate   jdbc;
    @Autowired WorkflowClient workflowClient; // mocked by FinanceWebItSupport

    /**
     * The @MockBean WorkflowClient on FinanceWebItSupport returns null from
     * newWorkflowStub() by default. ReceiptService.requestEmail() invokes
     * WorkflowClient.start(workflow::send, ...) which would NPE on a null
     * stub, surfacing as 500 from the controller. Stub the call to return
     * a Mockito-mocked SendReceiptEmailWorkflow so the start path is a
     * harmless no-op during ITs.
     */
    @BeforeEach
    void stubWorkflowStub() {
        SendReceiptEmailWorkflow workflowStub = mock(SendReceiptEmailWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(SendReceiptEmailWorkflow.class),
                                              any(WorkflowOptions.class)))
            .thenReturn(workflowStub);
    }

    @BeforeEach
    void setUpFiscalPeriod() {
        UUID fyId     = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        LocalDate today      = LocalDate.now();
        LocalDate yearStart  = LocalDate.of(today.getYear(), 1, 1);
        LocalDate yearEnd    = LocalDate.of(today.getYear(), 12, 31);
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
    @DisplayName("POST /email returns 202 with workflowId on happy path")
    void requestEmail_202_happyPath() throws Exception {
        UUID customerId = seedCustomerWithEmail("happy@receipt.local");
        UUID dnId       = createDebitNote(customerId);
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT");
        org.assertj.core.api.Assertions.assertThat(r.getPdfPath()).isNotNull();

        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/email", dnId, r.getId()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.workflowId",
                                  startsWith("send-receipt-email-")));
    }

    @Test
    @DisplayName("POST /email returns 422 RECEIPT_PDF_UNAVAILABLE when pdf_path is null")
    void requestEmail_422_pdfUnavailable() throws Exception {
        UUID customerId = seedCustomerWithEmail("nopdf@receipt.local");
        UUID dnId       = createDebitNote(customerId);
        UUID receiptId  = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, receipt_number, debit_note_id, amount, payment_date, " +
            "                       payment_method, status, created_by) " +
            "VALUES (?, ?, ?, ?, CURRENT_DATE, 'CASH', 'POSTED', 'test')",
            receiptId,
            "REC-NOPDF-" + receiptId.toString().substring(0, 6),
            dnId,
            new BigDecimal("75000"));

        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/email", dnId, receiptId))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors[0].code", containsString("RECEIPT_PDF_UNAVAILABLE")));
    }

    @Test
    @DisplayName("POST /email returns 422 RECEIPT_RECIPIENT_UNRESOLVED when customer has no email")
    void requestEmail_422_recipientUnresolved() throws Exception {
        UUID customerId = seedCustomerWithoutEmail();
        UUID dnId       = createDebitNote(customerId);
        Receipt r = receiptService.post(
            dnId, new BigDecimal("50000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT");

        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/email", dnId, r.getId()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors[0].code", containsString("RECEIPT_RECIPIENT_UNRESOLVED")));
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"FINANCE_VIEW"})
    @DisplayName("POST /email returns 403 when caller lacks FINANCE_UPDATE")
    void requestEmail_403_withoutFinanceUpdate() throws Exception {
        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/email",
                                UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isForbidden());
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private UUID seedCustomerWithEmail(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, email, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", email);
        return id;
    }

    private UUID seedCustomerWithoutEmail() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, 'test')",
            id, "NoEmail", "Customer");
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
            dnId,
            "DN-EMAIL-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(),
            "POL-EMAIL-001",
            customerId,
            "Email Test Customer",
            "Email Premium Test",
            new BigDecimal("500000.00"),
            new BigDecimal("500000.00"));
        return dnId;
    }
}
