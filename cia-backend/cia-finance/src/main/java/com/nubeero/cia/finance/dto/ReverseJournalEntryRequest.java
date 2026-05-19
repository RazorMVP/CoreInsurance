package com.nubeero.cia.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Wire contract for {@code POST /api/v1/finance/journal-entries/{id}/reverse}.
 * The reason is required so the audit trail records <em>why</em> the
 * reversal happened — the reversal JE's narrative is built as
 * {@code "REVERSAL of JE {originalId}: {reason}"} (d10).
 */
public record ReverseJournalEntryRequest(
    @NotBlank
    @Size(max = 500)
    String reason
) {}
