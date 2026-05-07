package com.nubeero.cia.config;

import com.nubeero.cia.workflow.worker.TemporalWorkerManager;
import com.nubeero.cia.workflow.worker.TemporalWorkerRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThat;

class TemporalWorkerStarterTest {

    @Test
    void throwsWhenWorkerFactoryCannotStartOutsideDevTest() {
        FakeWorkerManager workerManager = new FakeWorkerManager();
        workerManager.startFailure = new IllegalStateException("Temporal unavailable");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        TemporalWorkerStarter starter = new TemporalWorkerStarter(workerManager, environment);

        assertThatThrownBy(starter::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Temporal WorkerFactory could not start outside dev/test profiles");
    }

    @Test
    void allowsDevStartupToContinueWhenTemporalIsUnavailable() {
        FakeWorkerManager workerManager = new FakeWorkerManager();
        workerManager.startFailure = new IllegalStateException("Temporal unavailable");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        TemporalWorkerStarter starter = new TemporalWorkerStarter(workerManager, environment);

        assertThatNoException().isThrownBy(starter::start);
        assertThat(workerManager.startAttempts).isEqualTo(1);
    }

    private static final class FakeWorkerManager implements TemporalWorkerManager {

        private RuntimeException startFailure;
        private int startAttempts;

        @Override
        public TemporalWorkerRegistration newWorker(String taskQueue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start() {
            startAttempts++;
            if (startFailure != null) {
                throw startFailure;
            }
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
}
