package com.nubeero.cia.finance.email;

import com.nubeero.cia.finance.audit.PdfDownloadLogRetentionActivitiesImpl;
import com.nubeero.cia.finance.audit.PdfDownloadLogRetentionWorkflow;
import com.nubeero.cia.finance.audit.PdfDownloadLogRetentionWorkflowImpl;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Registers both email workflows + their activity beans on
 * {@link TemporalQueues#EMAIL_QUEUE}. Mirrors {@code BackfillWorkerConfig}.
 *
 * <p>The {@code @PostConstruct} hook is graceful — if Temporal is unavailable
 * at boot, it logs a warning and the app still starts. This matches the
 * behaviour established by {@code BackfillWorkerConfig} (and the webhook
 * worker before it). {@code TemporalWorkerStarter} (cia-api) calls
 * {@code workerFactory.start()} once all modules have registered.
 *
 * @since Slice γ — Task 21, F7 email transmission
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EmailWorkerConfig {

    private final WorkerFactory                          workerFactory;
    private final SendReceiptEmailActivitiesImpl         receiptActivities;
    private final SendPaymentVoucherEmailActivitiesImpl  voucherActivities;
    private final PdfDownloadLogRetentionActivitiesImpl  retentionActivities;
    private final WorkflowClient                         workflowClient;

    @PostConstruct
    public void registerEmailWorker() {
        try {
            Worker worker = workerFactory.newWorker(TemporalQueues.EMAIL_QUEUE);
            worker.registerWorkflowImplementationTypes(
                SendReceiptEmailWorkflowImpl.class,
                SendPaymentVoucherEmailWorkflowImpl.class,
                PdfDownloadLogRetentionWorkflowImpl.class);
            worker.registerActivitiesImplementations(
                receiptActivities, voucherActivities, retentionActivities);
            log.info("Registered Temporal worker on queue: {}", TemporalQueues.EMAIL_QUEUE);
            schedulePdfDownloadLogRetention();
        } catch (Exception e) {
            log.warn("Could not register email Temporal worker (Temporal unavailable?): {}", e.getMessage());
        }
    }

    /**
     * Schedules the weekly retention purge via Temporal cron. Sunday 02:00 UTC.
     * Cron survives JVM restarts (it's persisted in Temporal state). On
     * re-registration the existing schedule is left intact — Temporal
     * idempotency on the workflow id prevents duplicates (the existing
     * workflow is queryable via the workflow-id "pdf-download-log-retention-cron").
     */
    private void schedulePdfDownloadLogRetention() {
        try {
            PdfDownloadLogRetentionWorkflow workflow = workflowClient.newWorkflowStub(
                PdfDownloadLogRetentionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TemporalQueues.EMAIL_QUEUE)
                    .setWorkflowId("pdf-download-log-retention-cron")
                    .setCronSchedule("0 2 * * 0")
                    .build());
            WorkflowClient.start(workflow::purge);
            log.info("Scheduled pdf_download_log retention cron (Sunday 02:00 UTC)");
        } catch (Exception e) {
            log.info("pdf_download_log retention cron already scheduled or Temporal unavailable: {}",
                     e.getMessage());
        }
    }
}
