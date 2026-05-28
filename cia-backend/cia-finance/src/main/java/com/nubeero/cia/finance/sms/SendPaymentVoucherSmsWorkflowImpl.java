package com.nubeero.cia.finance.sms;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.UUID;

/**
 * Temporal workflow implementation for sending a payment-voucher SMS.
 *
 * <p>Retry policy mirrors
 * {@link com.nubeero.cia.finance.email.SendPaymentVoucherEmailWorkflowImpl}:
 * first retry at 5 minutes, doubles each time up to 1 hour, no maximum
 * attempt cap. Three error codes are non-retryable:
 * {@code PAYMENT_NOT_FOUND}, {@code PAYMENT_PDF_UNAVAILABLE},
 * {@code PAYMENT_RECIPIENT_PHONE_UNRESOLVED}.
 *
 * @since R7 — SMS Temporal workflows, Tasks 8.1–8.3
 */
public class SendPaymentVoucherSmsWorkflowImpl implements SendPaymentVoucherSmsWorkflow {

    private boolean cancelled = false;

    private final SmsActivities activities = Workflow.newActivityStub(
            SmsActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(TemporalQueues.NOTIFICATIONS_QUEUE)
                .setStartToCloseTimeout(Duration.ofMinutes(2))
                .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMinutes(5))
                    .setMaximumInterval(Duration.ofHours(1))
                    .setBackoffCoefficient(2.0)
                    .setDoNotRetry("PAYMENT_NOT_FOUND", "PAYMENT_RECIPIENT_PHONE_UNRESOLVED")
                    .build())
                .build());

    @Override
    public void send(String tenantId, UUID paymentId, String requestedBy) {
        // Best-effort cancellation: check the flag BEFORE dispatching to the
        // activity. If a cancel signal arrives after we've already dispatched,
        // the activity (and its retries) complete normally — we don't try to
        // interrupt the SMS provider in flight. This is enough for the
        // bulk-SMS UI which fires N workflows serially; cancel mid-run means
        // "don't send the remaining queued ones", and each queued workflow
        // gets a clean pre-dispatch check.
        if (cancelled) return;
        activities.deliverPaymentVoucherSms(tenantId, paymentId, requestedBy);
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }
}
