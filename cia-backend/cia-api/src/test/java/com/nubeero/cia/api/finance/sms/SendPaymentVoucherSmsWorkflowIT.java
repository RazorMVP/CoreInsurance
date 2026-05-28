package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.PaymentService;
import com.nubeero.cia.finance.sms.SendPaymentVoucherSmsWorkflow;
import com.nubeero.cia.finance.sms.SendPaymentVoucherSmsWorkflowImpl;
import com.nubeero.cia.finance.sms.SmsActivitiesImpl;
import com.nubeero.cia.notifications.sms.SmsMessage;
import com.nubeero.cia.notifications.sms.SmsService;
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
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Temporal workflow ITs for {@link SendPaymentVoucherSmsWorkflow}.
 *
 * <p>Combines the 4-beneficiary-type seeding chains from
 * {@link com.nubeero.cia.api.finance.email.SendPaymentVoucherEmailWorkflowIT}
 * (CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT) with the SMS-channel test
 * harness introduced in {@link SendReceiptSmsWorkflowIT}. Six scenarios:
 * <ol>
 *   <li>CLAIM happy path — Customer.phone resolved via Claim → Customer chain.</li>
 *   <li>COMMISSION happy path — Broker.phone resolved via dispatcher.</li>
 *   <li>REINSURANCE happy path — ReinsuranceCompany.phone resolved via dispatcher.</li>
 *   <li>ENDORSEMENT happy path — Customer.phone via Endorsement.customerId hop.</li>
 *   <li>Cancel-before-dispatch — {@code signalWithStart("cancel")} fires as the
 *       workflow starts; no SMS sent, no audit row.</li>
 *   <li>Non-retryable phone-unresolved — credit note with entity_type=POLICY has no
 *       resolver; dispatcher returns {@code Optional.empty()}, workflow fails
 *       non-retryably with {@code PAYMENT_RECIPIENT_PHONE_UNRESOLVED}, no SMS, no audit.</li>
 * </ol>
 *
 * @since F7-δ / R7 — Task 8.6, payment-voucher SMS workflow IT
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE"})
class SendPaymentVoucherSmsWorkflowIT extends FinanceWebItSupport {

    // ── beans from Spring context ─────────────────────────────────────────────

    @MockBean
    SmsService smsService;

    @Autowired
    DocumentStorageService documentStorageService;

    @Autowired
    SmsActivitiesImpl smsActivities;

    @Autowired
    PaymentService paymentService;

    @Autowired
    JdbcTemplate jdbc;

    // ── Temporal test harness ─────────────────────────────────────────────────

    private TestWorkflowEnvironment env;
    private Worker worker;
    private WorkflowClient client;

    @BeforeEach
    void setUpTemporal() {
        // Reset the shared @MockBean so invocations from prior tests (or from
        // SendReceiptSmsWorkflowIT which shares the same Spring context) don't
        // bleed into this test's verify() counts.
        reset(smsService);

        env    = TestWorkflowEnvironment.newInstance();
        worker = env.newWorker(TemporalQueues.NOTIFICATIONS_QUEUE);
        worker.registerWorkflowImplementationTypes(SendPaymentVoucherSmsWorkflowImpl.class);
        worker.registerActivitiesImplementations(smsActivities);
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

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CLAIM happy path: Customer.phone resolved via Claim → Customer chain + audit SEND row written")
    void claimHappyPath_sendsSmsAndWritesAudit() {
        UUID customerId = seedCustomerWithPhone("+2348011111111");
        UUID policyId   = seedPolicy(customerId);
        UUID claimId    = seedClaim(customerId, "Claim Customer", policyId);
        UUID cnId       = createClaimCreditNote(claimId);

        // Use JDBC directly rather than paymentService.post() to avoid the
        // Hibernate "shared references to a collection: Claim.documents" issue.
        // paymentService.post() calls PaymentVoucherPdfGenerator → ClaimBeneficiaryProfileResolver
        // which loads Claim via JPA (@Builder.Default @OneToMany = incompatible with
        // Hibernate flush when the same entity is re-loaded in the activity's @Transactional).
        // The SMS activity does NOT require pdfPath (no attachment), so a direct JDBC
        // insert with a stub pdf_path is sufficient.
        UUID paymentId = createPaymentViaJdbc(cnId, new BigDecimal("250000"), "IT-pv-sms-claim");

        SendPaymentVoucherSmsWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-pv-sms-claim-" + paymentId)
                .build());
        wf.send("test-tenant", paymentId, "alice");

        // sms_sent_to populated
        String smsSentTo = jdbc.queryForObject(
            "SELECT sms_sent_to FROM payments WHERE id = ?", String.class, paymentId);
        assertThat(smsSentTo).isEqualTo("+2348011111111");

        // sms_sent_at populated
        Boolean smsSentAtNotNull = jdbc.queryForObject(
            "SELECT sms_sent_at IS NOT NULL FROM payments WHERE id = ?", Boolean.class, paymentId);
        assertThat(smsSentAtNotNull).isTrue();

        // exactly one SEND audit row
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Payment' AND entity_id = ? AND action = 'SEND'",
            Integer.class, paymentId.toString());
        assertThat(auditCount).isEqualTo(1);

