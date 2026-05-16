package com.nubeero.cia.workflow.backfill;

import java.time.LocalDate;

/**
 * Activity input: process one chunk of source-table rows for a single event
 * type. The workflow advances {@code offset} by {@code limit} until the
 * activity reports {@code exhausted = true} (i.e. it returned fewer than
 * {@code limit} rows).
 *
 * <p>{@code tenantId} travels with every chunk so the
 * {@code TenantAwareWorkerInterceptor} (and a defensive set inside the
 * activity impl) can bind the Hibernate multi-tenant resolver before any
 * query fires.
 */
public record BackfillChunkRequest(
        String tenantId,
        String requestedBy,
        BackfillEventType eventType,
        LocalDate fromDate,
        LocalDate toDate,
        int offset,
        int limit,
        boolean dryRun) {
}
