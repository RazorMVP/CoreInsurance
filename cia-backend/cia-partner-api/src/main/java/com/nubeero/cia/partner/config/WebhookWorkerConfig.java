package com.nubeero.cia.partner.config;

import com.nubeero.cia.partner.webhook.WebhookDispatchActivityImpl;
import com.nubeero.cia.partner.webhook.WebhookDispatchWorkflowImpl;
import com.nubeero.cia.workflow.TemporalQueues;
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

    @PostConstruct
    public void registerWebhookWorker() {
        try {
            Worker worker = workerFactory.newWorker(TemporalQueues.WEBHOOK_QUEUE);
            worker.registerWorkflowImplementationTypes(WebhookDispatchWorkflowImpl.class);
            worker.registerActivitiesImplementations(webhookDispatchActivity);
            log.info("Registered Temporal worker on queue: {}", TemporalQueues.WEBHOOK_QUEUE);
        } catch (Exception e) {
            log.warn("Could not register webhook Temporal worker (Temporal unavailable?): {}", e.getMessage());
        }
    }
}
