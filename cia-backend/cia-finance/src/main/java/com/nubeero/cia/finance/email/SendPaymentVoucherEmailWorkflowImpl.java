package com.nubeero.cia.finance.email;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.UUID;

/**
 * Temporal workflow implementation for sending a payment-voucher email.
 *
 * <p>Retry policy mirrors {@link SendReceiptEmailWorkflowImpl}: first retry at
 * 5 minutes, doubles each time up to 1 hour, no maximum attempt cap.
 * Three error codes are non-retryable: PAYMENT_NOT_FOUND,
 * PAYMENT_PDF_UNAVAILABLE, PAYMENT_RECIPIENT_UNRESOLVED.
 *
 * @since Slice γ — Task 20, F7 email transmission
 */
public class SendPaymentVoucherEmailWorkflowImpl implements SendPaymentVoucherEmailWorkflow {

    private boolean cancelled = false;

    private final SendPaymentVoucherEmailActivities activities = Workflow.newActivityStub(
            SendPaymentVoucherEmailActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMinutes(5))
                    .setMaximumInterval(Duration.ofHours(1))
                    .setBackoffCoefficient(2.0)
                    .setDoNotRetry("PAYMENT_PDF_UNAVAILABLE", "PAYMENT_RECIPIENT_UNRESOLVED", "PAYMENT_NOT_FOUND")
                    .build())
                .build());

    @Override
    public void send(String tenantId, UUID paymentId, String requestedBy) {
        // Best-effort cancellation: check the flag BEFORE dispatching to the
        // activity. If a cancel signal arrives after we've already dispatched,
        // the activity (and its retries) complete normally — we don't try to
        // interrupt SMTP in flight. This is enough for the bulk-email UI which
        // fires N workflows serially; cancel mid-run means "don't send the
        // remaining queued ones", and each queued workflow gets a clean
        // pre-dispatch check.
        if (cancelled) return;
        activities.deliver(tenantId, paymentId, requestedBy);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }
}
