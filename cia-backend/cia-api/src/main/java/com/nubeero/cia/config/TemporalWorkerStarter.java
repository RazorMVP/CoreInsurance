package com.nubeero.cia.config;

import com.nubeero.cia.workflow.worker.TemporalWorkerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.env.Environment;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Starts the Temporal WorkerFactory after all Spring beans (including
 * all module-level worker registrations) are fully initialized.
 * Each module registers its workers via @PostConstruct; this fires last.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemporalWorkerStarter {

    private static final Set<String> DEV_OR_TEST_PROFILES = Set.of("dev", "test");

    private final TemporalWorkerManager workerManager;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        try {
            workerManager.start();
            log.info("Temporal WorkerFactory started; all registered workers are active");
        } catch (Exception e) {
            if (!isDevOrTestOnly()) {
                throw new IllegalStateException(
                        "Temporal WorkerFactory could not start outside dev/test profiles", e);
            }
            log.warn("Temporal WorkerFactory could not start (Temporal unavailable): {}", e.getMessage());
        }
    }

    private boolean isDevOrTestOnly() {
        Set<String> activeProfiles = Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .filter(profile -> !profile.isBlank())
                .collect(Collectors.toSet());
        return !activeProfiles.isEmpty()
                && activeProfiles.stream().allMatch(DEV_OR_TEST_PROFILES::contains);
    }
}
