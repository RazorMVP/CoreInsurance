package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflowImpl;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Temporal workflow ITs for {@link SendReceiptSmsWorkflow}.
 *
 * <p>Mirrors {@code SendReceiptEmailWorkflowIT} exactly — same
 * {@link TestWorkflowEnvironment} setup, same seeding helpers, same
 * simulated-clock technique for the retry scenario — with SMS-channel
 * swaps throughout. Five scenarios:
 * <ol>
 *   <li>Happy path — SMS delivered, {@code sms_sent_at}/{@code sms_sent_to}
 *       populated, audit {@code SEND} row written.</li>
 *   <li>Cancel-before-dispatch — {@code signalWithStart("cancel")} fires as
 *       the workflow starts; {@code smsService.sendSms} is never called and
 *       no audit row is written.</li>
 *   <li>Non-retryable phone-unresolved — customer has no phone; workflow
 *       fails immediately with {@code RECEIPT_RECIPIENT_PHONE_UNRESOLVED},
 *       no SMS sent, no audit row.</li>
 *   <li>Retry sim — SMS provider fails twice, succeeds on attempt 3; exactly
 *       one audit row (audit-after-success idempotency).</li>
 *   <li>Missing receipt — workflow for a bogus UUID fails non-retryably with
 *       {@code RECEIPT_NOT_FOUND}; no SMS sent.</li>
 * </ol>
 *
 * @since F7-δ / R7 — Task 8.5, SMS workflow IT
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE"})
class SendReceiptSmsWorkflowIT extends FinanceWebItSupport {

    // ── beans from Spring context ─────────────────────────────────────────────

    @MockBean
    SmsService smsService;

    /**
     * Injects the {@code @MockBean DocumentStorageService} declared in
     * {@link FinanceWebItSupport}. The field there is package-private, so
     * we retrieve the same mock bean by type via {@code @Autowired}.
     */
    @Autowired
    DocumentStorageService documentStorageService;

    @Autowired
    SmsActivitiesImpl smsActivities;

    @Autowired
    ReceiptService receiptService;

    @Autowired
    JdbcTemplate jdbc;

    // ── Temporal test harness ─────────────────────────────────────────────────

    private TestWorkflowEnvironment env;
    private Worker worker;
    private WorkflowClient client;

    @BeforeEach
    void setUpTemporal() {
        env    = TestWorkflowEnvironment.newInstance();
        worker = env.newWorker(TemporalQueues.NOTIFICATIONS_QUEUE);
        worker.registerWorkflowImplementationTypes(SendReceiptSmsWorkflowImpl.class);
        worker.registerActivitiesImplementations(smsActivities);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDownTemporal() {
        env.close();
        SecurityContextHolder.clearContext();
    }

    // ── Shared fixture setup ──────────────────────────────────────────────────

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
    @DisplayName("Happy path: SMS delivered, sms_sent_at/sms_sent_to populated, audit SEND row written")
    void happyPath_sendsSmsAndWritesAudit() throws Exception {
        // Arrange — seed customer with phone, debit note, post receipt (triggers slice-β PDF)
        UUID customerId = seedCustomerWithPhone("+2348012345678");
        UUID dnId       = createDebitNote(customerId);

        stubDocumentStorageToReturnPdfBytes();
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-sms-happy");
        assertThat(r.getPdfPath())
            .as("ReceiptService.post() must generate a PDF (slice β)")
            .isNotNull();

        // Act — start workflow (synchronous in TestWorkflowEnvironment)
        SendReceiptSmsWorkflow wf = client.newWorkflowStub(
            SendReceiptSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-receipt-sms-happy-" + r.getId())
                .build());
        wf.send("test-tenant", r.getId(), "alice");

        // Assert — sms_sent_to populated
        String smsSentTo = jdbc.queryForObject(
            "SELECT sms_sent_to FROM receipts WHERE id = ?", String.class, r.getId());
        assertThat(smsSentTo).isEqualTo("+2348012345678");

        // Assert — sms_sent_at populated
        Boolean smsSentAtNotNull = jdbc.queryForObject(
            "SELECT sms_sent_at IS NOT NULL FROM receipts WHERE id = ?", Boolean.class, r.getId());
        assertThat(smsSentAtNotNull).isTrue();

        // Assert — exactly one SEND audit row
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' AND entity_id = ? AND action = 'SEND'",
            Integer.class, r.getId().toString());
        assertThat(auditCount).isEqualTo(1);

        // Assert — SmsService invoked once with the correct phone
        ArgumentCaptor<SmsMessage> captor = ArgumentCaptor.forClass(SmsMessage.class);
        verify(smsService, times(1)).sendSms(captor.capture());
        assertThat(captor.getValue().toPhone()).isEqualTo("+2348012345678");
    }

