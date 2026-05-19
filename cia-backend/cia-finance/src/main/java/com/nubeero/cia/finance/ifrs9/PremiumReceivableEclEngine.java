package com.nubeero.cia.finance.ifrs9;

import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.JournalEntryResponse;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import com.nubeero.cia.finance.gl.JournalEntryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * IFRS 9 §5.5.15 simplified-approach ECL engine for premium receivables.
 * Module 12 Phase 3 Slice 3.6.
 *
 * <p>Unlike Slice 3.5's general 3-stage model, this engine uses the
 * provision-matrix approach: aging buckets × default rates → lifetime ECL.
 * No stage transitions, no SICR detection — appropriate for high-volume
 * short-tenor trade receivables where per-debtor credit data isn't
 * available.
 *
 * <h2>Account routing</h2>
 * <pre>
 *   Increase: Dr 5350 (ECL expense — premium receivables)
 *             Cr 1340 (ECL allowance — premium receivable, contra-asset)
 *   Reversal: Dr 1340 / Cr 5350
 * </pre>
 *
 * <p>Unlike investment ECL on AC (Slice 3.5), premium receivables use a
 * separate contra-asset allowance account. The BS shows gross receivable
 * (via debit_notes) and allowance (1340) separately; the net carrying
 * amount is computed at presentation time.
 *
 * <h2>Cumulative prior ECL via JE aggregate</h2>
 * <p>No persisted state table — cumulative prior ECL is computed by SQL
 * aggregate over journal_entry_line: net debits on 5350 across prior
 * periods. This keeps the engine stateless and the JE table the source
 * of truth.
 *
 * <h2>Idempotency</h2>
 * <p>JE existence check on {@code (ifrs9, "PREMIUM_RECEIVABLE_ECL",
 * period_id:PREMIUM_RECEIVABLE)} source-reference triple. Aggregate per
 * period — no per-debtor granularity.
 *
 * <h2>v1 simplifications</h2>
 * <ul>
 *   <li>Aging buckets + rates are admin-supplied. v2 will compute aging
 *       from debit_notes / receipts and pull rates from a per-tenant
 *       provision-matrix table.</li>
 *   <li>Aggregate per period, no per-debtor / per-broker / per-customer
 *       breakdown. v2 may add the dimensional split for §B5.5.36
 *       disclosure detail.</li>
 *   <li>No forward-looking adjustment factor (§5.5.18). The admin bakes
 *       it into the default rates supplied in the request.</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class PremiumReceivableEclEngine {

    /** ECL allowance — Premium receivable (V32, ifrs9_role=ECL_ALLOWANCE). */
    static final String COA_ECL_ALLOWANCE_PREMIUM = "1340";
    /** ECL expense — Premium receivables (V32, ifrs9_role=ECL_EXPENSE). */
    static final String COA_ECL_EXPENSE_PREMIUM = "5350";

    static final String MODULE_IFRS9 = "ifrs9";
    static final String EVENT_PREMIUM_RECEIVABLE_ECL = "PREMIUM_RECEIVABLE_ECL";

    /** Single canonical source reference per period — there's no per-debtor dimension in v1. */
    static final String REFERENCE_SUFFIX = ":PREMIUM_RECEIVABLE";

    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JournalEntryService journalEntryService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Recognise the §5.5.15 lifetime ECL movement for {@code periodId} from
     * the supplied provision matrix.
     */
    public PremiumReceivableEclResult recognise(UUID periodId,
                                                 List<RecognisePremiumReceivableEclRequest.AgingBucket> buckets) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        String idempotencyRef = period.getId() + REFERENCE_SUFFIX;

        log.info("Premium-receivable ECL starting for period {} ({} → {}); {} aging buckets",
            periodId, period.getStartDate(), period.getEndDate(), buckets.size());

        // Idempotency check — short-circuit if already recognised.
        if (jeAlreadyPosted(idempotencyRef)) {
            log.info("Premium-receivable ECL already posted for period {} — short-circuit", periodId);
            return buildAlreadyDoneResult(period.getId(), buckets, idempotencyRef);
        }

        // Compute target lifetime ECL + per-bucket breakdown
        List<PremiumReceivableEclResult.BucketBreakdown> breakdown = new ArrayList<>(buckets.size());
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal targetEcl = BigDecimal.ZERO;
        for (var bucket : buckets) {
            BigDecimal outstanding = scale(bucket.outstandingAmount());
            BigDecimal rate = bucket.defaultRate();
            BigDecimal bucketEcl = scale(outstanding.multiply(rate));
            totalOutstanding = totalOutstanding.add(outstanding);
            targetEcl = targetEcl.add(bucketEcl);
            breakdown.add(new PremiumReceivableEclResult.BucketBreakdown(
                bucket.label(), outstanding, rate, bucketEcl));
        }
        targetEcl = scale(targetEcl);

        // Delta = target − cumulative prior
        BigDecimal priorEcl = cumulativePriorEcl(period);
        BigDecimal delta = scale(targetEcl.subtract(priorEcl));

        String direction = delta.signum() > 0 ? "INCREASE"
                         : delta.signum() < 0 ? "REVERSAL"
                         : "NO_CHANGE";

        UUID jeId = null;
        if (delta.signum() != 0) {
            jeId = postJe(period, delta, breakdown);
        }

        log.info("Premium-receivable ECL complete for period {} — target {} (vs prior {}), Δ {} ({})",
            periodId, targetEcl, priorEcl, delta, direction);

        return new PremiumReceivableEclResult(
            period.getId(),
            scale(totalOutstanding),
            targetEcl,
            priorEcl,
            delta,
            direction,
            jeId,
            breakdown);
    }

    /**
     * Pure: lifetime ECL = Σ (outstanding × rate), rounded HALF_UP at scale 2.
     * Static + unit-testable.
     */
    static BigDecimal computeLifetimeEcl(List<RecognisePremiumReceivableEclRequest.AgingBucket> buckets) {
        if (buckets == null || buckets.isEmpty()) return BigDecimal.ZERO;
        return buckets.stream()
            .map(b -> b.outstandingAmount().multiply(b.defaultRate()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private boolean jeAlreadyPosted(String idempotencyRef) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry " +
            "WHERE source_module = ? AND source_event_type = ? AND source_reference = ? " +
            "AND deleted_at IS NULL",
            Long.class,
            MODULE_IFRS9, EVENT_PREMIUM_RECEIVABLE_ECL, idempotencyRef);
        return count != null && count > 0;
    }

    /**
     * Cumulative ECL allowance recognised in all PRIOR periods — derived
     * from journal_entry_line aggregate on account 5350. Net debits on
     * 5350 = cumulative expense recognised = cumulative allowance on 1340.
     */
    private BigDecimal cumulativePriorEcl(FiscalPeriod period) {
        BigDecimal sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(l.debit_amount - l.credit_amount), 0) " +
            "FROM journal_entry_line l " +
            "JOIN journal_entry je ON je.id = l.journal_entry_id " +
            "JOIN chart_of_account coa ON coa.id = l.account_id " +
            "JOIN fiscal_period fp ON fp.id = je.period_id " +
            "WHERE je.source_event_type = ? " +
            "  AND coa.code = ? " +
            "  AND fp.end_date < ? " +
            "  AND l.deleted_at IS NULL " +
            "  AND je.deleted_at IS NULL",
            BigDecimal.class,
            EVENT_PREMIUM_RECEIVABLE_ECL, COA_ECL_EXPENSE_PREMIUM,
            java.sql.Date.valueOf(period.getStartDate()));
        return scale(sum);
    }

    private UUID postJe(FiscalPeriod period, BigDecimal delta,
                        List<PremiumReceivableEclResult.BucketBreakdown> breakdown) {
        boolean increase = delta.signum() > 0;
        BigDecimal abs = delta.abs();

        String debitAccount = increase ? COA_ECL_EXPENSE_PREMIUM : COA_ECL_ALLOWANCE_PREMIUM;
        String creditAccount = increase ? COA_ECL_ALLOWANCE_PREMIUM : COA_ECL_EXPENSE_PREMIUM;

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            debitAccount, abs, BigDecimal.ZERO, "NGN",
            null, null, null, null, null);
        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            creditAccount, BigDecimal.ZERO, abs, "NGN",
            null, null, null, null, null);

        // Embed the provision-matrix breakdown in the narrative for
        // §B5.5.36 disclosure auditability. Compact label/amount/rate triples.
        StringBuilder narrative = new StringBuilder("Lifetime ECL on premium receivables for period ")
            .append(period.getStartDate()).append(" to ").append(period.getEndDate())
            .append(" — ").append(increase ? "increase" : "reversal").append(" of ").append(abs)
            .append(". Buckets: ");
        for (int i = 0; i < breakdown.size(); i++) {
            var b = breakdown.get(i);
            if (i > 0) narrative.append(", ");
            narrative.append(b.label())
                .append("=").append(b.outstandingAmount())
                .append("@").append(b.defaultRate())
                .append("→").append(b.bucketEcl());
        }

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            period.getEndDate(),
            MODULE_IFRS9,
            EVENT_PREMIUM_RECEIVABLE_ECL,
            period.getId() + REFERENCE_SUFFIX,
            narrative.toString(),
            List.of(debit, credit));

        JournalEntryResponse je = journalEntryService.post(request);
        return je.id();
    }

    private PremiumReceivableEclResult buildAlreadyDoneResult(
            UUID periodId,
            List<RecognisePremiumReceivableEclRequest.AgingBucket> buckets,
            String idempotencyRef) {
        UUID existingJeId = jdbcTemplate.queryForObject(
            "SELECT id FROM journal_entry " +
            "WHERE source_module = ? AND source_event_type = ? AND source_reference = ? " +
            "AND deleted_at IS NULL",
            UUID.class,
            MODULE_IFRS9, EVENT_PREMIUM_RECEIVABLE_ECL, idempotencyRef);

        List<PremiumReceivableEclResult.BucketBreakdown> breakdown = new ArrayList<>(buckets.size());
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        for (var b : buckets) {
            BigDecimal outstanding = scale(b.outstandingAmount());
            BigDecimal bucketEcl = scale(outstanding.multiply(b.defaultRate()));
            totalOutstanding = totalOutstanding.add(outstanding);
            breakdown.add(new PremiumReceivableEclResult.BucketBreakdown(
                b.label(), outstanding, b.defaultRate(), bucketEcl));
        }

        return new PremiumReceivableEclResult(
            periodId,
            scale(totalOutstanding),
            computeLifetimeEcl(buckets),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "NO_CHANGE",
            existingJeId,
            breakdown);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
