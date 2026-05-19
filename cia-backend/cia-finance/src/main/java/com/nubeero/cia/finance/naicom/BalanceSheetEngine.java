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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NAICOM N02 — Annual Balance Sheet.
 *
 * <p>Module 12 Phase 4 Slice 4.3. Reuses {@link TrialBalanceService}
 * (Slice 1.4) — the trial balance IS the GL substrate for the balance
 * sheet. The engine consumes the trial-balance lines as-of {@code period.endDate}
 * and re-projects them into the NAICOM balance-sheet shape (Assets,
 * Liabilities, Equity sections).
 *
 * <h2>Reconciliation guarantee</h2>
 * <p>Because this engine reads exclusively from {@code journal_entry_line}
 * via the trial-balance projection, the per-section totals it emits are
 * guaranteed to tie back to the trial balance for the same {@code asOf}
 * date. There is no risk of source-table-vs-GL divergence — every figure
 * has a JE provenance.
 *
 * <h2>Period-end vs closed-period note</h2>
 * <p>A textbook balance sheet shows assets, liabilities, and equity AFTER
 * income and expense have been closed to retained earnings at year-end.
 * Inside a fiscal year (before year-end close), the {@code INCOME} and
 * {@code EXPENSE} accounts still carry balances that haven't been swept.
 * This engine excludes I/E accounts from the BS sections it emits and
 * surfaces the unswept P&amp;L delta as {@code retainedEarningsToDate}
 * inside the equity section — the implicit retained-earnings figure that
 * would result from a close at {@code period.endDate}. Auditors see one
 * coherent equity figure; the BS balances assuming the unposted close.
 *
 * <h2>Payload shape</h2>
 * <pre>
 * {
 *   "submissionType": "BALANCE_SHEET",
 *   "period": { "id", "start", "end" },
 *   "asOf": "2026-12-31",
 *   "generatedAt": ISO-8601,
 *   "assets":      { "lines": [ {accountCode, accountName, balance}, ... ], "total": ₦ },
 *   "liabilities": { "lines": [ ... ], "total": ₦ },
 *   "equity":      { "lines": [ ... ], "total": ₦, "retainedEarningsToDate": ₦ },
 *   "balanceCheck":{ "totalAssets", "totalLiabilitiesAndEquity", "balanced": true|false,
 *                    "difference": signed_₦ }
 * }
 * </pre>
 *
 * <p>Lines within each section are ordered by {@code account_code ASC} —
 * deterministic across runs for the same input. Pure read engine: no DB
 * writes; orchestrator (Slice 4.9) owns the submission upsert.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BalanceSheetEngine implements NaicomSubmissionEngine {

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final TrialBalanceService trialBalanceService;

    @Override
    public NaicomSubmissionType type() {
        return NaicomSubmissionType.BALANCE_SHEET;
    }

    @Override
    public Map<String, Object> computePayload(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        // Trial balance as-of period_end gives the canonical net positions
        // for every account. Sourced exclusively from journal_entry_line
        // — no divergence risk vs the GL.
        TrialBalanceResponse tb = trialBalanceService.trialBalanceAsOf(period.getEndDate());

        Map<String, Object> assets = sectionFor(tb.lines(), AccountType.ASSET, true);
        Map<String, Object> liabilities = sectionFor(tb.lines(), AccountType.LIABILITY, false);
        Map<String, Object> equity = sectionFor(tb.lines(), AccountType.EQUITY, false);

        // Retained earnings to date = (INCOME credit balances) − (EXPENSE debit balances).
        // INCOME accounts normally carry credit balances; EXPENSE accounts debit balances.
        // The figure is what a year-end close would transfer into equity.
        BigDecimal income = sumNet(tb.lines(), AccountType.INCOME, false);
        BigDecimal expense = sumNet(tb.lines(), AccountType.EXPENSE, true);
        BigDecimal retainedEarningsToDate = income.subtract(expense);
        equity.put("retainedEarningsToDate", retainedEarningsToDate);

        BigDecimal totalAssets = (BigDecimal) assets.get("total");
        BigDecimal totalLiab = (BigDecimal) liabilities.get("total");
        BigDecimal totalEquity = (BigDecimal) equity.get("total");
        BigDecimal totalLiabAndEquity = totalLiab.add(totalEquity).add(retainedEarningsToDate);
        BigDecimal difference = totalAssets.subtract(totalLiabAndEquity);

        Map<String, Object> balanceCheck = new LinkedHashMap<>();
        balanceCheck.put("totalAssets", totalAssets);
        balanceCheck.put("totalLiabilitiesAndEquity", totalLiabAndEquity);
        balanceCheck.put("balanced", difference.signum() == 0);
        balanceCheck.put("difference", difference);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("submissionType", NaicomSubmissionType.BALANCE_SHEET.name());
        payload.put("period", periodMeta(period));
        payload.put("asOf", period.getEndDate().toString());
        payload.put("generatedAt", Instant.now().toString());
        payload.put("assets", assets);
        payload.put("liabilities", liabilities);
        payload.put("equity", equity);
        payload.put("balanceCheck", balanceCheck);

        log.info("Balance Sheet computed as-of {} — assets {}, liab+equity {}, retainedEarningsToDate {}, balanced={}",
            period.getEndDate(), totalAssets, totalLiabAndEquity, retainedEarningsToDate, difference.signum() == 0);

        return payload;
    }

    /**
     * One BS section (Assets / Liabilities / Equity).
     *
     * @param normalSideDebit true when the account's natural balance side is
     *                        debit (assets, expenses); false for credit-side
     *                        accounts (liabilities, equity, income). The
     *                        section's emitted balance is signed positive
     *                        when the account is on its expected side, and
     *                        signed negative when it has flipped (auditor
     *                        signal of unusual position).
     */
    private Map<String, Object> sectionFor(List<TrialBalanceLine> lines, AccountType type, boolean normalSideDebit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (TrialBalanceLine line : lines) {
            if (line.accountType() != type) continue;
            BigDecimal balance = normalSideDebit
                ? line.debitBalance().subtract(line.creditBalance())
                : line.creditBalance().subtract(line.debitBalance());
            if (balance.signum() == 0) continue;  // skip zero rows
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("accountCode", line.accountCode());
            row.put("accountName", line.accountName());
            row.put("balance", balance);
            rows.add(row);
            total = total.add(balance);
        }
        // Sort by accountCode ASC for deterministic output (the TB already
        // emits in that order per its repository ORDER BY, but we re-sort
        // defensively in case the upstream contract changes).
        rows.sort((a, b) -> ((String) a.get("accountCode")).compareTo((String) b.get("accountCode")));

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("lines", rows);
        section.put("total", total);
        return section;
    }

    private BigDecimal sumNet(List<TrialBalanceLine> lines, AccountType type, boolean normalSideDebit) {
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

    private static Map<String, Object> periodMeta(FiscalPeriod period) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", period.getId().toString());
        meta.put("start", period.getStartDate().toString());
        meta.put("end", period.getEndDate().toString());
        return meta;
    }
}
