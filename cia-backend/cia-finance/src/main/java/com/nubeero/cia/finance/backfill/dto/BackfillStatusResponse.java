package com.nubeero.cia.finance.backfill.dto;

import com.nubeero.cia.workflow.backfill.BackfillResult;

/**
 * Wire contract for {@code GET /api/v1/admin/finance/backfill-journal-entries/{workflowId}}.
 *
 * <p>{@code executionStatus} is Temporal's view of the workflow ({@code RUNNING},
 * {@code COMPLETED}, {@code FAILED}, {@code CANCELED}, {@code TERMINATED},
 * {@code TIMED_OUT}, or {@code NOT_FOUND} when the workflow id is unknown).
 * {@code result} is the workflow's own verdict and is only populated once
 * {@code executionStatus = COMPLETED}; it carries the SUCCESS / PARTIAL_FAILURE
 * / REFUSED status plus the per-event-type breakdown.
 *
 * <p>This two-layer shape lets the operator distinguish a Temporal-level
 * failure (worker crash, infra issue) from a business-level refusal
 * (period locks blocked the run). The first surfaces in {@code executionStatus},
 * the second in {@code result.status}.
 *
 * @since Module 12, Slice 1.8b
 */
public record BackfillStatusResponse(
        String workflowId,
        String executionStatus,
        BackfillResult result) {

    public static BackfillStatusResponse notFound(String workflowId) {
        return new BackfillStatusResponse(workflowId, "NOT_FOUND", null);
    }
}
