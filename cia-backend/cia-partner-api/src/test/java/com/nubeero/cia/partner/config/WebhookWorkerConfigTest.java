package com.nubeero.cia.partner.config;

import com.nubeero.cia.partner.webhook.WebhookDispatchActivityImpl;
import com.nubeero.cia.partner.webhook.WebhookDispatchWorkflowImpl;
import com.nubeero.cia.workflow.TemporalQueues;
import com.nubeero.cia.workflow.worker.TemporalWorkerManager;
import com.nubeero.cia.workflow.worker.TemporalWorkerRegistration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookWorkerConfigTest {

    @Test
    void registersWebhookWorkflowAndActivity() {
        RecordingWorkerManager workerManager = new RecordingWorkerManager();
        WebhookDispatchActivityImpl activity = new WebhookDispatchActivityImpl(null, null);

        new WebhookWorkerConfig(workerManager, activity).registerWebhookWorker();

        assertThat(workerManager.registrations).containsExactly(
                new Registration(
                        TemporalQueues.WEBHOOK_QUEUE,
                        List.of(WebhookDispatchWorkflowImpl.class),
                        List.of(activity)));
    }

    private static final class RecordingWorkerManager implements TemporalWorkerManager {

        private final List<Registration> registrations = new ArrayList<>();

        @Override
        public TemporalWorkerRegistration newWorker(String taskQueue) {
            Registration registration = new Registration(taskQueue, new ArrayList<>(), new ArrayList<>());
            registrations.add(registration);
            return registration;
        }

        @Override
        public void start() {
        }

        @Override
        public boolean isStarted() {
            return false;
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }
    }

    private record Registration(
            String taskQueue,
            List<Class<?>> workflowImplementationTypes,
            List<Object> activities) implements TemporalWorkerRegistration {

        @Override
        public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationTypes) {
            this.workflowImplementationTypes.addAll(Arrays.asList(workflowImplementationTypes));
        }

        @Override
        public void registerActivitiesImplementations(Object... activities) {
            this.activities.addAll(Arrays.asList(activities));
        }
    }
}
