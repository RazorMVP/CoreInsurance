package com.nubeero.cia.finance.dto;

import java.math.BigDecimal;

/**
 * Footer summary on a trial balance response (D3=A — flat list per-account
 * plus a footer summary).
 *
 * <p>{@code totalDebits} / {@code totalCredits} are the sums across every
 * line returned. {@code balanced} = {@code totalDebits.compareTo(totalCredits) == 0}
 * — pre-computed by the service so the frontend doesn't have to do
 * scale-aware BigDecimal comparison. {@code lineCount} is the count of
 * journal entry lines (not accounts) that contributed.
 */
public record TrialBalanceFooter(
    BigDecimal totalDebits,
    BigDecimal totalCredits,
    boolean balanced,
    long lineCount
) {}