        // SmsService called once with correct phone
        ArgumentCaptor<SmsMessage> captor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(smsService, times(1)).sendSms(captor.capture());
        assertThat(captor.getValue().toPhone()).isEqualTo("+2348011111111");
    }

    @Test
    @DisplayName("COMMISSION happy path: Broker.phone resolved via dispatcher")
    void commissionHappyPath() {
        UUID brokerId = seedBrokerWithPhone("+2348022222222");
        UUID cnId     = createCommissionCreditNote(brokerId);

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("75000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Broker Payee", "0123456789", "IT-pv-sms-commish");
        assertThat(p.getPdfPath()).isNotNull();
        stubDocumentStorageToReturnPdfBytes();

        SendPaymentVoucherSmsWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-pv-sms-commish-" + p.getId())
                .build());
        wf.send("test-tenant", p.getId(), "alice");

        String smsSentTo = jdbc.queryForObject(
            "SELECT sms_sent_to FROM payments WHERE id = ?", String.class, p.getId());
        assertThat(smsSentTo).isEqualTo("+2348022222222");

        verify(smsService, times(1)).sendSms(any(SmsMessage.class));
    }

    @Test
    @DisplayName("REINSURANCE happy path: ReinsuranceCompany.phone resolved via dispatcher")
    void reinsuranceHappyPath() {
        UUID reinsurerId = seedReinsurerWithPhone("+2348033333333");
        UUID cnId        = createReinsuranceCreditNote(reinsurerId);

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("200000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "RI Payee", "0123456789", "IT-pv-sms-ri");
        assertThat(p.getPdfPath()).isNotNull();
        stubDocumentStorageToReturnPdfBytes();

        SendPaymentVoucherSmsWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-pv-sms-ri-" + p.getId())
                .build());
        wf.send("test-tenant", p.getId(), "alice");

        String smsSentTo = jdbc.queryForObject(
            "SELECT sms_sent_to FROM payments WHERE id = ?", String.class, p.getId());
        assertThat(smsSentTo).isEqualTo("+2348033333333");

        verify(smsService, times(1)).sendSms(any(SmsMessage.class));
    }

    @Test
    @DisplayName("ENDORSEMENT happy path: Customer.phone resolved via Endorsement.customerId denormalised hop")
    void endorsementHappyPath() {
        UUID customerId    = seedCustomerWithPhone("+2348044444444");
        UUID policyId      = seedPolicy(customerId);
        UUID endorsementId = seedEndorsement(customerId, "Endorsement Customer", policyId);
        UUID cnId          = createEndorsementCreditNote(endorsementId);

        // Use JDBC directly (same reasoning as CLAIM happy path — avoids
        // Hibernate "shared references to a collection: Endorsement.risks" caused
        // by @Builder.Default @OneToMany + JPA load in both paymentService.post()
        // PDF generation and the SMS activity's @Transactional).
        UUID paymentId = createPaymentViaJdbc(cnId, new BigDecimal("30000"), "IT-pv-sms-end");

        SendPaymentVoucherSmsWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-pv-sms-end-" + paymentId)
                .build());
        wf.send("test-tenant", paymentId, "alice");

        String smsSentTo = jdbc.queryForObject(
            "SELECT sms_sent_to FROM payments WHERE id = ?", String.class, paymentId);
        assertThat(smsSentTo).isEqualTo("+2348044444444");

        verify(smsService, times(1)).sendSms(any(SmsMessage.class));
    }

    @Test
    @DisplayName("Cancel-before-dispatch: cancel signal fires as workflow starts; no SMS sent, no audit row")
    void cancelBeforeDispatch_skipsSms() throws Exception {
        UUID brokerId = seedBrokerWithPhone("+2348055555555");
        UUID cnId     = createCommissionCreditNote(brokerId);

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("50000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Cancel Payee", "0123456789", "IT-pv-sms-cancel");
        assertThat(p.getPdfPath()).isNotNull();

        // signalWithStart delivers the cancel signal AS the workflow starts
        io.temporal.client.WorkflowStub untypedStub = client.newUntypedWorkflowStub(
            "SendPaymentVoucherSmsWorkflow",
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-pv-sms-cancel-" + p.getId())
                .build());
        untypedStub.signalWithStart("cancel", new Object[]{}, new Object[]{"test-tenant", p.getId(), "alice"});

        // Cancel path is synchronous — no activity dispatched
        untypedStub.getResult(10, TimeUnit.SECONDS, Void.class);

        // SmsService never called
        verify(smsService, never()).sendSms(any(SmsMessage.class));

        // No SEND audit row
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Payment' AND entity_id = ? AND action = 'SEND'",
            Integer.class, p.getId().toString());
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Phone unresolved: POLICY entity_type has no resolver — non-retryable PAYMENT_RECIPIENT_PHONE_UNRESOLVED; no SMS, no audit")
    void nonRetryablePhoneUnresolved_failsWithoutAudit() {
        // A POLICY-typed credit note has no BeneficiaryPhoneResolver registered in
        // BeneficiaryPhoneResolverDispatcher — dispatcher returns Optional.empty(),
        // activity throws PAYMENT_RECIPIENT_PHONE_UNRESOLVED (non-retryable).
        UUID cnId = createPolicyCreditNote();

        stubDocumentStorageToReturnPdfBytes();
        Payment p = paymentService.post(
            cnId, new BigDecimal("50000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, "Test Bank", "Unknown", "0", "IT-pv-sms-unresolved");

        SendPaymentVoucherSmsWorkflow wf = client.newWorkflowStub(
            SendPaymentVoucherSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-pv-sms-unresolved-" + p.getId())
                .build());

        assertThatThrownBy(() -> wf.send("test-tenant", p.getId(), "alice"))
            .isInstanceOf(WorkflowFailedException.class)
            .rootCause()
            .isInstanceOf(ApplicationFailure.class)
            .satisfies(root -> assertThat(((ApplicationFailure) root).getType())
                .isEqualTo("PAYMENT_RECIPIENT_PHONE_UNRESOLVED"));

        // No SMS sent
        verify(smsService, never()).sendSms(any(SmsMessage.class));

        // No SEND audit row
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Payment' AND entity_id = ? AND action = 'SEND'",
            Integer.class, p.getId().toString());
        assertThat(auditCount).isEqualTo(0);

        // sms_sent_at remains null
        Boolean smsSentAtNull = jdbc.queryForObject(
            "SELECT sms_sent_at IS NULL FROM payments WHERE id = ?", Boolean.class, p.getId());
        assertThat(smsSentAtNull).isTrue();
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    /**
     * Seeds a minimal {@code customers} row with a known phone number.
     * Omits encrypted bytea fields (address, id_number, id_document_url)
     * since the payment SMS activity reads only entity-table phone fields
     * via the resolver chain.
     */
    private UUID seedCustomerWithPhone(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, phone, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", phone);
        return id;
    }

    /**
     * Seeds a minimal {@code policies} row. Required because both {@code claims}
     * and {@code endorsements} have a FK on {@code policies(id)}.
     * Product/class columns on policies are snapshot-only (no FK) so random UUIDs suffice.
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
     * Seeds a minimal {@code claims} row. The CLAIM resolver reads
     * {@code claim.customerId} directly, so customerId MUST match the seeded
     * Customer's id.
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
     * Seeds a minimal {@code endorsements} row. The ENDORSEMENT resolver reads
     * {@code endorsement.customerId} directly (denormalised hop), so customerId
     * MUST match the seeded Customer's id.
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

    /** Seeds a broker with a known phone (used by CommissionBeneficiaryPhoneResolver). */
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

    /** Seeds a reinsurer with a known phone (used by FacOutwardBeneficiaryPhoneResolver). */
    private UUID seedReinsurerWithPhone(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO reinsurance_companies (id, name, phone, created_by) VALUES (?, ?, ?, 'test')",
            id,
            "Reinsurer-" + id.toString().substring(0, 6),
            phone);
        return id;
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

    /**
     * Creates a credit note with {@code entity_type='COMMISSION'} and the broker
     * id as {@code beneficiary_id} — matches the dispatcher route for
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

    /**
     * Creates a credit note with {@code entity_type='REINSURANCE'} and the reinsurer
     * id as {@code beneficiary_id} — matches the dispatcher route for
     * {@code REINSURANCE-phone} resolver.
     */
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

    /**
     * Credit note with {@code entity_type='POLICY'} — no resolver registered for
     * POLICY in the dispatcher, so {@code dispatcher.resolve()} returns
     * {@code Optional.empty()} and the workflow surfaces
     * {@code PAYMENT_RECIPIENT_PHONE_UNRESOLVED}.
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
            "Policy-routed CN with no phone resolver",
            new BigDecimal("50000.00"),
            new BigDecimal("50000.00"));
        return cnId;
    }

    /**
     * Inserts a minimal payment row directly via JDBC, bypassing
     * {@code PaymentService.post()} and its PDF-generation chain.
     *
     * <p>Used for CLAIM and ENDORSEMENT credit notes to avoid the Hibernate
     * <em>"Found shared references to a collection"</em> error that occurs when
     * {@code PaymentVoucherPdfGenerator} → {@code ClaimBeneficiaryProfileResolver} /
     * {@code EndorsementRefundBeneficiaryProfileResolver} loads a {@code Claim} or
     * {@code Endorsement} entity via JPA (both have {@code @Builder.Default @OneToMany}
     * collections) in the {@code post()} transaction, and then the SMS activity's
     * own {@code @Transactional} loads the same entity again in the same JVM-level
     * Hibernate session factory, triggering a second PersistentBag initialization
     * that Hibernate flags as a shared reference on flush.
     *
     * <p>The SMS activity does NOT require {@code pdfPath} (no PDF attachment), so a
     * stub {@code pdf_path} value is sufficient for the workflow to complete.
     *
     * @param creditNoteId the credit note the payment is posted against
     * @param amount payment amount
     * @param narration narration / reference for the test
     * @return the inserted payment {@code id}
     */
    private UUID createPaymentViaJdbc(UUID creditNoteId, BigDecimal amount, String narration) {
        UUID paymentId = UUID.randomUUID();
        String paymentNumber = "PAY-IT-" + paymentId.toString().substring(0, 8).toUpperCase();
        jdbc.update(
            "INSERT INTO payments " +
            "  (id, payment_number, credit_note_id, amount, payment_date, " +
            "   payment_method, status, narration, created_by, pdf_path) " +
            "VALUES (?, ?, ?, ?, CURRENT_DATE, 'BANK_TRANSFER', 'POSTED', ?, 'test', " +
            "        'payments/2026/01/stub.pdf')",
            paymentId, paymentNumber, creditNoteId, amount, narration);
        return paymentId;
    }

    /**
     * Stubs {@code documentStorageService.download()} to return a minimal PDF byte stream.
     * Uses {@code any()} matchers because {@code TenantContext.getTenantId()} is null
     * in the test context (no JWT / no TenantContextFilter).
     */
    private void stubDocumentStorageToReturnPdfBytes() {
        Mockito.doAnswer(inv -> new ByteArrayInputStream(
                "%PDF-1.4 mock payment voucher content".getBytes()))
            .when(documentStorageService).download(any(), any());
    }
}
