package com.nubeero.cia.config;

import io.temporal.worker.WorkerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Starts the Temporal WorkerFactory after all Spring beans (including
 * all module-level worker registrations) are fully initialized.
 * Each module registers its workers via @PostConstruct; this fires last.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemporalWorkerStarter {

    private final WorkerFactory workerFactory;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            workerFactory.start();
            log.info("Temporal WorkerFactory started — all workers active");
        } catch (Exception e) {
            log.warn("Temporal WorkerFactory could not start (Temporal unavailable): {}", e.getMessage());
        }
    }
}
