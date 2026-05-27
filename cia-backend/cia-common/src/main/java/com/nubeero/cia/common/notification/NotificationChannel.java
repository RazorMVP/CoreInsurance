package com.nubeero.cia.common.notification;

/**
 * Delivery channel for a notification.
 *
 * <p>Used by the per-tenant template override system (slice δ / R7) to
 * select the correct template when both EMAIL and SMS variants exist for
 * the same {@link NotificationTemplateType}.
 *
 * @since Task 0.1 — F7-δ + R7 pre-work refactor
 */
public enum NotificationChannel {
    EMAIL,
    SMS
}
