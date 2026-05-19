package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.gl.AccountType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of a trial balance — totals for a single chart-of-account at the
 * reporting date.
 *
 * <p>{@code debitBalance} / {@code creditBalance} are mutually exclusive
 * (exactly one is &gt; 0; the other is {@link BigDecimal#ZERO}). The service
 * computes them by netting: if {@code Σ debits − Σ credits &gt; 0} the
 * account has a debit balance; otherwise a credit balance. This matches the
 * normal trial-balance presentation: a single column has a value, never
 * both.
 *
 * <p>Account type is included so the caller can group rows in the table
 * (Asset / Liability / Equity / Income / Expense headers) without a
 * follow-up lookup.
 */
public record TrialBalanceLine(
    UUID accountId,
    String accountCode,
    String accountName,
    AccountType accountType,
    BigDecimal debitBalance,
    BigDecimal creditBalance
) {}
