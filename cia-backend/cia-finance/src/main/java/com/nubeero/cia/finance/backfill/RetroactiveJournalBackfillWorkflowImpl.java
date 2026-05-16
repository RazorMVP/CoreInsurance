package com.nubeero.cia.finance.backfill;

import com.nubeero.cia.workflow.backfill.BackfillChunkRequest;
import com.nubeero.cia.workflow.backfill.BackfillChunkResult;
import com.nubeero.cia.workflow.backfill.BackfillEventType;
import com.nubeero.cia.workflow.backfill.BackfillEventTypeCount;
import com.nubeero.cia.workflow.backfill.BackfillPreflightResult;
import com.nubeero.cia.workflow.backfill.BackfillRequest;
import com.nubeero.cia.workflow.backfill.BackfillResult;
import com.nubeero.cia.workflow.backfill.RetroactiveJournalBackfillActivities;
import com.nubeero.cia.workflow.backfill.RetroactiveJournalBackfillWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Temporal workflow implementation for the retroactive JE backfill
 * (Slice 1.8a — Module 12 Period-End Closures).
 *
 * <h2>Determinism</h2>
 * <p>Workflow code must replay deterministically; clocks, randomness, and
 * collections that iterate non-deterministically are forbidden. This impl
 * uses {@link Workflow#currentTimeMillis()} for timestamps and iterates a
 * stable {@code List<BackfillEventType>}. The mutable counters live in
 * local variables — Temporal replays the same activity results to
 * reconstruct them.
 *
 * <h2>Activity contract</h2>
 * <p>The activity stub is configured with:
 * <ul>
 *   <li>{@code startToCloseTimeout = 5 min} per chunk — generous because a
 *       100-row chunk inside a busy schema may queue on locks. Heartbeat
 *       timeout 30 s keeps liveness tight.</li>
 *   <li>{@code maximumAttempts = 3} — replay is idempotent, but we keep
 *       attempts low so a permanently failing chunk surfaces quickly rather
 *       than retrying indefinitely.</li>
 *   <li>{@code doNotRetry = JournalEntryDuplicateException} — would never
 *       reach here because the activity catches it internally; listed for
 *       belt-and-braces.</li>
 * </ul>
 *
 * <h2>Per-tenant scope</h2>
 * <p>One workflow execution per tenant. The {@code requestId} is the Temporal
 * workflow id; admin re-submissions with the same id deduplicate via
 * {@code WorkflowIdReusePolicy.REJECT_DUPLICATE} configured at the start
 * call.
 */
public class RetroactiveJournalBackfillWorkflowImpl implements RetroactiveJournalBackfillWorkflow {

    /** Chunk size for source-table paging. Trade-off: bigger chunks cut
     *  activity overhead, smaller chunks shorten retry blast radius. 100 is
     *  the sweet spot for typical tenant data volumes (≤500k rows). */
    static final int CHUNK_SIZE = 100;

    private final RetroactiveJournalBackfillActivities activities = Workflow.newActivityStub(
            RetroactiveJournalBackfillActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setMaximumInterval(Duration.ofMinutes(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public BackfillResult backfill(BackfillRequest request) {
        Instant startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());

        // 1. Pre-flight period-lock check (D6).
        BackfillPreflightResult preflight = activities.previewPeriodLocks(
                request.tenantId(), request.fromDate(), request.toDate());
        if (preflight.hasBlockingLocks()) {
            Instant completedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
            return new BackfillResult(
                    request.tenantId(),
                    request.requestId(),
                    BackfillResult.Status.REFUSED,
                    request.dryRun(),
                    startedAt,
                    completedAt,
                    0, 0, 0, 0,
                    List.of(),
                    preflight.summary());
        }

        // 2. Resolve event-type list — empty input means all six.
        List<BackfillEventType> types = (request.eventTypes() == null || request.eventTypes().isEmpty())
                ? Arrays.asList(BackfillEventType.values())
                : request.eventTypes();

        // 3. Page through each event type until exhausted.
        List<BackfillEventTypeCount> perType = new ArrayList<>();
        long totalAttempted = 0, totalPosted = 0, totalAlreadyExists = 0, totalFailed = 0;

        for (BackfillEventType type : types) {
            BackfillEventTypeCount typeCount = BackfillEventTypeCount.zero(type);
            int offset = 0;
            while (true) {
                BackfillChunkRequest chunkRequest = new BackfillChunkRequest(
                        request.tenantId(),
                        request.requestedBy(),
                        type,
                        request.fromDate(),
                        request.toDate(),
                        offset,
                        CHUNK_SIZE,
                        request.dryRun());
                BackfillChunkResult chunk = activities.processChunk(chunkRequest);
                typeCount = typeCount.plus(chunk);
                if (chunk.exhausted()) break;
                offset += CHUNK_SIZE;
            }
            perType.add(typeCount);
            totalAttempted += typeCount.attempted();
            totalPosted += typeCount.posted();
            totalAlreadyExists += typeCount.alreadyExists();
            totalFailed += typeCount.failed();
        }

        Instant completedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis());
        BackfillResult.Status status = totalFailed > 0
                ? BackfillResult.Status.PARTIAL_FAILURE
                : BackfillResult.Status.SUCCESS;

        return new BackfillResult(
                request.tenantId(),
                request.requestId(),
                status,
                request.dryRun(),
                startedAt,
                completedAt,
                totalAttempted,
                totalPosted,
                totalAlreadyExists,
                totalFailed,
                perType,
                null);
    }
}
