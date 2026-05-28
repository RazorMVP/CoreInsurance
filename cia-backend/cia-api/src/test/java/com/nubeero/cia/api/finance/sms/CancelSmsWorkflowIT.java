package com.nubeero.cia.api.finance.sms;

import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.Receipt;
import com.nubeero.cia.finance.ReceiptService;
import com.nubeero.cia.finance.notification.NotificationPreflightException;
import com.nubeero.cia.finance.sms.SendReceiptSmsWorkflowImpl;
import com.nubeero.cia.finance.sms.SmsActivitiesImpl;
import com.nubeero.cia.notifications.sms.SmsMessage;
import com.nubeero.cia.notifications.sms.SmsService;
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
 * Pins the cancel-signal flow on {@link com.nubeero.cia.finance.sms.SendReceiptSmsWorkflow}.
 *
 * <p>Mirrors {@code CancelEmailWorkflowIT} exactly — same
 * {@link TestWorkflowEnvironment} setup, same seeding helpers, same
 * {@code signalWithStart} technique for the pre-dispatch cancel — with
 * SMS-channel swaps throughout.
 *
 * <ol>
 *   <li>{@link #cancelBeforeStart_noSmsAndNoSendAudit} — delivers cancel AS
 *       the workflow starts via {@code signalWithStart}; workflow's
 *       {@code if (cancelled) return;} check fires before activity dispatch;
 *       {@code smsService.sendSms} never called, no SEND audit row.</li>
 *   <li>{@link #cancelForUnknownWorkflow_throwsWorkflowNotFound} — calls
 *       {@code receiptService.cancelSms} for a workflow that was never
 *       started; the production {@code workflowClient} (@MockBean from
 *       {@link FinanceWebItSupport}) throws on the typed-stub call, which the
 *       service wraps in {@link NotificationPreflightException}.</li>
 * </ol>
 *
 * @since F7-δ / R7 — Task 10.1, SMS cancel workflow IT
 */
@WithMockUser(username = "alice", authorities = {"FINANCE_VIEW", "FINANCE_CREATE"})
class CancelSmsWorkflowIT extends FinanceWebItSupport {

    @MockBean  SmsService             smsService;
    @Autowired DocumentStorageService documentStorageService;
    @Autowired SmsActivitiesImpl      smsActivities;
    @Autowired ReceiptService         receiptService;
    @Autowired JdbcTemplate           jdbc;

    private TestWorkflowEnvironment env;
    private WorkflowClient          client;

    @BeforeEach
    void setUpTemporal() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TemporalQueues.NOTIFICATIONS_QUEUE);
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
    void cancelBeforeStart_noSmsAndNoSendAudit() throws Exception {
        UUID customerId = seedCustomerWithPhone("+2348011223344");
        UUID dnId       = createDebitNote(customerId);
        Mockito.doAnswer(inv -> new ByteArrayInputStream("%PDF-1.4 mock".getBytes()))
            .when(documentStorageService).download(any(), any());
        Receipt r = receiptService.post(
            dnId, new BigDecimal("100000"), LocalDate.now(),
            PaymentMethod.CASH, null, null, null, "IT-sms-cancel-wf");
        assertThat(r.getPdfPath()).isNotNull();

        // signalWithStart: deliver the cancel signal AS the workflow starts,
        // so the very first thing send() sees is cancelled=true. The
        // `if (cancelled) return;` check skips activities.deliverReceiptSms entirely.
        String workflowId = "test-sms-cancel-" + r.getId();
        WorkflowStub stub = client.newUntypedWorkflowStub(
            "SendReceiptSmsWorkflow",
            WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setWorkflowId(workflowId)
                .build());
        stub.signalWithStart(
            "cancel",
            new Object[]{},
            new Object[]{"test-tenant", r.getId(), "alice"});
        stub.getResult(10, TimeUnit.SECONDS, Void.class);

        // Assert: smsService.sendSms was NEVER called (workflow skipped the activity)
        verify(smsService, never()).sendSms(any(SmsMessage.class));

        // Assert: no SEND audit row (activity never ran)
        Integer auditCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'Receipt' " +
            "  AND entity_id = ? AND action = 'SEND'",
            Integer.class, r.getId().toString());
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    @DisplayName("Signal on unknown workflow id → service surfaces NotificationPreflightException(WORKFLOW_NOT_FOUND)")
    void cancelForUnknownWorkflow_throwsWorkflowNotFound() {
        // ReceiptService.cancelSms uses the production WorkflowClient
        // (the @MockBean from FinanceWebItSupport). For an unknown
        // workflow id, the stub's cancel() invocation throws — service's
        // try/catch wraps in NotificationPreflightException.
        UUID fakeReceiptId = UUID.randomUUID();
        assertThatThrownBy(() -> receiptService.cancelSms(fakeReceiptId))
            .isInstanceOf(NotificationPreflightException.class)
            .hasMessageContaining("No in-flight SMS workflow");
    }

    private UUID seedCustomerWithPhone(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_type, kyc_status, first_name, last_name, phone, created_by) " +
            "VALUES (?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, 'test')",
            id, "Test", "Customer", phone);
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
            dnId, "DN-SMSC-" + dnId.toString().substring(0, 8),
            UUID.randomUUID(), "POL-SMSC-001",
            customerId, "SMS Cancel Test Customer", "Premium",
            new BigDecimal("500000.00"), new BigDecimal("500000.00"));
        return dnId;
    }
}
