package com.nubeero.cia.finance.backfill.dto;

import com.nubeero.cia.workflow.backfill.BackfillEventType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Wire contract for {@code POST /api/v1/admin/finance/backfill-journal-entries}.
 *
 * <p>{@code eventTypes} is optional — null or empty means "all six event
 * types". {@code dryRun = true} runs the workflow without writing any JEs;
 * useful for sizing or validating a planned backfill before executing it.
 */
public record StartBackfillRequest(
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate,
        List<BackfillEventType> eventTypes,
        boolean dryRun) {
}
