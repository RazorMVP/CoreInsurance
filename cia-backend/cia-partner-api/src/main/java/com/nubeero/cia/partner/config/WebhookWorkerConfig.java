package com.nubeero.cia.partner.config;

import com.nubeero.cia.partner.usage.PartnerUsageFlushActivitiesImpl;
import com.nubeero.cia.partner.usage.PartnerUsageFlushWorkflow;
import com.nubeero.cia.partner.usage.PartnerUsageFlushWorkflowImpl;
import com.nubeero.cia.partner.webhook.WebhookDispatchActivityImpl;
import com.nubeero.cia.partner.webhook.WebhookDispatchWorkflowImpl;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebhookWorkerConfig {

    private final WorkerFactory workerFactory;
    private final WebhookDispatchActivityImpl webhookDispatchActivity;
    private final PartnerUsageFlushActivitiesImpl partnerUsageFlushActivities;
    private final WorkflowClient workflowClient;

    @PostConstruct
    public void registerWebhookWorker() {
        try {
            Worker worker = workerFactory.newWorker(TemporalQueues.WEBHOOK_QUEUE);
            worker.registerWorkflowImplementationTypes(
                    WebhookDispatchWorkflowImpl.class, PartnerUsageFlushWorkflowImpl.class);
            worker.registerActivitiesImplementations(webhookDispatchActivity, partnerUsageFlushActivities);
            log.info("Registered Temporal worker on queue: {}", TemporalQueues.WEBHOOK_QUEUE);
            schedulePartnerUsageFlush();
        } catch (Exception e) {
            log.warn("Could not register webhook Temporal worker (Temporal unavailable?): {}", e.getMessage());
        }
    }

    /**
     * Daily drain of yesterday's live {@code PartnerUsageRollupStore} counters into {@code
     * partner_request_daily}. 03:00 UTC — after the calendar day it flushes has fully closed
     * everywhere. Cron survives JVM restarts (persisted in Temporal state); re-registration is a
     * no-op via the fixed workflow id, mirroring {@code PdfDownloadLogRetentionWorkflow}'s
     * registration pattern.
     */
    private void schedulePartnerUsageFlush() {
        try {
            PartnerUsageFlushWorkflow workflow = workflowClient.newWorkflowStub(
                    PartnerUsageFlushWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setTaskQueue(TemporalQueues.WEBHOOK_QUEUE)
                            .setWorkflowId("partner-usage-flush-cron")
                            .setCronSchedule("0 3 * * *")
                            .build());
            WorkflowClient.start(workflow::flushYesterday);
            log.info("Scheduled partner_request_daily flush cron (daily 03:00 UTC)");
        } catch (Exception e) {
            log.info("partner_request_daily flush cron already scheduled or Temporal unavailable: {}",
                    e.getMessage());
        }
    }
}
