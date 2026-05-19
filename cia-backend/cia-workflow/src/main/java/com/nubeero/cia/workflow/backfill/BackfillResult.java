package com.nubeero.cia.workflow.backfill;

import java.time.Instant;
import java.util.List;

/**
 * Final output of {@code RetroactiveJournalBackfillWorkflow.backfill}.
 *
 * <p>{@code status} is the workflow's verdict:
 * <ul>
 *   <li>{@code REFUSED} — pre-flight period-lock check found HARD-closed or
 *       SOFT-past-grace periods inside the requested range; nothing was
 *       written. {@code refusalReason} is populated.</li>
 *   <li>{@code SUCCESS} — every chunk completed with zero failed rows.</li>
 *   <li>{@code PARTIAL_FAILURE} — at least one row failed inside a chunk
 *       (idempotency duplicates do <em>not</em> count as failure).</li>
 * </ul>
 *
 * <p>{@code dryRun} is echoed back so the caller can confirm what flavour of
 * run produced the counts.
 */
public record BackfillResult(
        String tenantId,
        String requestId,
        Status status,
        boolean dryRun,
        Instant startedAt,
        Instant completedAt,
        long totalAttempted,
        long totalPosted,
        long totalAlreadyExists,
        long totalFailed,
        List<BackfillEventTypeCount> byEventType,
        String refusalReason) {

    public enum Status {
        SUCCESS,
        PARTIAL_FAILURE,
        REFUSED
    }
}
