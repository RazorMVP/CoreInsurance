package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the retroactive JE backfill Temporal worker on
 * {@link TemporalQueues#BACKFILL_QUEUE} (Slice 1.8a — Module 12).
 *
 * <p>Follows the per-module worker-registration pattern established by
 * {@code WebhookWorkerConfig}: a {@code @PostConstruct} hook attaches the
 * workflow impl + activity bean to the shared {@code WorkerFactory}, and
 * {@code TemporalWorkerStarter} (cia-api) calls {@code workerFactory.start()}
 * once all modules have finished registering.
 *
 * <p>The worker inherits the {@code TenantAwareWorkerInterceptor} configured
 * in {@code TemporalConfig}, so every activity invocation here gets the
 * automatic {@code TenantContext} + {@code FiscalPeriodLookupCache}
 * cleanup in {@code finally}.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BackfillWorkerConfig {

    private final WorkerFactory workerFactory;
    private final RetroactiveJournalBackfillActivitiesImpl backfillActivities;

    @PostConstruct
    public void registerBackfillWorker() {
        try {
            Worker worker = workerFactory.newWorker(TemporalQueues.BACKFILL_QUEUE);
            worker.registerWorkflowImplementationTypes(RetroactiveJournalBackfillWorkflowImpl.class);
            worker.registerActivitiesImplementations(backfillActivities);
            log.info("Registered Temporal worker on queue: {}", TemporalQueues.BACKFILL_QUEUE);
        } catch (Exception e) {
            log.warn("Could not register backfill Temporal worker (Temporal unavailable?): {}", e.getMessage());
        }
    }
}
