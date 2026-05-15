package com.nubeero.cia.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Wire contract for {@code POST /api/v1/finance/period-locks/{periodId}/soft-close}
 * and {@code .../hard-close}. The reason is required so the {@code audit_log}
 * row and the {@code period_lock.release_reason} (on subsequent reopen)
 * carry the operational justification — auditors sample for missing
 * reasons.
 *
 * @since Module 12, Slice 1.7
 */
public record ClosePeriodRequest(
    @NotBlank
    @Size(max = 500)
    String reason
) {}
