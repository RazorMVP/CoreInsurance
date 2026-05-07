package com.nubeero.cia.config;

import com.nubeero.cia.workflow.worker.TemporalWorkerManager;
import com.nubeero.cia.workflow.worker.TemporalWorkerRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalWorkerHealthIndicatorTest {

    @Test
    void reportsUpWhenWorkerFactoryIsStarted() {
        FakeWorkerManager workerManager = new FakeWorkerManager();
        workerManager.started = true;

        assertThat(new TemporalWorkerHealthIndicator(workerManager).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenWorkerFactoryIsNotStarted() {
        FakeWorkerManager workerManager = new FakeWorkerManager();

        assertThat(new TemporalWorkerHealthIndicator(workerManager).health().getStatus())
                .isEqualTo(Status.DOWN);
    }

    private static final class FakeWorkerManager implements TemporalWorkerManager {

        private boolean started;

        @Override
        public TemporalWorkerRegistration newWorker(String taskQueue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public boolean isStarted() {
            return started;
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
}
