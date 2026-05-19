package com.nubeero.cia.api.finance.backfill;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.backfill.BackfillAdminService;
import com.nubeero.cia.finance.backfill.dto.BackfillStatusResponse;
import com.nubeero.cia.finance.backfill.dto.StartBackfillRequest;
import com.nubeero.cia.finance.backfill.dto.StartBackfillResponse;
import com.nubeero.cia.workflow.backfill.BackfillEventType;
import com.nubeero.cia.workflow.backfill.BackfillEventTypeCount;
import com.nubeero.cia.workflow.backfill.BackfillResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line entry point for the retroactive JE backfill workflow
 * (Slice 1.8b — Module 12 Period-End Closures).
 *
 * <p>Activated only when {@code --cia.backfill.enabled=true} is passed at
 * startup; the runner bean is otherwise excluded by
 * {@link ConditionalOnProperty}. The intended invocation pairs this flag
 * with {@code --spring.main.web-application-type=NONE} so Spring boots far
 * enough to wire DB + Temporal connectivity but does not bind port 8080:
 *
 * <pre>{@code
 * java -jar cia-api.jar \
 *   --spring.main.web-application-type=NONE \
 *   --cia.backfill.enabled=true \
 *   --cia.backfill.tenant=tenant_acme \
 *   --cia.backfill.from=2026-01-01 \
 *   --cia.backfill.to=2026-05-31 \
 *   --cia.backfill.event-types=POLICY_APPROVED,CLAIM_SETTLED \
 *   --cia.backfill.dry-run=true
 * }</pre>
 *
 * <h2>Why a CLI in addition to the REST endpoint</h2>
 * <p>Two operational scenarios where the REST endpoint is the wrong tool:
 * <ol>
 *   <li><b>Initial migration</b> — the very first backfill happens before the
 *       front-end is generally available; there is no logged-in
 *       {@code PLATFORM_ADMIN} JWT to call POST with.</li>
 *   <li><b>Per-tenant runs scripted by ops</b> — looping over a list of
 *       tenants from a maintenance window is naturally shaped as a bash
 *       script, not a sequence of curl calls with bearer tokens.</li>
 * </ol>
 *
 * <h2>Exit codes</h2>
 * <ul>
 *   <li>0 — {@link BackfillResult.Status#SUCCESS}</li>
 *   <li>1 — {@link BackfillResult.Status#PARTIAL_FAILURE} (at least one row failed)</li>
 *   <li>2 — {@link BackfillResult.Status#REFUSED} (pre-flight lock blocked the run)</li>
 *   <li>3 — Temporal-level failure (FAILED / TIMED_OUT / CANCELED / TERMINATED)</li>
 *   <li>4 — bad input (missing required arg, malformed date)</li>
 * </ul>
 *
 * <p>Exits via {@link SpringApplication#exit} so {@code @PreDestroy} hooks
 * (Hikari pool shutdown, Temporal worker drain) still run. Bypassing those
 * with {@code System.exit} would leave hanging gRPC connections that
 * subsequent ops steps (e.g. {@code pg_dump} the same DB) would have to wait
 * out.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cia.backfill.enabled", havingValue = "true")
@RequiredArgsConstructor
public class BackfillCliRunner implements ApplicationRunner {

    private static final long POLL_INTERVAL_MS = 2_000L;
    private static final long MAX_WAIT_MS = 4 * 60 * 60 * 1000L; // 4 hours

    private final BackfillAdminService backfillAdminService;
    private final ApplicationContext applicationContext;

    @Value("${cia.backfill.tenant:}")
    private String tenant;

    @Value("${cia.backfill.from:}")
    private String fromDateStr;

    @Value("${cia.backfill.to:}")
    private String toDateStr;

    @Value("${cia.backfill.event-types:}")
    private String eventTypesCsv;

    @Value("${cia.backfill.dry-run:false}")
    private boolean dryRun;

    @Override
    public void run(ApplicationArguments args) {
        final int exitCode = computeExitCode();
        System.out.println("Exiting with code " + exitCode);
        // Exit Spring gracefully so @PreDestroy hooks run.
        System.exit(SpringApplication.exit(applicationContext, () -> exitCode));
    }

    private int computeExitCode() {
        try {
            return doRun();
        } catch (BadInputException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.err.println(usage());
            return 4;
        } catch (RuntimeException e) {
            log.error("Backfill CLI failed unexpectedly", e);
            System.err.println("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 3;
        }
    }

    private int doRun() {
        if (tenant.isBlank()) {
            throw new BadInputException("--cia.backfill.tenant is required");
        }
        LocalDate from = parseDate(fromDateStr, "--cia.backfill.from");
        LocalDate to = parseDate(toDateStr, "--cia.backfill.to");
        if (to.isBefore(from)) {
            throw new BadInputException("--cia.backfill.to must be >= --cia.backfill.from");
        }
        List<BackfillEventType> types = parseEventTypes(eventTypesCsv);

        System.out.printf("Starting backfill — tenant=%s range=%s..%s dryRun=%s eventTypes=%s%n",
                tenant, from, to, dryRun, types.isEmpty() ? "ALL" : types);

        // BackfillAdminService reads tenant from TenantContext; set it here
        // because there is no HTTP filter on the CLI path.
        TenantContext.setTenantId(tenant);
        StartBackfillResponse start;
        try {
            start = backfillAdminService.startBackfill(
                    new StartBackfillRequest(from, to, types.isEmpty() ? null : types, dryRun));
        } finally {
            TenantContext.clear();
        }

        System.out.println("Workflow started — id=" + start.workflowId());
        return poll(start.workflowId());
    }

    private int poll(String workflowId) {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        String lastPrintedStatus = null;
        while (System.currentTimeMillis() < deadline) {
            BackfillStatusResponse snapshot = backfillAdminService.getStatus(workflowId);
            if (!snapshot.executionStatus().equals(lastPrintedStatus)) {
                System.out.printf("[%s] status=%s%n",
                        DateTimeFormatter.ISO_LOCAL_TIME.format(java.time.LocalTime.now()),
                        snapshot.executionStatus());
                lastPrintedStatus = snapshot.executionStatus();
            }
            String exec = snapshot.executionStatus();
            switch (exec) {
                case "COMPLETED" -> {
                    return reportCompleted(snapshot.result());
                }
                case "FAILED", "TIMED_OUT", "CANCELED", "TERMINATED" -> {
                    System.err.println("Workflow ended in Temporal status: " + exec);
                    return 3;
                }
                case "NOT_FOUND" -> {
                    System.err.println("Workflow not found — Temporal may not yet have indexed it; retrying...");
                }
                default -> {
                    // RUNNING — keep polling.
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Polling interrupted");
                return 3;
            }
        }
        System.err.println("Polling timed out after " + (MAX_WAIT_MS / 60_000L) + " minutes");
        return 3;
    }

    private int reportCompleted(BackfillResult result) {
        if (result == null) {
            System.err.println("Workflow completed but result payload was null — likely a Temporal SDK decode failure");
            return 3;
        }
        System.out.println();
        System.out.println("=== Backfill Completed ===");
        System.out.println("Status:           " + result.status());
        System.out.println("Tenant:           " + result.tenantId());
        System.out.println("Dry run:          " + result.dryRun());
        System.out.println("Started at:       " + result.startedAt());
        System.out.println("Completed at:     " + result.completedAt());
        System.out.println("Total attempted:  " + result.totalAttempted());
        System.out.println("Total posted:     " + result.totalPosted());
        System.out.println("Already existed:  " + result.totalAlreadyExists());
        System.out.println("Failed:           " + result.totalFailed());
        if (result.refusalReason() != null) {
            System.out.println("Refusal reason:   " + result.refusalReason());
        }
        System.out.println("By event type:");
        for (BackfillEventTypeCount c : result.byEventType()) {
            System.out.printf("  %-25s attempted=%d posted=%d alreadyExists=%d failed=%d%n",
                    c.eventType(), c.attempted(), c.posted(), c.alreadyExists(), c.failed());
        }
        return switch (result.status()) {
            case SUCCESS -> 0;
            case PARTIAL_FAILURE -> 1;
            case REFUSED -> 2;
        };
    }

    private static LocalDate parseDate(String value, String argName) {
        if (value == null || value.isBlank()) {
            throw new BadInputException(argName + " is required (ISO format YYYY-MM-DD)");
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new BadInputException(argName + " is not a valid ISO date: " + value);
        }
    }

    private static List<BackfillEventType> parseEventTypes(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(BackfillEventType::valueOf)
                    .toList();
        } catch (IllegalArgumentException e) {
            throw new BadInputException("--cia.backfill.event-types contains an unknown value: " + e.getMessage()
                    + " (valid: " + Arrays.toString(BackfillEventType.values()) + ")");
        }
    }

    private static String usage() {
        return """
                Usage:
                  java -jar cia-api.jar \\
                    --spring.main.web-application-type=NONE \\
                    --cia.backfill.enabled=true \\
                    --cia.backfill.tenant=<tenant_id> \\
                    --cia.backfill.from=YYYY-MM-DD \\
                    --cia.backfill.to=YYYY-MM-DD \\
                    [--cia.backfill.event-types=POLICY_APPROVED,CLAIM_SETTLED,...] \\
                    [--cia.backfill.dry-run=true|false]
                """;
    }

    private static final class BadInputException extends RuntimeException {
        BadInputException(String message) { super(message); }
    }
}
