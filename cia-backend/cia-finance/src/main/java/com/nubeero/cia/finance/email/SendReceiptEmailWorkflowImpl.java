package com.nubeero.cia.finance.email;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.UUID;

/**
 * Temporal workflow implementation for sending a receipt email.
 *
 * <p>Retry policy: first retry at 5 minutes, doubles each time up to 1 hour,
 * no maximum attempt cap — keeps retrying until Temporal workflow timeout.
 * Three error codes are non-retryable and surface immediately to the caller:
 * RECEIPT_NOT_FOUND, RECEIPT_PDF_UNAVAILABLE, RECEIPT_RECIPIENT_UNRESOLVED.
 *
 * @since Slice γ — Task 19, F7 email transmission
 */
public class SendReceiptEmailWorkflowImpl implements SendReceiptEmailWorkflow {

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
        activities.deliver(tenantId, receiptId, requestedBy);
    }
}