    @Test
    @DisplayName("Cancel-before-dispatch: cancel signal fires as workflow starts; no SMS sent, no audit row")
    void cancelBeforeDispatch_skipsSms() throws Exception {
        // Arrange — seed customer + receipt
        UUID customerId = seedCustomerWithPhone("+2348099887766");
        UUID dnId       = createDebitNote(customerId);

        stubDocumentStorageToReturnPdfBytes();
        Receipt r = receiptService.post(
            dnId, new BigDecimal("75000"), LocalDate.now(),
            PaymentMethod.BANK_TRANSFER, null, null, null, "IT-sms-cancel");
        assertThat(r.getPdfPath()).isNotNull();

        // Act — use signalWithStart to deliver the cancel signal AS the workflow
        // starts, so SendReceiptSmsWorkflowImpl.send() sees cancelled=true on entry.
        io.temporal.client.WorkflowStub untypedStub = client.newUntypedWorkflowStub(
            "SendReceiptSmsWorkflow",
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-receipt-sms-cancel-" + r.getId())
                .build());
        untypedStub.signalWithStart("cancel", new Object[]{}, new Object[]{"test-tenant", r.getId(), "alice"});

        // Block until complete (cancel path is synchronous — no activity dispatched)
        untypedStub.getResult(10, TimeUnit.SECONDS, Void.class);

        // Assert — SmsService never called
        verify(smsService, never()).sendSms(any(SmsMessage.class));

        // Assert — no SEND audit row
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' AND entity_id = ? AND action = 'SEND'",
            Integer.class, r.getId().toString());
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Phone unresolved: non-retryable RECEIPT_RECIPIENT_PHONE_UNRESOLVED; no SMS, no audit")
    void nonRetryablePhoneUnresolved_failsWithoutAudit() {
        // Arrange — customer with NULL phone
        UUID customerId = seedCustomerWithPhone(null);
        UUID dnId       = createDebitNote(customerId);

        // Insert receipt directly via JDBC (bypassing service to keep pdf_path non-null
        // so the phone-resolution check is what triggers the failure, not pdf unavailability)
        UUID receiptId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, receipt_number, debit_note_id, amount, payment_date, " +
            "                       payment_method, status, created_by, pdf_path) " +
            "VALUES (?, ?, ?, ?, CURRENT_DATE, 'CASH', 'POSTED', 'test', 'receipts/2026/01/dummy.pdf')",
            receiptId,
            "REC-NOPHONE-" + receiptId.toString().substring(0, 6),
            dnId,
            new BigDecimal("50000"));

        // Act + Assert — expect non-retryable ApplicationFailure
        SendReceiptSmsWorkflow wf = client.newWorkflowStub(
            SendReceiptSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-receipt-sms-nophone-" + receiptId)
                .build());

        assertThatThrownBy(() -> wf.send("test-tenant", receiptId, "alice"))
            .isInstanceOf(WorkflowFailedException.class)
            .rootCause()
            .isInstanceOf(ApplicationFailure.class)
            .satisfies(root -> assertThat(((ApplicationFailure) root).getType())
                .isEqualTo("RECEIPT_RECIPIENT_PHONE_UNRESOLVED"));

