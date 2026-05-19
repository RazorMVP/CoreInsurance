package com.nubeero.cia.workflow.backfill;

import java.time.LocalDate;
import java.util.List;

/**
 * Input to {@code RetroactiveJournalBackfillWorkflow.backfill}.
 *
 * <p>Slice 1.8a (Module 12 — Period-End Closures). One workflow execution per
 * tenant; the tenant id is carried in every chunk payload so the
 * {@code TenantAwareWorkerInterceptor} can re-establish the
 * {@link com.nubeero.cia.common.tenant.TenantContext} on the worker thread
 * before the activity runs.
 *
 * <p>{@code eventTypes} is optional — an empty list means "all six event
 * types." Callers running a targeted re-replay (e.g. only POLICY_APPROVED
 * because the others are healthy) pass a narrowed list.
 *
 * <p>{@code dryRun = true} causes activities to count rows that <em>would</em>
 * be posted (those without an existing JE for the same idempotency triple)
 * but skip the actual {@code JournalEntryService.post} call, leaving the GL
 * untouched. Used to size and validate a planned backfill before running it
 * for real.
 *
 * @param tenantId         tenant whose schema the worker should bind to
 * @param requestId        idempotency token; also used as the Temporal
 *                         workflow id so re-submissions deduplicate
 * @param requestedBy      JWT subject of the admin who triggered the run
 * @param fromDate         inclusive lower bound on the business date
 * @param toDate           inclusive upper bound on the business date
 * @param eventTypes       narrowed event-type list or empty for all
 * @param dryRun           skip the actual post when {@code true}
 */
public record BackfillRequest(
        String tenantId,
        String requestId,
        String requestedBy,
        LocalDate fromDate,
        LocalDate toDate,
        List<BackfillEventType> eventTypes,
        boolean dryRun) {
}
