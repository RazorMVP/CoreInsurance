package com.nubeero.cia.workflow.worker;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TemporalWorkerFactoryManager implements TemporalWorkerManager {

    private final WorkerFactory workerFactory;

    @Override
    public TemporalWorkerRegistration newWorker(String taskQueue) {
        return new WorkerRegistrationAdapter(workerFactory.newWorker(taskQueue));
    }

    @Override
    public void start() {
        workerFactory.start();
    }

    @Override
    public boolean isStarted() {
        return workerFactory.isStarted();
    }

    @Override
    public boolean isShutdown() {
        return workerFactory.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return workerFactory.isTerminated();
    }

    private record WorkerRegistrationAdapter(Worker worker) implements TemporalWorkerRegistration {

        @Override
        public void registerWorkflowImplementationTypes(Class<?>... workflowImplementationTypes) {
            worker.registerWorkflowImplementationTypes(workflowImplementationTypes);
        }

        @Override
        public void registerActivitiesImplementations(Object... activities) {
            worker.registerActivitiesImplementations(activities);
        }
    }
}
