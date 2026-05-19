package com.nubeero.cia.finance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Wire contract for {@code POST /api/v1/finance/journal-entries}.
 *
 * <p>{@code businessDate} drives period assignment via
 * {@link com.nubeero.cia.finance.gl.FiscalPeriodResolver} and the V31
 * CHECK {@code businessDate <= postingDate}. The service stamps
 * {@code postingDate = today} — D3 default — callers cannot set it.
 *
 * <p>{@code sourceModule} / {@code sourceEventType} / {@code sourceReference}
 * form the idempotency triple. For manual postings (the only producer in
 * Slice 1.4) d8 prescribes using a fresh UUID-derived reference; sub-ledger
 * listeners (Slice 1.5) pass real entity ids. The controller forwards the
 * triple verbatim — no synthesis happens here.
 *
 * <p>{@code lines} must contain at least two entries (one debit, one credit
 * — the minimum for a balanced JE) and the service enforces balance before
 * INSERT.
 */
public record PostJournalEntryRequest(
    @NotNull
    LocalDate businessDate,

    @NotBlank
    @Size(max = 40)
    String sourceModule,

    @NotBlank
    @Size(max = 60)
    String sourceEventType,

    @NotBlank
    @Size(max = 100)
    String sourceReference,

    String narrative,

    @NotEmpty
    @Size(min = 2)
    @Valid
    List<JournalEntryLineRequest> lines
) {}
