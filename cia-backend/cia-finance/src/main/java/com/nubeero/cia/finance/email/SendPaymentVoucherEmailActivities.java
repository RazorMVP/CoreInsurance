package com.nubeero.cia.finance.email;

import io.temporal.activity.ActivityInterface;

import java.util.UUID;

/**
 * Temporal activity interface for delivering a payment-voucher email.
 *
 * <p>Mirror of {@link SendReceiptEmailActivities} for the payables side.
 * Recipient resolution delegates to {@link BeneficiaryEmailResolverDispatcher}.
 *
 * @since Slice γ — Task 20, F7 email transmission
 */
@ActivityInterface
public interface SendPaymentVoucherEmailActivities {
    void deliver(String tenantId, UUID paymentId, String requestedBy);
}
