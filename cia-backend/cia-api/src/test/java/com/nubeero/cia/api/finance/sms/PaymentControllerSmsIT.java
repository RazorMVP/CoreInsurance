package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.PaymentService;
import com.nubeero.cia.finance.sms.SendPaymentVoucherSmsWorkflow;
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
 * Pins {@code POST /api/v1/credit-notes/{cnId}/payments/{id}/sms} behaviour:
 * <ul>
 *   <li>202 Accepted on happy path (COMMISSION fixture — Broker.phone resolved
 *       via {@code BeneficiaryPhoneResolverDispatcher}); body carries
 *       {@code workflowId} prefixed {@code send-payment-voucher-sms-}.</li>
 *   <li>422 with {@code PAYMENT_RECIPIENT_PHONE_UNRESOLVED} when the credit note's
 *       entity_type is POLICY (no phone resolver registered → dispatcher returns
 *       {@code Optional.empty()}).</li>
 *   <li>404 with {@code RESOURCE_NOT_FOUND} when the payment id is unknown
 *       ({@code PaymentService.findOrThrow} throws
 *       {@code ResourceNotFoundException}).</li>
 *   <li>403 when the caller lacks {@code FINANCE_UPDATE}.</li>
 * </ul>
 *
 * <p>SMS has <em>no PDF gate</em> — unlike the email path, {@code requestSms()}
 * does not check {@code pdfPath}. The only preflight is that the
 * {@code BeneficiaryPhoneResolverDispatcher} returns a non-blank phone for the
 * credit note's entity type.
 *
 * <p>Mirrors {@link PaymentControllerEmailIT} for credit-note + payment seeding
 * and {@link ReceiptControllerSmsIT} for the SMS-specific WorkflowClient mock
 * shape ({@code @MockBean WorkflowClient} + {@code @BeforeEach} stub).
 *
 * @since Task 9.5 — F7-δ + R7 SMS controller IT
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE"})
class PaymentControllerSmsIT extends FinanceWebItSupport {

    @Autowired MockMvc        mockMvc;
    @Autowired PaymentService paymentService;
    @Autowired JdbcTemplate   jdbc;
    @Autowired WorkflowClient workflowClient; // mocked by FinanceWebItSupport

    /**
     * The @MockBean WorkflowClient on FinanceWebItSupport returns null from
     * newWorkflowStub() by default. PaymentService.requestSms() invokes
     * WorkflowClient.start(workflow::send, ...) which would NPE on a null
     * stub, surfacing as 500 from the controller. Stub the call to return
     * a Mockito-mocked SendPaymentVoucherSmsWorkflow so the start path is a
     * harmless no-op during ITs.
     */
    @BeforeEach
    void stubWorkflowStub() {
        SendPaymentVoucherSmsWorkflow workflowStub = mock(SendPaymentVoucherSmsWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(SendPaymentVoucherSmsWorkflow.class),
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
    @DisplayName("POST /sms returns 202 with workflowId on COMMISSION happy path (Broker.phone resolved)")
    void requestSms_202_happyPathCommission() throws Exception {
        UUID brokerId = seedBrokerWithPhone("+2348099887766");
        UUID cnId     = createCommissionCreditNote(brokerId);
        Payment p = paymentService.post(
            cnId, new BigDecimal("75000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Broker Payee", "0123456789", "IT");

        mockMvc.perform(post("/api/v1/credit-notes/{cnId}/payments/{id}/sms", cnId, p.getId()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.workflowId",
                                  startsWith("send-payment-voucher-sms-")));
    }

    @Test
    @DisplayName("POST /sms returns 422 PAYMENT_RECIPIENT_PHONE_UNRESOLVED when entity_type=POLICY (no resolver)")
    void requestSms_422_phoneUnresolved() throws Exception {
        UUID cnId = createPolicyCreditNote();
        Payment p = paymentService.post(
            cnId, new BigDecimal("50000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Unknown", "0", "IT");

        mockMvc.perform(post("/api/v1/credit-notes/{cnId}/payments/{id}/sms", cnId, p.getId()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errors[0].code").value("PAYMENT_RECIPIENT_PHONE_UNRESOLVED"));
    }

    @Test
    @DisplayName("POST /sms returns 404 RESOURCE_NOT_FOUND for unknown payment id")
    void requestSms_404_unknownPayment() throws Exception {
        UUID cnId           = UUID.randomUUID();
        UUID unknownPaymentId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/credit-notes/{cnId}/payments/{id}/sms",
                                cnId, unknownPaymentId))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errors[0].code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "bob", authorities = {"FINANCE_VIEW"})
    @DisplayName("POST /sms returns 403 when caller lacks FINANCE_UPDATE")
    void requestSms_403_withoutFinanceUpdate() throws Exception {
        mockMvc.perform(post("/api/v1/credit-notes/{cnId}/payments/{id}/sms",
                                UUID.randomUUID(), UUID.randomUUID()))
            .andExpect(status().isForbidden());
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    /**
     * Seeds a broker with a known phone number.
     * The COMMISSION {@code BeneficiaryPhoneResolver} reads {@code brokers.phone}
     * via the credit note's {@code beneficiary_id} FK.
     */
    private UUID seedBrokerWithPhone(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO brokers (id, name, code, phone, created_by) VALUES (?, ?, ?, ?, 'test')",
            id,
            "Broker-" + id.toString().substring(0, 6),
            "BR-" + id.toString().substring(0, 8).toUpperCase(),
            phone);
        return id;
    }

    /**
     * Creates a COMMISSION-typed credit note with the broker id as
     * {@code beneficiary_id} — matches the dispatcher route for the
     * {@code COMMISSION-phone} resolver.
     */
    private UUID createCommissionCreditNote(UUID brokerId) {
        UUID cnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_id, beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'COMMISSION', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-COMMISH-SMS-" + cnId.toString().substring(0, 8),
            UUID.randomUUID(),
            "COMM-" + cnId.toString().substring(0, 4),
            brokerId,
            "Broker Beneficiary",
            "Broker commission",
            new BigDecimal("75000.00"),
            new BigDecimal("75000.00"));
        return cnId;
    }

    /**
     * Creates a POLICY-typed credit note — no {@code BeneficiaryPhoneResolver}
     * is registered for POLICY in the dispatcher, so
     * {@code dispatcher.resolve()} returns {@code Optional.empty()} and
     * {@code PaymentService.requestSms()} throws
     * {@code NotificationPreflightException("PAYMENT_RECIPIENT_PHONE_UNRESOLVED", ...)}.
     */
    private UUID createPolicyCreditNote() {
        UUID cnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'POLICY', ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-POL-SMS-" + cnId.toString().substring(0, 8),
            UUID.randomUUID(),
            "POL-" + cnId.toString().substring(0, 4),
            "Policy Refund Test",
            "Policy-routed CN with no phone resolver",
            new BigDecimal("50000.00"),
            new BigDecimal("50000.00"));
        return cnId;
    }
}
