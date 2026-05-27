package com.nubeero.cia.api.finance.email;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.email.SendReceiptEmailActivitiesImpl;
import com.nubeero.cia.finance.email.SendReceiptEmailWorkflow;
import com.nubeero.cia.finance.email.SendReceiptEmailWorkflowImpl;
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
 * Temporal workflow ITs for {@link SendReceiptEmailWorkflow}.
 *
 * <p>Establishes the {@link TestWorkflowEnvironment} pattern for the CIAGB codebase
 * (first usage of temporal-testing in an IT). Three scenarios:
 * <ol>
 *   <li>Happy path — email delivered, audit row written, DB columns populated.</li>
 *   <li>Non-retryable failure — {@code RECEIPT_PDF_UNAVAILABLE} surfaces immediately
 *       without writing any audit row.</li>
 *   <li>Retry sim — SMTP fails 3×, succeeds on attempt 4; exactly one audit row
 *       (idempotency guarantee: audit only happens after successful send).</li>
 * </ol>
 *
 * <p>Temporal's simulated clock ({@link TestWorkflowEnvironment}) compresses the
 * 5-minute initial retry interval to zero real time, making the retry test viable
 * without any actual waiting.
 *
 * @since Slice γ — Task 22, F7 email workflow ITs
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE"})
class SendReceiptEmailWorkflowIT extends FinanceWebItSupport {

    // ── beans from Spring context ─────────────────────────────────────────────

    @MockBean
    EmailService emailService;

    /**
     * Injects the {@code @MockBean DocumentStorageService} that is declared (and
     * registered in the Spring context) by {@link FinanceWebItSupport}. We cannot
     * reference it as a field — this IT lives in a different package, and the base
     * class field has package-private access — so {@code @Autowired} retrieves the
     * already-existing mock bean by type instead of creating a duplicate.
     */
    @Autowired
    DocumentStorageService documentStorageService;

    @Autowired
    SendReceiptEmailActivitiesImpl receiptActivities;

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
        worker.registerWorkflowImplementationTypes(SendReceiptEmailWorkflowImpl.class);
        worker.registerActivitiesImplementations(receiptActivities);
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
    @DisplayName("Happy path: workflow completes, email_sent_at/email_sent_to populated, audit SEND row written")
    void happyPath_emailDeliveredAndAuditRowWritten() throws Exception {
        // Arrange — seed customer with email, create debit note referencing that customer,
        // post a receipt (triggers slice-β PDF generation).
        UUID customerId = seedCustomerWithEmail("test-happy@receipt.local");
        UUID dnId       = createDebitNote(customerId);

        stubDocumentStorageToReturnPdfBytes();
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-happy");
        assertThat(r.getPdfPath())
            .as("ReceiptService.post() must generate a PDF (slice β)")
            .isNotNull();

        // Also stub the subsequent download in the workflow activity
        stubDocumentStorageToReturnPdfBytes();

        // Act — start workflow (synchronous, since TestWorkflowEnvironment)
        SendReceiptEmailWorkflow wf = client.newWorkflowStub(
            SendReceiptEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-receipt-happy-" + r.getId())
                .build());
        wf.send("test-tenant", r.getId(), "alice");

        // Assert — email columns populated
        String emailSentTo = jdbc.queryForObject(
            "SELECT email_sent_to FROM receipts WHERE id = ?", String.class, r.getId());
        assertThat(emailSentTo).isEqualTo("test-happy@receipt.local");

        Boolean emailSentAtNotNull = jdbc.queryForObject(
            "SELECT email_sent_at IS NOT NULL FROM receipts WHERE id = ?", Boolean.class, r.getId());
        assertThat(emailSentAtNotNull).isTrue();

        // Assert — exactly one SEND audit row
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' AND entity_id = ? AND action = 'SEND'",
            Integer.class, r.getId().toString());
        assertThat(auditCount).isEqualTo(1);

