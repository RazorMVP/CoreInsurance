package com.nubeero.cia.finance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * One leg of a {@link PostJournalEntryRequest}. The caller supplies the
 * chart-of-account code (not id) — the service translates code → entity via
 * {@code ChartOfAccountService.findByCode} so a missing/typo code surfaces
 * as a clean 404 rather than a wrapped FK error.
 *
 * <p>Exactly one of {@code debitAmount} / {@code creditAmount} must be
 * &gt; 0 (matches DB CHECK {@code ck_journal_entry_line_amount}). The
 * service validates the XOR before the round-trip so misuse fails at 422
 * instead of cascading into a constraint violation.
 *
 * <p>Optional dimension columns ({@code cohortYear} / {@code portfolioId} /
 * {@code contractGroupId} / {@code holdingId}) are filled by later slices
 * when they post on behalf of measurement modules. Manual postings in
 * Slice 1.4 leave them null.
 */
public record JournalEntryLineRequest(
    @NotBlank
    String accountCode,

    @NotNull
    @DecimalMin("0.00")
    BigDecimal debitAmount,

    @NotNull
    @DecimalMin("0.00")
    BigDecimal creditAmount,

    String currencyCode,

    Integer cohortYear,

    UUID portfolioId,

    UUID contractGroupId,

    UUID holdingId,

    Map<String, Object> dimensionTags
) {}
