package com.nubeero.cia.finance.gl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link JournalEntryLine}. Slice 1.4 (gateway)
 * uses this exclusively from {@link TrialBalanceService} — there is no
 * caller that fetches lines outside the trial-balance aggregation, and
 * direct CRUD on lines is forbidden (mutation only via
 * {@link JournalEntryService}).
 *
 * <p>{@link #aggregateByAccountAsOf(java.time.LocalDate)} is the single
 * query that powers {@code GET /api/v1/finance/trial-balance}. The filter
 * predicate matches D4=A: {@code business_date <= :asOf} (economic-date,
 * cumulative-since-inception). Soft-deleted journal entries are excluded by
 * the {@code je.deletedAt IS NULL} guard so a future hypothetical voided JE
 * doesn't pollute the trial balance.
 */
public interface JournalEntryLineRepository extends JpaRepository<JournalEntryLine, UUID> {

    /**
     * Returns one row per chart-of-account that has any line activity at or
     * before {@code asOf} (interpreted on the {@code business_date} of the
     * containing journal entry).
     *
     * <p>Row shape (Object[]):
     * <ol>
     *   <li>{@code account_id} — UUID</li>
     *   <li>{@code account_code} — String</li>
     *   <li>{@code account_name} — String</li>
     *   <li>{@code account_type} — String (enum name)</li>
     *   <li>{@code debit_total} — BigDecimal (never null; sums to ZERO when absent)</li>
     *   <li>{@code credit_total} — BigDecimal (never null; sums to ZERO when absent)</li>
     * </ol>
     *
     * <p>Ordered by account code ascending so the response is
     * deterministically diffable across runs (key for the 100-JE
     * reconciliation IT and for end-of-day finance reviews).
     */
    @Query("""
        SELECT line.account.id,
               line.account.code,
               line.account.name,
               line.account.accountType,
               COALESCE(SUM(line.debitAmount), 0),
               COALESCE(SUM(line.creditAmount), 0)
          FROM JournalEntryLine line
          JOIN line.journalEntry je
         WHERE je.deletedAt IS NULL
           AND line.deletedAt IS NULL
           AND je.businessDate <= :asOf
         GROUP BY line.account.id, line.account.code, line.account.name, line.account.accountType
         ORDER BY line.account.code ASC
        """)
    List<Object[]> aggregateByAccountAsOf(@Param("asOf") LocalDate asOf);

    /**
     * Grand totals across every account on or before {@code asOf} — same
     * filter as {@link #aggregateByAccountAsOf}. Used by
     * {@link TrialBalanceService} to populate the footer summary (D3=A).
     *
     * <p>Row shape (Object[]):
     * <ol>
     *   <li>{@code total_debits} — BigDecimal (never null)</li>
     *   <li>{@code total_credits} — BigDecimal (never null)</li>
     *   <li>{@code line_count} — Long</li>
     * </ol>
     */
    /**
     * Returned as {@code List<Object[]>} rather than {@code Object[]} so the
     * call goes through {@code getResultList()} instead of {@code
     * getSingleResult()}. Hibernate 6's {@code getSingleResult()} on a
     * multi-column aggregate JPQL query returns a wrapped
     * {@code Object[]{Object[]}} that breaks the caller's cast — the
     * existing {@link #aggregateByAccountAsOf} pattern (which returns a
     * list and takes {@code .get(0)} downstream) is the workaround.
     * Aggregate query is guaranteed to produce exactly one row.
     */
    @Query("""
        SELECT COALESCE(SUM(line.debitAmount), 0),
               COALESCE(SUM(line.creditAmount), 0),
               COUNT(line.id)
          FROM JournalEntryLine line
          JOIN line.journalEntry je
         WHERE je.deletedAt IS NULL
           AND line.deletedAt IS NULL
           AND je.businessDate <= :asOf
        """)
    List<Object[]> totalsAsOf(@Param("asOf") LocalDate asOf);

    /**
     * Used by the 100-JE reconciliation IT to take a hash-friendly snapshot
     * of every posted line below the aggregation level. Not exposed via any
     * controller — pure test affordance.
     */
    @Query("""
        SELECT line
          FROM JournalEntryLine line
          JOIN FETCH line.journalEntry je
          JOIN FETCH line.account
         WHERE je.deletedAt IS NULL
           AND line.deletedAt IS NULL
           AND je.businessDate <= :asOf
         ORDER BY je.businessDate ASC, je.id ASC, line.lineNo ASC
        """)
    List<JournalEntryLine> findAllLinesAsOf(@Param("asOf") LocalDate asOf);

    /**
     * Convenience for the 100-JE reconciliation IT: the net of
     * (total debits − total credits) across every line at or before
     * {@code asOf}. The acceptance gate is that this is exactly
     * {@link BigDecimal#ZERO} (scale-aware via {@code compareTo}).
     */
    @Query("""
        SELECT COALESCE(SUM(line.debitAmount), 0) - COALESCE(SUM(line.creditAmount), 0)
          FROM JournalEntryLine line
          JOIN line.journalEntry je
         WHERE je.deletedAt IS NULL
           AND line.deletedAt IS NULL
           AND je.businessDate <= :asOf
        """)
    BigDecimal netAsOf(@Param("asOf") LocalDate asOf);
}
