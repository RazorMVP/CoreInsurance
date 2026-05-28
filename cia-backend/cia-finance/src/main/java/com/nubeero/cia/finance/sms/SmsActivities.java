package com.nubeero.cia.finance.sms;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.UUID;

/**
 * Temporal activity interface for delivering receipt and payment-voucher SMS
 * notifications.
 *
 * <p>A single activity per entity type keeps the workflow simple: one
 * activity = one retry boundary. If the SMS provider fails after multiple
 * retries, the failure is surfaced as a workflow failure rather than
 * silently swallowed.
 *
 * @since R7 — SMS Temporal workflows, Tasks 8.1–8.3
 */
@ActivityInterface
public interface SmsActivities {

    /**
     * Deliver a receipt SMS to the customer phone on file.
     *
     * <p>Non-retryable error codes: {@code RECEIPT_NOT_FOUND},
     * {@code RECEIPT_RECIPIENT_PHONE_UNRESOLVED}.
     */
    @ActivityMethod
    void deliverReceiptSms(String tenantId, UUID receiptId, String requestedBy);

    /**
     * Deliver a payment-voucher SMS to the beneficiary phone resolved via
     * {@link BeneficiaryPhoneResolverDispatcher}.
     *
     * <p>Non-retryable error codes: {@code PAYMENT_NOT_FOUND},
     * {@code PAYMENT_RECIPIENT_PHONE_UNRESOLVED}.
     */
    @ActivityMethod
    void deliverPaymentVoucherSms(String tenantId, UUID paymentId, String requestedBy);
}
