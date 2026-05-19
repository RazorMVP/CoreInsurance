package com.nubeero.cia.workflow.backfill;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal workflow contract for the per-tenant retroactive JE backfill.
 *
 * <p>Slice 1.8a (Module 12 — Period-End Closures). The workflow:
 * <ol>
 *   <li>Runs {@code previewPeriodLocks} — refuses the run if any HARD or
 *       SOFT-past-grace period sits inside the requested range.</li>
 *   <li>Iterates the requested event types (or all six). For each, drives
 *       {@code processChunk} with a paging cursor of size 100 until the
 *       activity reports {@code exhausted = true}.</li>
 *   <li>Aggregates the per-chunk counts into a {@link BackfillResult} and
 *       returns it.</li>
 * </ol>
 *
 * <p>The workflow id is the caller-supplied {@code requestId}. Temporal
 * rejects a duplicate {@code WorkflowIdReusePolicy.REJECT_DUPLICATE}, so the
 * admin UI can submit the same request twice without producing two parallel
 * runs.
 */
@WorkflowInterface
public interface RetroactiveJournalBackfillWorkflow {

    @WorkflowMethod
    BackfillResult backfill(BackfillRequest request);
}
