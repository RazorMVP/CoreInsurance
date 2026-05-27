package com.nubeero.cia.finance.audit;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Weekly cleanup of {@code pdf_download_log} rows older than 30 days.
 * Registered on {@link com.nubeero.cia.workflow.TemporalQueues#EMAIL_QUEUE}
 * and triggered by a Temporal cron schedule (Sunday 02:00 UTC).
 *
 * @since F11
 */
@WorkflowInterface
public interface PdfDownloadLogRetentionWorkflow {
    @WorkflowMethod
    void purge();
}
