package com.nubeero.cia.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Wire contract for {@code POST /api/v1/finance/period-locks/{periodId}/reopen}.
 *
 * <p>Reason is mandatory and ends up in:
 * <ul>
 *   <li>{@code period_lock.release_reason} on the HARD lock being released.</li>
 *   <li>{@code audit_log.new_value} for action {@code REOPEN}.</li>
 *   <li>The body of the {@code PeriodReopenedEvent} → CFO + compliance email.</li>
 * </ul>
 * Three audiences for the same string. NAICOM auditors specifically pull the
 * release reasons on reopened periods at year-end.
 *
 * @since Module 12, Slice 1.7
 */
public record ReopenPeriodRequest(
    @NotBlank
    @Size(max = 500)
    String reason
) {}