        // Assert — EmailService invoked once with REC- filename
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, times(1)).sendEmail(captor.capture());
        EmailMessage sent = captor.getValue();
        assertThat(sent.to()).isEqualTo("test-happy@receipt.local");
        assertThat(sent.attachments()).hasSize(1);
        assertThat(sent.attachments().get(0).filename()).startsWith("REC-");
    }

    @Test
    @DisplayName("RECEIPT_PDF_UNAVAILABLE: workflow fails non-retryably; no audit row, no email_sent_at")
    void pdfUnavailable_nonRetryableFailure() {
        // Arrange — insert receipt directly via JDBC with pdf_path NULL
        UUID customerId = seedCustomerWithEmail("nobody@receipt.local");
        UUID dnId       = createDebitNote(customerId);
        UUID receiptId  = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO receipts (id, receipt_number, debit_note_id, amount, payment_date, " +
            "                       payment_method, status, created_by) " +
            "VALUES (?, ?, ?, ?, CURRENT_DATE, 'CASH', 'POSTED', 'test')",
            receiptId,
            "REC-NOPDF-" + receiptId.toString().substring(0, 6),
            dnId,
            new BigDecimal("50000"));

        // Act + Assert — expect WorkflowFailedException wrapping ApplicationFailure
        SendReceiptEmailWorkflow wf = client.newWorkflowStub(
            SendReceiptEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-receipt-nopdf-" + receiptId)
                .build());

        assertThatThrownBy(() -> wf.send("test-tenant", receiptId, "alice"))
            .isInstanceOf(WorkflowFailedException.class)
            .rootCause()
            .isInstanceOf(ApplicationFailure.class)
            .satisfies(root -> assertThat(((ApplicationFailure) root).getType())
                .isEqualTo("RECEIPT_PDF_UNAVAILABLE"));

        // Assert — no audit row written
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' AND entity_id = ? AND action = 'SEND'",
            Integer.class, receiptId.toString());
        assertThat(auditCount).isEqualTo(0);

        // Assert — email_sent_at stays null
        Boolean emailSentAtNull = jdbc.queryForObject(
            "SELECT email_sent_at IS NULL FROM receipts WHERE id = ?", Boolean.class, receiptId);
        assertThat(emailSentAtNull).isTrue();
    }

    @Test
    @DisplayName("Retry sim: SMTP fails 3×, succeeds on 4th — exactly 1 audit row (idempotency)")
    void retrySim_3FailsThenSuccess_exactlyOneAuditRow() throws Exception {
        // Arrange — seed customer + receipt with PDF
        UUID customerId = seedCustomerWithEmail("retry@receipt.local");
        UUID dnId       = createDebitNote(customerId);

        stubDocumentStorageToReturnPdfBytes();
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-retry");
        assertThat(r.getPdfPath()).isNotNull();

        // Stub: fail 3× then succeed. Storage stub needs to return bytes on each attempt.
        stubDocumentStorageToReturnPdfBytes();
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(inv -> {
            int n = callCount.incrementAndGet();
            if (n < 4) {
                throw new RuntimeException("SMTP transient error attempt " + n);
            }
            return null; // success on 4th call
        }).when(emailService).sendEmail(any(EmailMessage.class));

        // Act — TestWorkflowEnvironment uses simulated time; env.sleep() advances the
        // simulated clock instantly so the exponential back-off retries don't add wall time.
        SendReceiptEmailWorkflow wf = client.newWorkflowStub(
            SendReceiptEmailWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId("test-receipt-retry-" + r.getId())
                .build());

        // Start the workflow asynchronously so we can advance time
        WorkflowClient.start(wf::send, "test-tenant", r.getId(), "alice");
        // Advance simulated clock past 3 retry intervals (5min, 10min, 20min = 35min total)
        env.sleep(java.time.Duration.ofMinutes(40));

        // Wait for the workflow to finish; getResult blocks until complete.
        // With simulated time already advanced past all retry intervals the
        // workflow should be done — 10 s real-time timeout is a safety net.
        client.newUntypedWorkflowStub("test-receipt-retry-" + r.getId())
              .getResult(10, TimeUnit.SECONDS, Void.class);

        // Assert — EmailService called exactly 4 times
        verify(emailService, times(4)).sendEmail(any(EmailMessage.class));

        // Assert — exactly 1 audit row (audit only written after successful send)
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' AND entity_id = ? AND action = 'SEND'",
            Integer.class, r.getId().toString());
        assertThat(auditCount)
            .as("Audit row must be written exactly once despite 3 SMTP failures before success")
            .isEqualTo(1);
    }

    // ── Fixture helpers ───────────────────────────────────────────────────────

    /**
     * Seeds a minimal {@code customers} row with a known email address.
     * Omits encrypted bytea fields (address, id_number, id_document_url) since
     * the receipt workflow reads only {@code customers.email} (plain text).
     */
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

    /**
     * Stubs {@code documentStorageService.download()} to return a minimal PDF byte stream.
     * Uses {@code any()} matchers because {@code TenantContext.getTenantId()} is null
     * in the test context (no JWT / no TenantContextFilter).
     */
    private void stubDocumentStorageToReturnPdfBytes() {
        Mockito.doAnswer(inv -> new ByteArrayInputStream(
                "%PDF-1.4 mock receipt content".getBytes()))
            .when(documentStorageService).download(any(), any());
    }
}
