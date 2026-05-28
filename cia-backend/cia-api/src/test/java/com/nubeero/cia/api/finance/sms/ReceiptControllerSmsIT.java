package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow;
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

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins {@code POST /api/v1/debit-notes/{dnId}/receipts/{id}/sms} behaviour:
 * <ul>
 *   <li>202 Accepted on happy path; body carries {@code workflowId} prefixed
 *       {@code send-receipt-sms-}.</li>
 *   <li>422 with {@code RECEIPT_RECIPIENT_PHONE_UNRESOLVED} when the customer
 *       row has a null phone number.</li>
 *   <li>404 when the receipt id is unknown (ResourceNotFoundException →
 *       RESOURCE_NOT_FOUND).</li>
 *   <li>403 when the caller lacks {@code FINANCE_UPDATE}.</li>
 * </ul>
 *
 * <p>SMS has <em>no PDF gate</em> — unlike the email path, {@code requestSms()}
 * does not check {@code pdfPath}. The only preflight is {@code customers.phone != null}.
 *
 * <p>The {@link FinanceWebItSupport#workflowClient} {@code @MockBean} is stubbed
 * in {@link #stubWorkflowStub()} so that {@code WorkflowClient.start(workflow::send, ...)}
 * inside {@code ReceiptService.requestSms()} is a harmless no-op during ITs
 * rather than an NPE on the default Mockito null proxy.
 *
 * @since Task 9.4 — F7-δ + R7 SMS controller IT
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class ReceiptControllerSmsIT extends FinanceWebItSupport {

    @Autowired MockMvc        mockMvc;
    @Autowired ReceiptService receiptService;
    @Autowired JdbcTemplate   jdbc;
    @Autowired WorkflowClient workflowClient; // mocked by FinanceWebItSupport

    /**
     * The @MockBean WorkflowClient on FinanceWebItSupport returns null from
     * newWorkflowStub() by default. ReceiptService.requestSms() invokes
     * WorkflowClient.start(workflow::send, ...) which would NPE on a null
     * stub, surfacing as 500 from the controller. Stub the call to return
     * a Mockito-mocked SendReceiptSmsWorkflow so the start path is a
     * harmless no-op during ITs.
     */
    @BeforeEach
    void stubWorkflowStub() {
        SendReceiptSmsWorkflow workflowStub = mock(SendReceiptSmsWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(SendReceiptSmsWorkflow.class),
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
    @DisplayName("POST /sms returns 202 with workflowId on happy path")
    void requestSms_202_happyPath() throws Exception {
        UUID customerId = seedCustomerWithPhone("+2348012345678");
        UUID dnId       = createDebitNote(customerId);
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT");

        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms", dnId, r.getId()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.workflowId",
                                  startsWith("send-receipt-sms-")));
    }

    @Test
    @DisplayName("POST /sms returns 422 RECEIPT_RECIPIENT_PHONE_UNRESOLVED when customer has no phone")
    void requestSms_422_phoneUnresolved() throws Exception {
        UUID customerId = seedCustomerWithoutPhone();
        UUID dnId       = createDebitNote(customerId);
        Receipt r = receiptService.post(
            dnId, new BigDecimal("50000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT");

        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms", dnId, r.getId()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors[0].code").value("RECEIPT_RECIPIENT_PHONE_UNRESOLVED"));
    }

    @Test
    @DisplayName("POST /sms returns 404 RESOURCE_NOT_FOUND for unknown receipt id")
    void requestSms_404_unknownReceipt() throws Exception {
        UUID dnId = UUID.randomUUID();
        UUID unknownReceiptId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms",
                                dnId, unknownReceiptId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"FINANCE_VIEW"})
    @DisplayName("POST /sms returns 403 when caller lacks FINANCE_UPDATE")
    void requestSms_403_withoutFinanceUpdate() throws Exception {
        mockMvc.perform(post("/api/v1/debit-notes/{dnId}/receipts/{id}/sms",
                                UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isForbidden());
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private UUID seedCustomerWithPhone(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, phone, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", phone);
        return id;
    }

    private UUID seedCustomerWithoutPhone() {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, 'test')",
            id, "NoPhone", "Customer");
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
            "DN-SMS-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(),
            "POL-SMS-001",
            customerId,
            "SMS Test Customer",
            "SMS Premium Test",
            new BigDecimal("500000.00"),
            new BigDecimal("500000.00"));
        return dnId;
    }
}
