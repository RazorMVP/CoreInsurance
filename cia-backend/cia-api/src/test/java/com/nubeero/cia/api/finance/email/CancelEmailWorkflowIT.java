package com.nubeero.cia.api.finance.email;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.finance.email.SendReceiptEmailActivitiesImpl;
import com.nubeero.cia.finance.email.SendReceiptEmailWorkflowImpl;
import com.nubeero.cia.notifications.email.EmailMessage;
import com.nubeero.cia.notifications.email.EmailService;
import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.verify;

/**
 * Pins the cancel-signal flow on SendReceiptEmailWorkflow.
 *
 * @since F11 — Task 15
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE"})
class CancelEmailWorkflowIT extends FinanceWebItSupport {

    @MockBean  EmailService                   emailService;
    @Autowired DocumentStorageService         documentStorageService;
    @Autowired SendReceiptEmailActivitiesImpl receiptActivities;
    @Autowired ReceiptService                 receiptService;
    @Autowired JdbcTemplate                   jdbc;

    private TestWorkflowEnvironment env;
    private WorkflowClient          client;

    @BeforeEach
    void setUpTemporal() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TemporalQueues.NOTIFICATIONS_QUEUE);
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

    @BeforeEach
    void setUpFiscalPeriod() {
        UUID fyId     = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        jdbc.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, 'ACTIVE', 'test') ON CONFLICT (name) DO NOTHING",
            fyId, "FY-IT-" + today.getYear(),
            LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear(), 12, 31));
        UUID resolvedFyId = jdbc.queryForObject(
            "SELECT id FROM fiscal_year WHERE name = ?", UUID.class, "FY-IT-" + today.getYear());
        jdbc.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, 'MONTH', ?, ?, 'OPEN', 'test') ON CONFLICT DO NOTHING",
            periodId, resolvedFyId, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()));
    }

    @Test
    @DisplayName("Cancel signal at workflow start (signalWithStart) → workflow skips activity dispatch")
    void cancelAtStart_skipsActivity() throws Exception {
        UUID customerId = seedCustomerWithEmail("cancel@test.local");
        UUID dnId       = createDebitNote(customerId);
        Mockito.doAnswer(inv -> new ByteArrayInputStream("%PDF-1.4 mock".getBytes()))
            .when(documentStorageService).download(any(), any());
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-cancel");
        assertThat(r.getPdfPath()).isNotNull();

        // signalWithStart: deliver the cancel signal AS the workflow starts,
        // so the very first thing send() sees is cancelled=true. The
        // `if (cancelled) return;` check skips activities.deliver entirely.
        String workflowId = "test-cancel-" + r.getId();
        WorkflowStub stub = client.newUntypedWorkflowStub(
            "SendReceiptEmailWorkflow",
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId(workflowId)
                .build());
        stub.signalWithStart(
            "cancel",
            new Object[]{},
            new Object[]{"test-tenant", r.getId(), "alice"});
        stub.getResult(10, TimeUnit.SECONDS, Void.class);

        // Assert: emailService.sendEmail was NEVER called (workflow skipped
        // the activity)
        verify(emailService, never()).sendEmail(any(EmailMessage.class));

        // Assert: no SEND audit row (activity never ran)
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' " +
            "  AND entity_id = ? AND action = 'SEND'",
            Integer.class, r.getId().toString());
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Signal on unknown workflow id → service surfaces NotificationPreflightException(WORKFLOW_NOT_FOUND)")
    void signalOnUnknownWorkflow_throws() {
        // ReceiptService.cancelEmail uses the production WorkflowClient
        // (the @MockBean from FinanceWebItSupport). For an unknown
        // workflow id, the stub's cancel() invocation throws — service's
        // try/catch wraps in NotificationPreflightException.
        UUID fakeReceiptId = UUID.randomUUID();
        assertThatThrownBy(() -> receiptService.cancelEmail(fakeReceiptId))
            .isInstanceOf(com.nubeero.cia.finance.notification.NotificationPreflightException.class)
            .hasMessageContaining("No in-flight email workflow");
    }

    private UUID seedCustomerWithEmail(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, email, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", email);
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
            dnId, "DN-CANCEL-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(), "POL-CANCEL-001",
            customerId, "Cancel Test Customer", "Premium",
            new BigDecimal("500000.00"), new BigDecimal("500000.00"));
        return dnId;
    }
}
