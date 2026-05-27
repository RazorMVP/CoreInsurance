package com.nubeero.cia.finance.email;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.UUID;

public class SendReceiptEmailWorkflowImpl implements SendReceiptEmailWorkflow {

    private boolean cancelled = false;

    private final SendReceiptEmailActivities activities = Workflow.newActivityStub(
            SendReceiptEmailActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMinutes(5))
                    .setMaximumInterval(Duration.ofHours(1))
                    .setBackoffCoefficient(2.0)
                    .setDoNotRetry("RECEIPT_PDF_UNAVAILABLE", "RECEIPT_RECIPIENT_UNRESOLVED", "RECEIPT_NOT_FOUND")
                    .build())
                .build());

    @Override
    public void send(String tenantId, UUID receiptId, String requestedBy) {
        // Best-effort cancellation: check the flag BEFORE dispatching to the
        // activity. If a cancel signal arrives after we've already dispatched,
        // the activity (and its retries) complete normally — we don't try to
        // interrupt SMTP in flight. This is enough for the bulk-email UI which
        // fires N workflows serially; cancel mid-run means "don't send the
        // remaining queued ones", and each queued workflow gets a clean
        // pre-dispatch check.
        if (cancelled) return;
        activities.deliver(tenantId, receiptId, requestedBy);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }
}
