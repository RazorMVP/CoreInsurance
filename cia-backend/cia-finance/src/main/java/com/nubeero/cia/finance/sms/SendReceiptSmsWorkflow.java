package com.nubeero.cia.finance.sms;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.UUID;

/**
 * Temporal workflow for delivering a receipt SMS notification.
 *
 * <p>Mirror of {@link com.nubeero.cia.finance.email.SendReceiptEmailWorkflow}
 * for the SMS channel. The workflow is intentionally thin — it delegates all
 * I/O to {@link SmsActivities#deliverReceiptSms} so that retry semantics are
 * managed by the Temporal retry policy on the activity stub rather than in
 * workflow code.
 *
 * @since R7 — SMS Temporal workflows, Tasks 8.1–8.3
 */
@WorkflowInterface
public interface SendReceiptSmsWorkflow {

    @WorkflowMethod
    void send(String tenantId, UUID receiptId, String requestedBy);

    /**
     * Best-effort cancel. Sets the {@code cancelled} flag on the workflow
     * impl; the next activity invocation checks the flag and exits cleanly
     * without invoking {@code smsService.sendSms}. An in-flight provider
     * call already in progress completes (Temporal cannot interrupt an
     * activity mid-execution).
     */
    @SignalMethod
    void cancel();
}
