package com.nubeero.cia.finance.backfill.dto;

import java.time.Instant;

/**
 * Returned to the admin caller immediately after the workflow is started.
 * The actual run is asynchronous — the caller polls Temporal (or a future
 * GET status endpoint, Slice 1.8b) using {@code workflowId}.
 */
public record StartBackfillResponse(
        String workflowId,
        String tenantId,
        boolean dryRun,
        Instant startedAt) {
}
