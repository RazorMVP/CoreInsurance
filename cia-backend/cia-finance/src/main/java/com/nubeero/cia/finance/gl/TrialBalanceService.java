package com.nubeero.cia.finance.gl;

import com.nubeero.cia.finance.dto.TrialBalanceFooter;
import com.nubeero.cia.finance.dto.TrialBalanceLine;
import com.nubeero.cia.finance.dto.TrialBalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Assembles the trial balance for a tenant at a given reporting date.
 *
 * <p>D4=A — {@code asOf} filters on {@code business_date} (economic date),
 * not {@code posting_date}. Cumulative since inception: every line of every
 * non-deleted journal entry with {@code business_date <= asOf} contributes.
 *
 * <p>D3=A — response shape is a flat list per-account plus a footer
 * summary. Tree assembly (if a tenant requests it for presentation) is the
 * caller's job; this service emits a deterministic flat shape ordered by
 * account code ascending.
 *
 * <p>Why the per-account presentation nets to a single side: a real-world
 * trial balance shows each account in either the debit column or the
 * credit column, never both. The aggregation query returns
 * {@code (Σ debits, Σ credits)} per account; we compute the net here and
 * emit it on the appropriate side.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrialBalanceService {

    /**
     * BigDecimal scale used in trial balance output. Matches the schema's
     * {@code DECIMAL(18,2)} and d9 (≤ 2 decimal places). Quantising here
     * guarantees the JSON response uses {@code "1234.00"} not
     * {@code "1234"} or {@code "1234.000000000"} — important for
     * deterministic equality in the 100-JE reconciliation evidence file.
     */
    private static final int SCALE = 2;

    private final JournalEntryLineRepository lineRepository;
    private final Clock clock;

    public TrialBalanceResponse trialBalanceAsOf(LocalDate asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("asOf is required");
        }
        Instant generatedAt = Instant.now(clock);

        List<Object[]> rows = lineRepository.aggregateByAccountAsOf(asOf);
        List<TrialBalanceLine> lines = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            UUID accountId = (UUID) row[0];
            String accountCode = (String) row[1];
            String accountName = (String) row[2];
            AccountType accountType = (AccountType) row[3];
            BigDecimal debits = scale((BigDecimal) row[4]);
            BigDecimal credits = scale((BigDecimal) row[5]);
            BigDecimal net = debits.subtract(credits);
            // Net positive → debit balance; net negative → credit balance (sign flipped).
            BigDecimal debitBalance = net.signum() > 0 ? net : BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
            BigDecimal creditBalance = net.signum() < 0 ? net.negate() : BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY);
            lines.add(new TrialBalanceLine(
                accountId, accountCode, accountName, accountType, debitBalance, creditBalance));
        }

        // List<Object[]> rather than Object[] — see repository javadoc.
        Object[] totals = lineRepository.totalsAsOf(asOf).get(0);
        BigDecimal totalDebits = scale((BigDecimal) totals[0]);
        BigDecimal totalCredits = scale((BigDecimal) totals[1]);
        long lineCount = ((Number) totals[2]).longValue();
        boolean balanced = totalDebits.compareTo(totalCredits) == 0;
        TrialBalanceFooter footer = new TrialBalanceFooter(totalDebits, totalCredits, balanced, lineCount);

        return new TrialBalanceResponse(asOf, generatedAt, lines, footer);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }
}
