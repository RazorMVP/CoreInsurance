package com.nubeero.cia.api.finance.email;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.PaymentService;
import com.nubeero.cia.finance.email.SendPaymentVoucherEmailActivitiesImpl;
import com.nubeero.cia.finance.email.SendPaymentVoucherEmailWorkflow;
import com.nubeero.cia.finance.email.SendPaymentVoucherEmailWorkflowImpl;
import com.nubeero.cia.notifications.email.EmailMessage;
import com.nubeero.cia.notifications.email.EmailService;
import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Temporal workflow ITs for {@link SendPaymentVoucherEmailWorkflow}.
 *
 * <p>Covers four scenarios:
 * <ol>
 *   <li>COMMISSION happy path — Broker.email resolved via dispatcher.</li>
 *   <li>REINSURANCE happy path — ReinsuranceCompany.email resolved via dispatcher.</li>
 *   <li>PAYMENT_RECIPIENT_UNRESOLVED — credit note with entity_type=POLICY has no
 *       resolver registered; dispatcher returns Optional.empty(), workflow fails
 *       non-retryably.</li>
 *   <li>Retry sim — SMTP fails 3×, succeeds on attempt 4; exactly one audit row.</li>
 * </ol>
 *
 * <p>CLAIM and ENDORSEMENT happy paths are intentionally deferred — they require
 * full Customer + Policy + Product + ClassOfBusiness fixture chains to satisfy
 * Claim/Endorsement FK constraints. The Customer.email resolution pattern they
 * exercise is structurally identical to the receipt workflow's happy path
 * (covered by SendReceiptEmailWorkflowIT) — both go Customer → email. Logged as
 * a P2 backlog row "F7-γ-claim-endorsement-payment-ITs".
 *
 * @since Slice γ — Task 22, F7 payment voucher workflow ITs
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE"})
class SendPaymentVoucherEmailWorkflowIT extends FinanceWebItSupport {

    @MockBean
    EmailService emailService;

    @Autowired
    DocumentStorageService documentStorageService;

    @Autowired
    SendPaymentVoucherEmailActivitiesImpl voucherActivities;

    @Autowired
    PaymentService paymentService;

    @Autowired
    JdbcTemplate jdbc;

    private TestWorkflowEnvironment env;
    private Worker worker;
    private WorkflowClient client;

    @BeforeEach
    void setUpTemporal() {
        env    = TestWorkflowEnvironment.newInstance();
        worker = env.newWorker(TemporalQueues.EMAIL_QUEUE);
        worker.registerWorkflowImplementationTypes(SendPaymentVoucherEmailWorkflowImpl.class);
        worker.registerActivitiesImplementations(voucherActivities);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDownTemporal() {
        env.close();
        SecurityContextHolder.clearContext();
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

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("COMMISSION happy path: Broker.email resolved + voucher delivered + audit SEND row written")
    void commissionHappyPath() {
        UUID brokerId = seedBrokerWithEmail("broker-commish@test.local");
        UUID cnId     = createCommissionCreditNote(brokerId);

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("75000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Broker Payee", "0123456789", "IT-commish");
        assertThat(p.getPdfPath()).isNotNull();
        stubDocumentStorageToReturnPdfBytes();

        SendPaymentVoucherEmailWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                .setWorkflowId("test-pay-commish-" + p.getId())
                .build());
        wf.send("test-tenant", p.getId(), "alice");

        String sentTo = jdbc.queryForObject(
            "SELECT email_sent_to FROM payments WHERE id = ?", String.class, p.getId());
        assertThat(sentTo).isEqualTo("broker-commish@test.local");

        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Payment' AND entity_id = ? AND action = 'SEND'",
            Integer.class, p.getId().toString());
        assertThat(auditCount).isEqualTo(1);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, times(1)).sendEmail(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("broker-commish@test.local");
        assertThat(captor.getValue().attachments().get(0).filename()).startsWith("PAY-");
    }

    @Test
    @DisplayName("REINSURANCE happy path: ReinsuranceCompany.email resolved + voucher delivered")
    void reinsuranceHappyPath() {
        UUID reinsurerId = seedReinsurerWithEmail("ri-claims@test.local");
        UUID cnId        = createReinsuranceCreditNote(reinsurerId);

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("200000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "RI Payee", "0123456789", "IT-ri");
        assertThat(p.getPdfPath()).isNotNull();
        stubDocumentStorageToReturnPdfBytes();

        SendPaymentVoucherEmailWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                .setWorkflowId("test-pay-ri-" + p.getId())
                .build());
        wf.send("test-tenant", p.getId(), "alice");

        String sentTo = jdbc.queryForObject(
            "SELECT email_sent_to FROM payments WHERE id = ?", String.class, p.getId());
        assertThat(sentTo).isEqualTo("ri-claims@test.local");

        verify(emailService, times(1)).sendEmail(any(EmailMessage.class));
    }

