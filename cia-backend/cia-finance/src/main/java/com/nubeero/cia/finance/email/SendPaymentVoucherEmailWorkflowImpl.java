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

    private final SendPaymentVoucherEmailActivities activities = Workflow.newActivityStub(
            SendPaymentVoucherEmailActivities.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
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
        activities.deliver(tenantId, paymentId, requestedBy);
    }
}