        // Assert — no SMS sent
        verify(smsService, never()).sendSms(any(SmsMessage.class));

        // Assert — no SEND audit row
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' AND entity_id = ? AND action = 'SEND'",
            Integer.class, receiptId.toString());
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Retry sim: provider fails 2×, succeeds on 3rd — exactly 1 audit row (idempotency)")
    void retryableErrorThenSuccess_writesExactlyOneAudit() throws Exception {
        // Arrange — seed customer + receipt with PDF
        UUID customerId = seedCustomerWithPhone("+2348055551234");
        UUID dnId       = createDebitNote(customerId);

        stubDocumentStorageToReturnPdfBytes();
        Receipt r = receiptService.post(
            dnId, new BigDecimal("200000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-sms-retry");
        assertThat(r.getPdfPath()).isNotNull();

        // Stub: fail 2× then succeed on 3rd
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n < 3) {
                throw new RuntimeException("SMS provider transient error attempt " + n);
            }
            return null; // success on 3rd call
        }).when(smsService).sendSms(any(SmsMessage.class));

        // Act — start asynchronously so we can advance the simulated clock
        SendReceiptSmsWorkflow wf = client.newWorkflowStub(
            SendReceiptSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-receipt-sms-retry-" + r.getId())
                .build());
        WorkflowClient.start(wf::send, "test-tenant", r.getId(), "alice");

        // Advance simulated clock past 2 retry intervals (5min, 10min = 15min total)
        env.sleep(Duration.ofMinutes(20));

        // Block until workflow completes; simulated time already past all retries.
        client.newUntypedWorkflowStub("test-receipt-sms-retry-" + r.getId())
              .getResult(10, TimeUnit.SECONDS, Void.class);

        // Assert — SmsService called exactly 3 times (2 failures + 1 success)
        verify(smsService, times(3)).sendSms(any(SmsMessage.class));

        // Assert — exactly 1 audit row (audit only after successful send)
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' AND entity_id = ? AND action = 'SEND'",
            Integer.class, r.getId().toString());
        assertThat(auditCount)
            .as("Audit row must be written exactly once despite 2 SMS failures before success")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("Missing receipt: RECEIPT_NOT_FOUND — workflow fails non-retryably; no SMS sent")
    void preflightReceiptNotFound_failsCleanly() {
        UUID bogusId = UUID.randomUUID();

        SendReceiptSmsWorkflow wf = client.newWorkflowStub(
            SendReceiptSmsWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-receipt-sms-notfound-" + bogusId)
                .build());

        assertThatThrownBy(() -> wf.send("test-tenant", bogusId, "alice"))
            .isInstanceOf(WorkflowFailedException.class)
            .rootCause()
            .isInstanceOf(ApplicationFailure.class)
            .satisfies(root -> assertThat(((ApplicationFailure) root).getType())
                .isEqualTo("RECEIPT_NOT_FOUND"));

        // Assert — SmsService never called
        verify(smsService, never()).sendSms(any(SmsMessage.class));
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    /**
     * Seeds a minimal {@code customers} row with the given phone number.
     * Pass {@code null} to produce a customer with no phone (triggers
     * {@code RECEIPT_RECIPIENT_PHONE_UNRESOLVED} in the SMS activity).
     * Omits encrypted bytea fields (address, id_number, id_document_url)
     * since the receipt SMS activity reads only {@code customers.phone}.
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
     * Seeds a minimal {@code debit_notes} row referencing the given customer.
     */
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

    /**
     * Stubs {@code documentStorageService.download()} to return a minimal PDF byte stream.
     * Uses {@code any()} matchers because {@code TenantContext.getTenantId()} is null
     * in the test context (no JWT / no TenantContextFilter).
     */
    private void stubDocumentStorageToReturnPdfBytes() {
        Mockito.doAnswer(inv -> new java.io.ByteArrayInputStream(
                "%PDF-1.4 mock receipt content".getBytes()))
            .when(documentStorageService).download(any(), any());
    }
}