    @Test
    @DisplayName("PAYMENT_RECIPIENT_UNRESOLVED: POLICY entity_type has no resolver — workflow fails non-retryably, no audit row")
    void unresolvedRecipient_policyEntityType() {
        UUID cnId = createPolicyCreditNote();

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("50000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Unknown", "0", "IT-unresolved");

        SendPaymentVoucherEmailWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                .setWorkflowId("test-pay-unresolved-" + p.getId())
                .build());

        assertThatThrownBy(() -> wf.send("test-tenant", p.getId(), "alice"))
            .isInstanceOf(WorkflowFailedException.class)
            .rootCause()
            .isInstanceOf(ApplicationFailure.class)
            .satisfies(root -> assertThat(((ApplicationFailure) root).getType())
                .isEqualTo("PAYMENT_RECIPIENT_UNRESOLVED"));

        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Payment' AND entity_id = ? AND action = 'SEND'",
            Integer.class, p.getId().toString());
        assertThat(auditCount).isEqualTo(0);

        Boolean emailSentAtNull = jdbc.queryForObject(
            "SELECT email_sent_at IS NULL FROM payments WHERE id = ?", Boolean.class, p.getId());
        assertThat(emailSentAtNull).isTrue();
    }

    @Test
    @DisplayName("Retry sim: SMTP fails 3×, succeeds on 4th — exactly 1 audit row")
    void retrySim_3FailsThenSuccess_exactlyOneAuditRow() throws Exception {
        UUID brokerId = seedBrokerWithEmail("retry-pay@test.local");
        UUID cnId     = createCommissionCreditNote(brokerId);

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("125000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Retry Test", "0123456789", "IT-retry");
        assertThat(p.getPdfPath()).isNotNull();

        stubDocumentStorageToReturnPdfBytes();
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n < 4) {
                throw new RuntimeException("SMTP transient error attempt " + n);
            }
            return null;
        }).when(emailService).sendEmail(any(EmailMessage.class));

        SendPaymentVoucherEmailWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                .setWorkflowId("test-pay-retry-" + p.getId())
                .build());

        WorkflowClient.start(wf::send, "test-tenant", p.getId(), "alice");
        env.sleep(Duration.ofMinutes(40));

        client.newUntypedWorkflowStub("test-pay-retry-" + p.getId())
              .getResult(10, TimeUnit.SECONDS, Void.class);

        verify(emailService, times(4)).sendEmail(any(EmailMessage.class));

        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Payment' AND entity_id = ? AND action = 'SEND'",
            Integer.class, p.getId().toString());
        assertThat(auditCount)
            .as("Audit row written exactly once despite 3 SMTP failures before success")
            .isEqualTo(1);
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

    private UUID seedReinsurerWithEmail(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO reinsurance_companies (id, name, email, created_by) VALUES (?, ?, ?, 'test')",
            id, "Reinsurer-" + id.toString().substring(0, 6), email);
        return id;
    }

    /**
     * Creates a credit note with {@code entity_type='COMMISSION'} and the broker
     * id as the beneficiary — matches the dispatcher route for the
     * {@code COMMISSION-email} resolver.
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

    private UUID createReinsuranceCreditNote(UUID reinsurerId) {
        UUID cnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_id, beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'REINSURANCE', ?, ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-RI-" + cnId.toString().substring(0, 8),
            UUID.randomUUID(),
            "FAC-" + cnId.toString().substring(0, 4),
            reinsurerId,
            "Reinsurer Beneficiary",
            "FAC outward premium",
            new BigDecimal("200000.00"),
            new BigDecimal("200000.00"));
        return cnId;
    }

    /**
     * Credit note with {@code entity_type='POLICY'} — no resolver registered for
     * POLICY in the dispatcher, so {@code dispatcher.resolve()} returns
     * {@code Optional.empty()} and the workflow surfaces
     * {@code PAYMENT_RECIPIENT_UNRESOLVED}.
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
            "CN-POL-" + cnId.toString().substring(0, 8),
            UUID.randomUUID(),
            "POL-" + cnId.toString().substring(0, 4),
            "Policy Refund Test",
            "Policy-routed CN with no resolver",
            new BigDecimal("50000.00"),
            new BigDecimal("50000.00"));
        return cnId;
    }

    private void stubDocumentStorageToReturnPdfBytes() {
        Mockito.doAnswer(inv -> new ByteArrayInputStream(
                "%PDF-1.4 mock payment voucher content".getBytes()))
            .when(documentStorageService).download(any(), any());
    }
}
