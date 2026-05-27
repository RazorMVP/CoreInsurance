package com.nubeero.cia.finance.email;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.UUID;

/**
 * Temporal workflow for delivering a payment-voucher PDF as an email attachment.
 *
 * <p>Mirror of {@link SendReceiptEmailWorkflow} for the payables side.
 * Recipient resolution uses {@link BeneficiaryEmailResolverDispatcher}
 * (CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT routes) rather than a
 * direct JDBC customer lookup.
 *
 * @since Slice γ — Task 20, F7 email transmission
 */
@WorkflowInterface
public interface SendPaymentVoucherEmailWorkflow {
    @WorkflowMethod
    void send(String tenantId, UUID paymentId, String requestedBy);

    /**
     * Best-effort cancel. Sets the {@code cancelled} flag on the workflow
     * impl; the next activity invocation checks the flag and exits
     * cleanly without invoking {@code emailService.sendEmail}. An
     * in-flight SMTP send already in progress completes (Temporal cannot
     * interrupt an activity mid-execution).
     */
    @io.temporal.workflow.SignalMethod
    void cancel();
}
