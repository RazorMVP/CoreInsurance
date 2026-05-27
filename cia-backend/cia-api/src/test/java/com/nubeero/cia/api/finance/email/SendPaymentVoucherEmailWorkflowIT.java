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
 * <p>CLAIM and ENDORSEMENT happy paths were originally deferred (backlog row
 * {@code F7-γ-claim-endorsement-payment-ITs}); both are now in this IT —
 * they need a seeded Customer + Policy chain because the Claim/Endorsement
 * FK to {@code policies(id)} is real and the resolver reads
 * {@code claim.customerId} / {@code endorsement.customerId} directly.
 *
 * @since Slice γ — Task 22 + post-slice backlog drain, F7 payment voucher workflow ITs
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
    @DisplayName("CLAIM happy path: Customer.email resolved via Claim → Customer chain")
    void claimHappyPath() {
        UUID customerId = seedCustomerWithEmail("claimant@test.local");
        UUID policyId   = seedPolicy(customerId);
        UUID claimId    = seedClaim(customerId, "Claim Customer", policyId);
        UUID cnId       = createClaimCreditNote(claimId);

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("250000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Claim Payee", "0123456789", "IT-claim");
        assertThat(p.getPdfPath()).isNotNull();
        stubDocumentStorageToReturnPdfBytes();

        SendPaymentVoucherEmailWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                .setWorkflowId("test-pay-claim-" + p.getId())
                .build());
        wf.send("test-tenant", p.getId(), "alice");

        String sentTo = jdbc.queryForObject(
            "SELECT email_sent_to FROM payments WHERE id = ?", String.class, p.getId());
        assertThat(sentTo).isEqualTo("claimant@test.local");

        verify(emailService, times(1)).sendEmail(any(EmailMessage.class));
    }

    @Test
    @DisplayName("ENDORSEMENT happy path: Customer.email resolved via Endorsement.customerId denormalised hop")
    void endorsementHappyPath() {
        UUID customerId    = seedCustomerWithEmail("endorsement@test.local");
        UUID policyId      = seedPolicy(customerId);
        UUID endorsementId = seedEndorsement(customerId, "Endorsement Customer", policyId);
        UUID cnId          = createEndorsementCreditNote(endorsementId);

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("30000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Refund Payee", "0123456789", "IT-end");
        assertThat(p.getPdfPath()).isNotNull();
        stubDocumentStorageToReturnPdfBytes();

        SendPaymentVoucherEmailWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                .setWorkflowId("test-pay-end-" + p.getId())
                .build());
        wf.send("test-tenant", p.getId(), "alice");

        String sentTo = jdbc.queryForObject(
            "SELECT email_sent_to FROM payments WHERE id = ?", String.class, p.getId());
        assertThat(sentTo).isEqualTo("endorsement@test.local");

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

    /** Seeds a minimal {@code customers} row with a known plain-text email. */
    private UUID seedCustomerWithEmail(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, email, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", email);
        return id;
    }

    /**
     * Seeds a minimal {@code policies} row. Required because both
     * {@code claims} and {@code endorsements} have a FK on
     * {@code policies(id)}; the customer/product/class columns on policies
     * are snapshot-only (no FK) so we use random UUIDs there.
     */
    private UUID seedPolicy(UUID customerId) {
        UUID policyId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies " +
            "  (id, customer_id, customer_name, " +
            "   product_id, product_name, product_code, product_rate, " +
            "   class_of_business_id, class_of_business_name, class_of_business_code, " +
            "   policy_start_date, policy_end_date, created_by) " +
            "VALUES (?, ?, 'Policy Customer', ?, 'Test Product', 'PRD', 0.05, " +
            "        ?, 'Test Class', 'CLS', CURRENT_DATE - INTERVAL '30 days', " +
            "        CURRENT_DATE + INTERVAL '335 days', 'test')",
            policyId, customerId, UUID.randomUUID(), UUID.randomUUID());
        return policyId;
    }

    /**
     * Seeds a minimal {@code claims} row. The Claim resolver reads
     * {@code claim.customerId} directly, so customerId here MUST match
     * the seeded Customer's id.
     */
    private UUID seedClaim(UUID customerId, String customerName, UUID policyId) {
        UUID claimId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO claims " +
            "  (id, claim_number, " +
            "   policy_id, policy_number, policy_start_date, policy_end_date, " +
            "   customer_id, customer_name, " +
            "   product_id, product_name, " +
            "   class_of_business_id, class_of_business_name, " +
            "   incident_date, reported_date, description, " +
            "   created_by) " +
            "VALUES (?, ?, ?, 'POL-IT-001', CURRENT_DATE - INTERVAL '30 days', " +
            "        CURRENT_DATE + INTERVAL '335 days', " +
            "        ?, ?, ?, 'Test Product', ?, 'Test Class', " +
            "        CURRENT_DATE - INTERVAL '7 days', CURRENT_DATE - INTERVAL '5 days', " +
            "        'Test claim incident', 'test')",
            claimId,
            "CLM-IT-" + claimId.toString().substring(0, 8),
            policyId,
            customerId, customerName,
            UUID.randomUUID(), UUID.randomUUID());
        return claimId;
    }

    /**
     * Seeds a minimal {@code endorsements} row. The Endorsement resolver
     * reads {@code endorsement.customerId} directly (denormalised hop),
     * so customerId here MUST match the seeded Customer's id.
     */
    private UUID seedEndorsement(UUID customerId, String customerName, UUID policyId) {
        UUID endId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO endorsements " +
            "  (id, endorsement_number, " +
            "   policy_id, policy_number, " +
            "   customer_id, customer_name, " +
            "   product_id, product_name, product_code, product_rate, " +
            "   class_of_business_id, class_of_business_name, " +
            "   effective_date, policy_end_date, description, " +
            "   created_by) " +
            "VALUES (?, ?, ?, 'POL-IT-001', ?, ?, ?, 'Test Product', 'PRD', 0.05, " +
            "        ?, 'Test Class', CURRENT_DATE, " +
            "        CURRENT_DATE + INTERVAL '335 days', 'Test endorsement', 'test')",
            endId,
            "END-IT-" + endId.toString().substring(0, 8),
            policyId,
            customerId, customerName,
            UUID.randomUUID(), UUID.randomUUID());
        return endId;
    }

    private UUID createClaimCreditNote(UUID claimId) {
        UUID cnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'CLAIM', ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-CLAIM-" + cnId.toString().substring(0, 8),
            claimId,
            "CLM-IT",
            "Claim DV Beneficiary",
            "Claim discharge voucher",
            new BigDecimal("250000.00"),
            new BigDecimal("250000.00"));
        return cnId;
    }

    private UUID createEndorsementCreditNote(UUID endorsementId) {
        UUID cnId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO credit_notes " +
            "  (id, credit_note_number, status, entity_type, entity_id, entity_reference, " +
            "   beneficiary_name, description, amount, tax_amount, total_amount, " +
            "   paid_amount, currency_code, created_by) " +
            "VALUES (?, ?, 'OUTSTANDING', 'ENDORSEMENT', ?, ?, ?, ?, ?, 0, ?, 0, 'NGN', 'test')",
            cnId,
            "CN-END-" + cnId.toString().substring(0, 8),
            endorsementId,
            "END-IT",
            "Endorsement Refund Beneficiary",
            "Endorsement refund",
            new BigDecimal("30000.00"),
            new BigDecimal("30000.00"));
        return cnId;
    }

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
