package com.nubeero.cia.config;

import com.nubeero.cia.workflow.worker.TemporalWorkerManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("temporalWorker")
@RequiredArgsConstructor
public class TemporalWorkerHealthIndicator implements HealthIndicator {

    private final TemporalWorkerManager workerManager;

    @Override
    public Health health() {
        if (workerManager.isStarted() && !workerManager.isShutdown()
                && !workerManager.isTerminated()) {
            return Health.up()
                    .withDetail("workerFactory", "started")
                    .build();
        }
        return Health.down()
                .withDetail("workerFactory", "not-started")
                .build();
    }
}
