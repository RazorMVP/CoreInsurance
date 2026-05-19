package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.finance.dto.TrialBalanceLine;
import com.nubeero.cia.finance.dto.TrialBalanceResponse;
import com.nubeero.cia.finance.gl.AccountType;
import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import com.nubeero.cia.finance.gl.TrialBalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NAICOM N03 — Quarterly Prudential Return.
 *
 * <p>Module 12 Phase 4 Slice 4.4. Computes the solvency-margin view a
 * regulator expects on each quarter-end: shareholders' funds + retained
 * earnings vs the required-capital floor implied by recent premium volume.
 *
 * <h2>Substrate: fully GL-driven</h2>
 * <p>Like {@link BalanceSheetEngine}, this engine reads exclusively from
 * {@link TrialBalanceService} (balance-sheet side) and from
 * {@code journal_entry_line} aggregates (income-statement side). No source-
 * table reads — every figure ties back to the trial balance for the same
 * {@code asOf} date. Auditor-canonical by construction.
 *
 * <h2>The simplified solvency formula</h2>
 * <p>The full NAICOM solvency-margin computation lives in the NAICOM
 * Operational Guidelines and includes:
 * <ul>
 *   <li>Admitted-asset exclusions (intangibles, deferred acquisition costs,
 *       certain receivables past a credit-rating threshold, fixed assets used
 *       in operations, etc.)</li>
 *   <li>A statutory minimum capital floor (₦10 billion for non-life under
 *       the 2021 recapitalisation regulation, subject to periodic revision)</li>
 *   <li>Tier-1 / Tier-2 capital qualifying-instrument logic for any
 *       subordinated debt the insurer carries</li>
 * </ul>
 * <p>v1 of this engine implements the principal calculation:
 * <pre>
 *   availableCapital      = totalEquity + retainedEarningsToDate
 *   minimumRequiredCapital = MIN_CAPITAL_PERCENT × periodPremiumWritten
 *   solvencyRatio          = availableCapital / minimumRequiredCapital
 *   solvent                = solvencyRatio &gt;= 1.0
 * </pre>
 * <p>The admitted-asset exclusions and the ₦10B statutory floor are
 * deliberately deferred to v2. The engine emits a {@code notes} field in the
 * payload making this simplification visible to the regulator/auditor, so
 * the submission isn't represented as a complete NAICOM Operational
 * Guideline implementation.
 *
 * <h2>Period semantics</h2>
 * <p>The engine uses the period boundary the caller provides:
 * <ul>
 *   <li>Balance-sheet figures (assets, liabilities, equity) come from
 *       {@code trialBalanceAsOf(period.endDate)} — cumulative since
 *       inception per {@code TrialBalanceService} semantics.</li>
 *   <li>Income-statement figures (premium written, claims incurred) come
 *       from {@code journal_entry_line} aggregates with
 *       {@code business_date BETWEEN period.startDate AND period.endDate}
 *       — period-bounded, not YTD.</li>
 * </ul>
 * <p>Callers wanting YTD income figures for a Q3 submission should pass a
 * YTD-shaped period (Jan 1 → Sep 30). For pure quarterly figures, pass the
 * Q3 period (Jul 1 → Sep 30). The orchestrator (Slice 4.9) gates the right
 * period for each {@link NaicomSubmissionType}.
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "PRUDENTIAL_RETURN",
 *   "period":         { "id", "start", "end" },
 *   "asOf":           "2026-09-30",
 *   "generatedAt":    ISO-8601,
 *   "balanceSheet":   { "totalAssets", "investments", "totalLiabilities",
 *                       "premiumReserves", "totalEquity",
 *                       "shareholdersFunds", "retainedEarningsToDate" },
 *   "income":         { "periodPremiumWritten", "periodClaimsIncurred" },
 *   "solvency":       { "availableCapital", "minimumRequiredCapital",
 *                       "minimumCapitalPercent", "solvencyRatio", "solvent" },
 *   "notes":          "v1 simplification disclosure"
 * }
 * </pre>
 *
 * <p>Pure read engine — no DB writes, no JE postings. Orchestrator
 * (Slice 4.9) owns the submission upsert + state machine.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PrudentialReturnEngine {

    /**
     * Required-capital percentage of net premium written. NAICOM's historical
     * benchmark for general (non-life) insurance has hovered between 15% and
     * 20% depending on the regulation year; 15% is the conservative-defensible
     * v1 default. Tenant-configurable in v2 (alongside the statutory floor).
     */
    private static final BigDecimal MIN_CAPITAL_PERCENT = new BigDecimal("0.15");
    private static final int MONEY_SCALE = 2;
    private static final int RATIO_SCALE = 4;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final TrialBalanceService trialBalanceService;
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        // ── Balance-sheet figures: cumulative since inception, as-of period_end ──
        TrialBalanceResponse tb = trialBalanceService.trialBalanceAsOf(period.getEndDate());
        List<TrialBalanceLine> lines = tb.lines();

        BigDecimal totalAssets = sumNet(lines, AccountType.ASSET, true);
        BigDecimal totalLiabilities = sumNet(lines, AccountType.LIABILITY, false);
        BigDecimal shareholdersFunds = sumNet(lines, AccountType.EQUITY, false);
        // Implicit retained earnings = (INCOME credits) − (EXPENSE debits) cumulatively.
        // Same pattern as BalanceSheetEngine — represents what a year-end close
        // would transfer into equity.
        BigDecimal incomeCumulative = sumNet(lines, AccountType.INCOME, false);
        BigDecimal expenseCumulative = sumNet(lines, AccountType.EXPENSE, true);
        BigDecimal retainedEarningsToDate = incomeCumulative.subtract(expenseCumulative);
        BigDecimal totalEquity = shareholdersFunds.add(retainedEarningsToDate);

        // Reserves: LIABILITY accounts under the 2100 parent (codes 2110-2170 cover
        // LRC BEL/RA/LC + LIC OCR/IBNR/RA/CHE per V32).
        BigDecimal premiumReserves = sumByCodePrefix(lines, AccountType.LIABILITY, "21", false);
        // Investments: ASSET accounts under the 1200 parent (codes 1210-1250 cover
        // FVPL/FVOCI/AmortisedCost securities per V32).
        BigDecimal investments = sumByCodePrefix(lines, AccountType.ASSET, "12", true);

        Map<String, Object> balanceSheet = new LinkedHashMap<>();
        balanceSheet.put("totalAssets", scale(totalAssets));
        balanceSheet.put("investments", scale(investments));
        balanceSheet.put("totalLiabilities", scale(totalLiabilities));
        balanceSheet.put("premiumReserves", scale(premiumReserves));
        balanceSheet.put("totalEquity", scale(totalEquity));
        balanceSheet.put("shareholdersFunds", scale(shareholdersFunds));
        balanceSheet.put("retainedEarningsToDate", scale(retainedEarningsToDate));

        // ── Income-statement figures: period-bounded ──
        BigDecimal periodPremiumWritten = sumPeriodCredits(period, "41"); // INCOME 4100 parent
        BigDecimal periodClaimsIncurred = sumPeriodDebits(period, "51");  // EXPENSE 5100 parent

        Map<String, Object> income = new LinkedHashMap<>();
        income.put("periodPremiumWritten", scale(periodPremiumWritten));
        income.put("periodClaimsIncurred", scale(periodClaimsIncurred));

        // ── Solvency calculation (v1 simplified) ──
        BigDecimal availableCapital = totalEquity;
        BigDecimal minimumRequiredCapital = scale(periodPremiumWritten.multiply(MIN_CAPITAL_PERCENT));

        BigDecimal solvencyRatio;
        Boolean solvent;
        if (minimumRequiredCapital.signum() == 0) {
            // No premium written this period — solvency-margin formula is
            // undefined. Emit null rather than divide-by-zero or infinite.
            solvencyRatio = null;
            solvent = null;
        } else {
            solvencyRatio = availableCapital.divide(minimumRequiredCapital, RATIO_SCALE, RoundingMode.HALF_UP);
            solvent = solvencyRatio.compareTo(BigDecimal.ONE) >= 0;
        }

        Map<String, Object> solvency = new LinkedHashMap<>();
        solvency.put("availableCapital", scale(availableCapital));
        solvency.put("minimumRequiredCapital", minimumRequiredCapital);
        solvency.put("minimumCapitalPercent", MIN_CAPITAL_PERCENT.multiply(new BigDecimal("100"))
            .setScale(MONEY_SCALE, RoundingMode.UNNECESSARY));
        solvency.put("solvencyRatio", solvencyRatio);
        solvency.put("solvent", solvent);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.PRUDENTIAL_RETURN.name());
        payload.put("period", periodMeta(period));
        payload.put("asOf", period.getEndDate().toString());
        payload.put("generatedAt", Instant.now().toString());
        payload.put("balanceSheet", balanceSheet);
        payload.put("income", income);
        payload.put("solvency", solvency);
        payload.put("notes",
            "v1 simplified formula: minimumRequiredCapital = 15% × periodPremiumWritten. "
            + "Full NAICOM Operational Guideline implementation (admitted-assets exclusions, "
            + "statutory minimum capital floor, Tier-1/Tier-2 capital classification) "
            + "is deferred to v2.");

        log.info("Prudential Return computed as-of {} — equity {}, required {}, ratio {}, solvent={}",
            period.getEndDate(), availableCapital, minimumRequiredCapital, solvencyRatio, solvent);

        return payload;
    }

    /**
     * Sums net balances on the natural side of an account type from the
     * trial balance. Mirrors {@link BalanceSheetEngine}'s
     * {@code sumOnNaturalSide} helper.
     */
    private static BigDecimal sumNet(List<TrialBalanceLine> lines, AccountType type, boolean normalSideDebit) {
        BigDecimal total = BigDecimal.ZERO;
        for (TrialBalanceLine line : lines) {
            if (line.accountType() != type) continue;
            BigDecimal balance = normalSideDebit
                ? line.debitBalance().subtract(line.creditBalance())
                : line.creditBalance().subtract(line.debitBalance());
            total = total.add(balance);
        }
        return total;
    }

    /**
     * Sums net balances for accounts whose {@code accountCode} starts with the
     * given prefix AND match the requested account type. Used to extract
     * sub-totals like "investments" (12xx ASSET) and "premium reserves" (21xx
     * LIABILITY) from a full trial balance.
     */
    private static BigDecimal sumByCodePrefix(List<TrialBalanceLine> lines, AccountType type,
                                                String codePrefix, boolean normalSideDebit) {
        BigDecimal total = BigDecimal.ZERO;
        for (TrialBalanceLine line : lines) {
            if (line.accountType() != type) continue;
            if (!line.accountCode().startsWith(codePrefix)) continue;
            BigDecimal balance = normalSideDebit
                ? line.debitBalance().subtract(line.creditBalance())
                : line.creditBalance().subtract(line.debitBalance());
            total = total.add(balance);
        }
        return total;
    }

    /**
     * Sums credit balances for INCOME accounts under {@code parentCodePrefix}
     * (e.g. "41" for Insurance revenue) over the period range. Uses
     * {@code business_date} not {@code posting_date} — same discipline as
     * {@code TrialBalanceService}'s D4=A convention.
     */
    private BigDecimal sumPeriodCredits(FiscalPeriod period, String parentCodePrefix) {
        BigDecimal sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(l.credit_amount - l.debit_amount), 0) " +
            "FROM journal_entry_line l " +
            "JOIN journal_entry je ON je.id = l.journal_entry_id " +
            "JOIN chart_of_account coa ON coa.id = l.account_id " +
            "WHERE coa.account_type = 'INCOME' " +
            "  AND coa.code LIKE ? " +
            "  AND je.business_date BETWEEN ? AND ? " +
            "  AND l.deleted_at IS NULL " +
            "  AND je.deleted_at IS NULL",
            BigDecimal.class,
            parentCodePrefix + "%",
            java.sql.Date.valueOf(period.getStartDate()),
            java.sql.Date.valueOf(period.getEndDate()));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * Symmetric to {@link #sumPeriodCredits} but for EXPENSE accounts (debit-
     * side natural balance).
     */
    private BigDecimal sumPeriodDebits(FiscalPeriod period, String parentCodePrefix) {
        BigDecimal sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(l.debit_amount - l.credit_amount), 0) " +
            "FROM journal_entry_line l " +
            "JOIN journal_entry je ON je.id = l.journal_entry_id " +
            "JOIN chart_of_account coa ON coa.id = l.account_id " +
            "WHERE coa.account_type = 'EXPENSE' " +
            "  AND coa.code LIKE ? " +
            "  AND je.business_date BETWEEN ? AND ? " +
            "  AND l.deleted_at IS NULL " +
            "  AND je.deleted_at IS NULL",
            BigDecimal.class,
            parentCodePrefix + "%",
            java.sql.Date.valueOf(period.getStartDate()),
            java.sql.Date.valueOf(period.getEndDate()));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> periodMeta(FiscalPeriod period) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", period.getId().toString());
        meta.put("start", period.getStartDate().toString());
        meta.put("end", period.getEndDate().toString());
        return meta;
    }
}
