package com.nubeero.cia.common.notification;

/**
 * Channel-neutral discriminator for notification templates.
 *
 * <p>Lives in {@code cia-common} so both {@code cia-finance} (email
 * dispatch) and {@code cia-setup} (per-tenant template overrides, slice δ)
 * can reference it without a cross-business-module dependency.
 *
 * <p>Renamed from {@code EmailTemplateType} (RECEIPT_EMAIL /
 * PAYMENT_VOUCHER_EMAIL) to channel-neutral names ahead of F7-δ + R7
 * (per-tenant notification template overrides) which add SMS support.
 *
 * @since Task 0.1 — F7-δ + R7 pre-work refactor
 */
public enum NotificationTemplateType {
    RECEIPT,
    PAYMENT_VOUCHER
}
