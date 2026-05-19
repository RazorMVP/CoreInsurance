package com.nubeero.cia.workflow.backfill;

import java.util.List;

/**
 * Output of the pre-flight period-lock check (D6).
 *
 * <p>If {@code hasBlockingLocks = true}, the workflow refuses the run and
 * returns a {@link BackfillResult} with status {@code REFUSED}. The operator
 * must either narrow the date range or reopen the offending periods first.
 *
 * <p>{@code blockingPeriodLabels} carries human-readable labels (e.g.
 * "Apr 2026") to surface in the structured error so the admin UI can render
 * a list rather than a wall of UUIDs.
 */
public record BackfillPreflightResult(
        boolean hasBlockingLocks,
        List<String> blockingPeriodLabels,
        String summary) {
}
