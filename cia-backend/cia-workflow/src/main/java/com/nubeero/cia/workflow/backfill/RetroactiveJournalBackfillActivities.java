package com.nubeero.cia.workflow.backfill;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Activity interface for the retroactive JE backfill (Slice 1.8a).
 *
 * <p>The implementation lives in {@code cia-finance} because it must call
 * {@link com.nubeero.cia.common.tenant.TenantContext}-aware Hibernate
 * queries (against {@code policy}, {@code claim}, {@code endorsement}, etc.)
 * and the same {@code SubledgerPostingService} the live event listeners use.
 * Keeping the interface here lets {@code cia-finance} stay un-imported by
 * any other module — only the assembly module {@code cia-api} wires both
 * sides together via the {@code WorkerFactory}.
 *
 * <p>Each method receives {@code tenantId} explicitly inside its request
 * record so the worker thread can rebind the tenant context before any DB
 * query — Temporal worker threads are pooled and reused across tenants.
 */
@ActivityInterface
public interface RetroactiveJournalBackfillActivities {

    /**
     * Pre-flight check (D6): refuse the run if the inclusive {@code
     * [fromDate, toDate]} range crosses any HARD-closed period, or any
     * SOFT-closed period whose grace window has elapsed and would require a
     * {@code FINANCE_OVERRIDE_LOCK} role the worker thread does not carry.
     */
    @ActivityMethod
    BackfillPreflightResult previewPeriodLocks(String tenantId, LocalDate fromDate, LocalDate toDate);

    /**
     * Process one chunk of source rows. Idempotent: rows whose
     * {@code (sourceModule, sourceEventType, sourceReference)} triple is
     * already in {@code journal_entry} are counted as {@code alreadyExists}
     * and skipped without retry.
     */
    @ActivityMethod
    BackfillChunkResult processChunk(BackfillChunkRequest request);
}
