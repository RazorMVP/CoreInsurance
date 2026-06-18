package com.nubeero.cia.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Startup-resilience contract for the Temporal client beans.
 *
 * <p>The cia-api pod must boot even when Temporal is briefly unreachable — Temporal is an async
 * subsystem (workers retry their pollers; the {@code @PostConstruct} cron scheduling is
 * try-caught; {@code TemporalWorkerStarter} try-catches {@code factory.start()}). The one bean
 * that was NOT resilient is {@code workflowServiceStubs}: a connection health-check at construction
 * made an unreachable target throw inside bean instantiation → {@code BeanCreationException} →
 * startup crashloop.
 */
class TemporalConfigTest {

    /**
     * Pointing the stubs at {@code localhost:1} (nothing listening), construction must NOT block or
     * throw — it returns immediately and defers the real connection to the first RPC. Bounded by a
     * preemptive timeout so a regression (health-check re-enabled) surfaces as a failure, never a
     * hung build.
     */
    @Test
    void workflowServiceStubs_doesNotBlockOrThrow_whenTemporalUnreachable() {
        TemporalConfig config = new TemporalConfig();

        WorkflowServiceStubs stubs = assertTimeoutPreemptively(Duration.ofSeconds(8),
                () -> config.workflowServiceStubs("localhost:1"));

        assertThat(stubs).isNotNull();
        stubs.shutdownNow();
    }
}
