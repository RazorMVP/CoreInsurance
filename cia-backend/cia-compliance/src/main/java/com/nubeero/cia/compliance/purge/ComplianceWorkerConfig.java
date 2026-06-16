package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Registers the compliance worker + schedules the hourly retention-purge cron. Mirrors NotificationsWorkerConfig. */
@Component
@Slf4j
@RequiredArgsConstructor
public class ComplianceWorkerConfig {

    private final WorkerFactory workerFactory;
    private final WorkflowClient workflowClient;
    private final CompliancePurgeActivitiesImpl purgeActivities;

    @PostConstruct
    public void registerComplianceWorker() {
        try {
            Worker worker = workerFactory.newWorker(TemporalQueues.COMPLIANCE_QUEUE);
            worker.registerWorkflowImplementationTypes(CustomerPiiPurgeWorkflowImpl.class);
            worker.registerActivitiesImplementations(purgeActivities);
            log.info("Registered Temporal worker on queue: {}", TemporalQueues.COMPLIANCE_QUEUE);
            scheduleRetentionPurge();
        } catch (Exception e) {
            log.warn("Could not register compliance Temporal worker (Temporal unavailable?): {}",
                    e.getMessage());
        }
    }

    private void scheduleRetentionPurge() {
        try {
            CustomerPiiPurgeWorkflow workflow = workflowClient.newWorkflowStub(
                CustomerPiiPurgeWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TemporalQueues.COMPLIANCE_QUEUE)
                    .setWorkflowId("customer-pii-retention-purge-cron")
                    .setCronSchedule("0 * * * *")   // hourly; per-tenant window gate decides actual run
                    .build());
            WorkflowClient.start(workflow::purge);
            log.info("Scheduled customer-pii-retention-purge cron (hourly; per-tenant window-gated)");
        } catch (Exception e) {
            log.info("customer-pii-retention-purge cron already scheduled (idempotent): {}",
                    e.getMessage());
        }
    }
}
