package com.nubeero.cia.workflow.backfill;

/**
 * Activity output: per-chunk counts.
 *
 * <p>{@code exhausted} signals the workflow that the source table yielded
 * fewer than {@code limit} rows for this {@code (eventType, fromDate,
 * toDate)} window — the workflow stops paging and moves to the next event
 * type.
 */
public record BackfillChunkResult(
        long attempted,
        long posted,
        long alreadyExists,
        long failed,
        boolean exhausted) {

    public static BackfillChunkResult empty() {
        return new BackfillChunkResult(0, 0, 0, 0, true);
    }
}
