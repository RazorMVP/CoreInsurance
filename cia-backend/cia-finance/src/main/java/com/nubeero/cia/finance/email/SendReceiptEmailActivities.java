package com.nubeero.cia.finance.email;

import io.temporal.activity.ActivityInterface;

import java.util.UUID;

/**
 * Temporal activity interface for delivering a receipt email.
 *
 * <p>A single {@code deliver} activity keeps the workflow simple: one
 * activity = one retry boundary. If SMTP fails after multiple retries,
 * the failure is surfaced as a workflow failure rather than silently swallowed.
 *
 * @since Slice γ — Task 19, F7 email transmission
 */
@ActivityInterface
public interface SendReceiptEmailActivities {
    void deliver(String tenantId, UUID receiptId, String requestedBy);
}
