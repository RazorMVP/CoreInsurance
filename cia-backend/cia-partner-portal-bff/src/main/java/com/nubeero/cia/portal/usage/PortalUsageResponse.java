package com.nubeero.cia.portal.usage;

import java.util.List;

/** {@code GET /portal/apps/{id}/usage} response — powers the Usage Dashboard in full. */
public record PortalUsageResponse(
        UsageDayResponse today,
        List<UsageHistoryEntryResponse> history,
        WebhookDeliverySummaryResponse webhookDeliveries,
        double errorRate) {
}
