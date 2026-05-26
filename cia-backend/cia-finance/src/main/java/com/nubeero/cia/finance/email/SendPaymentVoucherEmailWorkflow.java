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
}
