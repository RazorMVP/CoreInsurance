package com.nubeero.cia.portal.usage;

import java.time.Instant;

/**
 * Rolled-up webhook delivery health for the app's registrations — {@code webhook_delivery_logs}
 * across every registration this Partner App owns, in ITS tenant schema.
 */
public record WebhookDeliverySummaryResponse(
        int registrations,
        int activeRegistrations,
        long totalDeliveries,
        long successfulDeliveries,
        long failedDeliveries,
        Instant lastDeliveryAt) {
}
