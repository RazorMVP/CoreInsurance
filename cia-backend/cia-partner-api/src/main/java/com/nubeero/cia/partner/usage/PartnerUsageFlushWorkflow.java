package com.nubeero.cia.partner.usage;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Daily drain of the live {@link PartnerUsageRollupStore} counters into the durable {@link
 * PartnerRequestDaily} table. Registered on {@link com.nubeero.cia.workflow.TemporalQueues#WEBHOOK_QUEUE}
 * (mirrors {@code PdfDownloadLogRetentionWorkflow}'s registration pattern) and triggered by a
 * Temporal cron schedule (daily 03:00 UTC).
 *
 * @since Task 9 — Partner Portal BFF request telemetry
 */
@WorkflowInterface
public interface PartnerUsageFlushWorkflow {
    @WorkflowMethod
    void flushYesterday();
}
