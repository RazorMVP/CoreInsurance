package com.nubeero.cia.workflow.backfill;

/**
 * Per-event-type breakdown row in {@link BackfillResult}.
 *
 * <p>{@code attempted} counts every source row the activity considered.
 * {@code posted} counts rows that produced a new JE (or, in dry-run, would
 * have). {@code alreadyExists} counts rows where the idempotency triple
 * already had a JE — the canonical "this is replay-safe" signal. {@code
 * failed} counts rows that raised an exception other than the idempotency
 * duplicate (e.g. {@code InactiveAccountException}, malformed currency).
 *
 * <p>Invariant enforced by the workflow: {@code attempted ==
 * posted + alreadyExists + failed}.
 */
public record BackfillEventTypeCount(
        BackfillEventType eventType,
        long attempted,
        long posted,
        long alreadyExists,
        long failed) {

    public static BackfillEventTypeCount zero(BackfillEventType type) {
        return new BackfillEventTypeCount(type, 0, 0, 0, 0);
    }

    public BackfillEventTypeCount plus(BackfillChunkResult chunk) {
        return new BackfillEventTypeCount(
                eventType,
                attempted + chunk.attempted(),
                posted + chunk.posted(),
                alreadyExists + chunk.alreadyExists(),
                failed + chunk.failed());
    }
}
