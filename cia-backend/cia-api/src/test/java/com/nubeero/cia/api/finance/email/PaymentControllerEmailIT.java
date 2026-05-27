package com.nubeero.cia.api.finance.email;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.PaymentService;
import com.nubeero.cia.finance.email.SendPaymentVoucherEmailWorkflow;
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
 * Pins {@code POST /api/v1/credit-notes/{cnId}/payments/{id}/email} behaviour:
 * <ul>
 *   <li>202 Accepted on happy path (COMMISSION fixture — Broker.email resolved
 *       via dispatcher); body carries {@code workflowId}.</li>
 *   <li>422 with {@code PAYMENT_PDF_UNAVAILABLE} when {@code pdf_path} is null.</li>
 *   <li>422 with {@code PAYMENT_RECIPIENT_UNRESOLVED} when the credit note's
 *       entity_type is POLICY (no resolver registered → dispatcher returns
 *       {@code Optional.empty()}).</li>
 *   <li>403 when the caller lacks {@code FINANCE_UPDATE}.</li>
 * </ul>
 *
 * <p>Mirrors {@link ReceiptControllerEmailIT} — same {@code @MockBean WorkflowClient}
 * stub pattern (otherwise {@code WorkflowClient.start(workflow::send, ...)} NPEs
 * into a 500). CLAIM + ENDORSEMENT happy paths are deferred to backlog
 * {@code F7-γ-claim-endorsement-payment-ITs} because they need full FK chain
 * fixtures.
 *
 * @since Slice γ — Task 26, F7 email transmission
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class PaymentControllerEmailIT extends FinanceWebItSupport {

    @Autowired MockMvc        mockMvc;
    @Autowired PaymentService paymentService;
    @Autowired JdbcTemplate   jdbc;
    @Autowired WorkflowClient workflowClient;

    @BeforeEach
    void stubWorkflowStub() {
        SendPaymentVoucherEmailWorkflow workflowStub = mock(SendPaymentVoucherEmailWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(SendPaymentVoucherEmailWorkflow.class),
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
    @DisplayName("POST /email returns 202 with workflowId on COMMISSION happy path (Broker.email resolved)")
    void requestEmail_202_happyPathCommission() throws Exception {
        UUID brokerId = seedBrokerWithEmail("broker-happy@test.local");
        UUID cnId     = createCommissionCreditNote(brokerId);
        Payment p = paymentService.post(
            cnId, new BigDecimal("75000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Broker Payee", "0123456789", "IT");
        org.assertj.core.api.Assertions.assertThat(p.getPdfPath()).isNotNull();

        mockMvc.perform(post("/api/v1/credit-notes/{cnId}/payments/{id}/email", cnId, p.getId()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.workflowId",
                                  startsWith("send-payment-voucher-email-")));
    }

    @Test
    @DisplayName("POST /email returns 422 PAYMENT_PDF_UNAVAILABLE when pdf_path is null")
    void requestEmail_422_pdfUnavailable() throws Exception {
        UUID brokerId  = seedBrokerWithEmail("nopdf@test.local");
        UUID cnId      = createCommissionCreditNote(brokerId);
        UUID paymentId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO payments (id, payment_number, credit_note_id, amount, payment_date, " +
            "                       payment_method, status, created_by) " +
            "VALUES (?, ?, ?, ?, CURRENT_DATE, 'CASH', 'POSTED', 'test')",
            paymentId,
            "PAY-NOPDF-" + paymentId.toString().substring(0, 6),
            cnId,
            new BigDecimal("75000"));

        mockMvc.perform(post("/api/v1/credit-notes/{cnId}/payments/{id}/email", cnId, paymentId))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors[0].code", containsString("PAYMENT_PDF_UNAVAILABLE")));
    }

    @Test
    @DisplayName("POST /email returns 422 PAYMENT_RECIPIENT_UNRESOLVED when entity_type=POLICY (no resolver)")
    void requestEmail_422_recipientUnresolved() throws Exception {
        UUID cnId = createPolicyCreditNote();
        Payment p = paymentService.post(
            cnId, new BigDecimal("50000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Unknown", "0", "IT");

        mockMvc.perform(post("/api/v1/credit-notes/{cnId}/payments/{id}/email", cnId, p.getId()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors[0].code", containsString("PAYMENT_RECIPIENT_UNRESOLVED")));
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"FINANCE_VIEW"})
    @DisplayName("POST /email returns 403 when caller lacks FINANCE_UPDATE")
    void requestEmail_403_withoutFinanceUpdate() throws Exception {
        mockMvc.perform(post("/api/v1/credit-notes/{cnId}/payments/{id}/email",
                                UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isForbidden());
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    private UUID seedBrokerWithEmail(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO brokers (id, name, code, email, created_by) VALUES (?, ?, ?, ?, 'test')",
            id, "Broker-" + id.toString().substring(0, 6),
            "BR-" + id.toString().substring(0, 8).toUpperCase(),
            email);
        return id;
    }

    private UUID createCommissionCreditNote(UUID brokerId) {
        UUID cnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_id, beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'COMMISSION', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-COMMISH-" + cnId.toString().substring(0, 8),
            UUID.randomUUID(),
            "COMM-" + cnId.toString().substring(0, 4),
            brokerId,
            "Broker Beneficiary",
            "Broker commission",
            new BigDecimal("75000.00"),
            new BigDecimal("75000.00"));
        return cnId;
    }

    private UUID createPolicyCreditNote() {
        UUID cnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'POLICY', ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-POL-" + cnId.toString().substring(0, 8),
            UUID.randomUUID(),
            "POL-" + cnId.toString().substring(0, 4),
            "Policy Refund Test",
            "Policy-routed CN with no resolver",
            new BigDecimal("50000.00"),
            new BigDecimal("50000.00"));
        return cnId;
    }
}
