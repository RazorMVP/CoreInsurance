package com.nubeero.cia.finance.sms;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.UUID;

/**
 * Temporal workflow for delivering a payment-voucher SMS notification.
 *
 * <p>Mirror of {@link com.nubeero.cia.finance.email.SendPaymentVoucherEmailWorkflow}
 * for the SMS channel. Recipient resolution uses
 * {@link BeneficiaryPhoneResolverDispatcher}
 * (CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT routes) rather than a
 * direct JDBC customer lookup.
 *
 * @since R7 — SMS Temporal workflows, Tasks 8.1–8.3
 */
@WorkflowInterface
public interface SendPaymentVoucherSmsWorkflow {

    @WorkflowMethod
    void send(String tenantId, UUID paymentId, String requestedBy);

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
