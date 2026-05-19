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
import java.util.Optional;
import java.util.UUID;

/**
 * IFRS 9 §5.5 Expected Credit Loss engine — recognises ECL movements on
 * AMORTISED_COST + FVOCI_DEBT holdings. Module 12 Phase 3 Slice 3.5.
 *
 * <h2>Routing per §5.5.1 + §5.7.10A</h2>
 * <ul>
 *   <li><b>AC (1250 debt / 1140 money-market)</b>: ECL is recognised in
 *       P&amp;L (5340) with the offsetting credit reducing the asset's
 *       carrying amount directly. The BS shows the asset net of allowance.</li>
 *   <li><b>FVOCI_DEBT (1230)</b>: §5.7.10A — ECL is recognised in P&amp;L
 *       (5340) but the offsetting credit goes to the OCI reserve (3410),
 *       NOT the asset. The BS carrying amount stays at fair value; the
 *       3410 reserve becomes the algebraic sum of FV change (Slice 3.4)
 *       plus accumulated ECL (this slice). On derecognition the §5.7.10
 *       recycling unwinds the composite.</li>
 * </ul>
 * {@link InvestmentClassification#FVPL} and {@link InvestmentClassification#FVOCI_EQUITY}
 * don't carry ECL (§5.5.1 excludes them).
 *
 * <h2>Delta-based recognition</h2>
 * <p>Admin provides the target total ECL allowance. The engine computes:
 * <pre>
 *   cumulative_prior_ecl = SUM(investment_carrying_value.ecl_movement WHERE period &lt; this)
 *   delta = target − cumulative_prior_ecl
 * </pre>
 * If {@code delta != 0}, a JE is posted and {@code ecl_movement} on the
 * carrying-value row records the signed delta.
 *
 * <h2>Stage transition</h2>
 * <p>If the request supplies {@code eclStage} different from the holding's
 * current stage, the engine updates both {@code investment_holding.ecl_stage}
 * and the carrying-value row. v1 has no automatic SICR detection — admin
 * supplies the stage based on external credit-risk review.
 *
 * <h2>Idempotency</h2>
 * <p>JE existence check on
 * {@code (paa→ifrs9 module, "ECL_RECOGNITION", period_id:holding_id)} — if
 * already posted, the engine short-circuits before any state mutation.
 * The delta computation is also naturally re-entrant (re-running after a
 * complete prior run yields delta = 0), but the explicit pre-check avoids
 * writing the second {@code ecl_movement} value of 0 over the original.
 *
 * <h2>Coexistence with prior engines</h2>
 * <p>Like Slice 3.4, this engine UPDATES the existing carrying-value row
 * when AC/FV engines have already written one for the period, and INSERTs
 * a fresh row otherwise. The upsert path goes through
 * {@link InvestmentCarryingValueRepository#findByHoldingIdAndPeriodIdAndDeletedAtIsNull}.
 *
 * <p>For AC holdings: this engine REDUCES {@code closing_balance} by the
 * ECL delta. For FVOCI_DEBT holdings: this engine LEAVES
 * {@code closing_balance} and {@code closing_fair_value} unchanged
 * (per §5.7.10A).
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class InvestmentEclEngine {

    /** ECL expense - Investment securities (V32, ifrs9_role=ECL_EXPENSE). */
    static final String COA_ECL_EXPENSE = "5340";

    /** Amortised cost - Debt securities (V32). */
    static final String COA_AC_DEBT = "1250";
    /** Money market instruments (V32). */
    static final String COA_AC_MONEY_MARKET = "1140";
    /** FVOCI debt reserve (V32, ifrs9_role=OCI_DEBT_RESERVE). */
    static final String COA_OCI_DEBT_RESERVE = "3410";

    static final String MODULE_IFRS9 = "ifrs9";
    static final String EVENT_ECL_RECOGNITION = "ECL_RECOGNITION";

    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final InvestmentHoldingRepository holdingRepository;
    private final InvestmentCarryingValueRepository carryingValueRepository;
    private final JournalEntryService journalEntryService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Recognise ECL movements for {@code periodId} across the supplied
     * (holding, target-ECL[, stage]) entries.
     */
    public EclRecognitionResult recognise(UUID periodId, List<EclInput> inputs) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        log.info("ECL recognition starting for period {} ({} → {}); {} inputs",
            periodId, period.getStartDate(), period.getEndDate(), inputs.size());

        BigDecimal totalIncrease = BigDecimal.ZERO;
        BigDecimal totalReversal = BigDecimal.ZERO;
        int withJe = 0;
        List<EclRecognitionResult.HoldingEclEntry> entries = new ArrayList<>();

        for (EclInput input : inputs) {
            InvestmentHolding holding = holdingRepository.findById(input.holdingId())
                .filter(h -> h.getDeletedAt() == null)
                .orElseThrow(() -> new InvestmentHoldingNotFoundException(input.holdingId()));

            if (!isEclEligible(holding.getClassification())) {
                log.warn("Skipping holding {} — classification {} is not ECL-eligible",
                    holding.getId(), holding.getClassification());
                continue;
            }

            // Idempotency: short-circuit if a JE for this (period, holding) already exists.
            if (jeAlreadyPosted(period.getId(), holding.getId())) {
                log.debug("Skipping holding {} — ECL JE already posted for period {}",
                    holding.getId(), periodId);
                continue;
            }

            BigDecimal targetEcl = scale(input.eclAmount());
            BigDecimal priorEcl = cumulativePriorEcl(holding.getId(), period);
            BigDecimal delta = scale(targetEcl.subtract(priorEcl));

            Integer priorStage = holding.getEclStage();
            Integer newStage = input.eclStage() != null ? input.eclStage() : priorStage;

            UUID jeId = null;
            if (delta.signum() != 0) {
                jeId = postJe(holding, period, delta);
                withJe++;
                if (delta.signum() > 0) {
                    totalIncrease = totalIncrease.add(delta);
                } else {
                    totalReversal = totalReversal.add(delta.abs());
                }
            }

            // Update holding stage if requested change.
            if (newStage != null && !newStage.equals(priorStage)) {
                holding.setEclStage(newStage);
                holdingRepository.save(holding);
            }

            upsertCarryingValue(holding, period, delta, newStage);

            entries.add(new EclRecognitionResult.HoldingEclEntry(
                holding.getId(),
                holding.getSecurityName(),
                holding.getClassification(),
                priorStage,
                newStage,
                priorEcl,
                targetEcl,
                delta,
                jeId));
        }

        BigDecimal totalMovement = totalIncrease.subtract(totalReversal);

        log.info("ECL recognition complete for period {} — {} holdings processed, "
                + "{} JEs posted; ECL Δ +{} / -{} (net {})",
            periodId, entries.size(), withJe,
            scale(totalIncrease), scale(totalReversal), scale(totalMovement));

        return new EclRecognitionResult(period.getId(), entries.size(), withJe,
            scale(totalIncrease), scale(totalReversal), scale(totalMovement), entries);
    }

    /** §5.5.1 + §5.5.2 — ECL applies only to AC and FVOCI_DEBT. */
    static boolean isEclEligible(InvestmentClassification c) {
        return c == InvestmentClassification.AMORTISED_COST
            || c == InvestmentClassification.FVOCI_DEBT;
    }

    /**
     * Pure ECL routing helper.
     *
     * @return (debit, credit) account-code pair
     * @throws IllegalArgumentException for FVPL / FVOCI_EQUITY (not ECL-eligible)
     */
    static AccountRouting routeEclJe(InvestmentHolding holding, BigDecimal delta) {
        boolean increase = delta.signum() > 0;
        return switch (holding.getClassification()) {
            case AMORTISED_COST -> {
                String investmentAccount = holding.getAssetType() == AssetType.MONEY_MARKET
                    ? COA_AC_MONEY_MARKET : COA_AC_DEBT;
                yield increase
                    ? new AccountRouting(COA_ECL_EXPENSE, investmentAccount)
                    : new AccountRouting(investmentAccount, COA_ECL_EXPENSE);
            }
            case FVOCI_DEBT -> increase
                ? new AccountRouting(COA_ECL_EXPENSE, COA_OCI_DEBT_RESERVE)
                : new AccountRouting(COA_OCI_DEBT_RESERVE, COA_ECL_EXPENSE);
            case FVPL, FVOCI_EQUITY -> throw new IllegalArgumentException(
                "ECL is not applicable to " + holding.getClassification());
        };
    }

    record AccountRouting(String debit, String credit) {}

    private boolean jeAlreadyPosted(UUID periodId, UUID holdingId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_entry " +
            "WHERE source_module = ? AND source_event_type = ? AND source_reference = ? " +
            "AND deleted_at IS NULL",
            Long.class,
            MODULE_IFRS9, EVENT_ECL_RECOGNITION, periodId + ":" + holdingId);
        return count != null && count > 0;
    }

    /**
     * Sum {@code investment_carrying_value.ecl_movement} for every prior
     * period — the running ECL allowance accumulated up to (but excluding)
     * this period. JDBC aggregate keeps the engine stateless: the
     * carrying-value rows are the source of truth.
     */
    private BigDecimal cumulativePriorEcl(UUID holdingId, FiscalPeriod period) {
        BigDecimal sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(cv.ecl_movement), 0) " +
            "FROM investment_carrying_value cv " +
            "JOIN fiscal_period fp ON fp.id = cv.period_id " +
            "WHERE cv.holding_id = ? " +
            "  AND cv.deleted_at IS NULL " +
            "  AND fp.end_date < ?",
            BigDecimal.class, holdingId, java.sql.Date.valueOf(period.getStartDate()));
        return scale(sum);
    }

    private UUID postJe(InvestmentHolding holding, FiscalPeriod period, BigDecimal delta) {
        AccountRouting routing = routeEclJe(holding, delta);
        BigDecimal abs = delta.abs();
        String idempotencyRef = period.getId() + ":" + holding.getId();
        String direction = delta.signum() > 0 ? "recognition" : "reversal";

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            routing.debit(), abs, BigDecimal.ZERO, holding.getCurrencyCode(),
            null, null, null,
            holding.getId(),
            null);

        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            routing.credit(), BigDecimal.ZERO, abs, holding.getCurrencyCode(),
            null, null, null,
            holding.getId(),
            null);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            period.getEndDate(),
            MODULE_IFRS9,
            EVENT_ECL_RECOGNITION,
            idempotencyRef,
            "ECL " + direction + " for holding " + holding.getSecurityName()
                + " (" + holding.getClassification() + ", stage " + holding.getEclStage() + ")"
                + " for period " + period.getStartDate() + " to " + period.getEndDate(),
            List.of(debit, credit));

        JournalEntryResponse je = journalEntryService.post(request);
        return je.id();
    }

    private void upsertCarryingValue(InvestmentHolding holding, FiscalPeriod period,
                                      BigDecimal delta, Integer newStage) {
        Optional<InvestmentCarryingValue> existing = carryingValueRepository
            .findByHoldingIdAndPeriodIdAndDeletedAtIsNull(holding.getId(), period.getId());

        InvestmentCarryingValue cv = existing.orElseGet(() -> {
            InvestmentCarryingValue fresh = new InvestmentCarryingValue();
            fresh.setHolding(holding);
            fresh.setPeriod(period);
            fresh.setOpeningBalance(scale(holding.getAcquisitionCost()));
            fresh.setClosingBalance(scale(holding.getAcquisitionCost()));
            fresh.setCurrencyCode(holding.getCurrencyCode());
            return fresh;
        });

        // ecl_movement tracks signed delta for the period.
        cv.setEclMovement(scale(cv.getEclMovement().add(delta)));

        // §5.7.10A: only AC reduces the BS carrying amount via ECL. FVOCI_DEBT
        // leaves closing_balance + closing_fair_value untouched (the offset
        // hit OCI reserve, not the asset).
        if (holding.getClassification() == InvestmentClassification.AMORTISED_COST) {
            cv.setClosingBalance(scale(cv.getClosingBalance().subtract(delta)));
        }

        if (newStage != null) {
            cv.setEclStage(newStage);
        }
        carryingValueRepository.save(cv);
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Input row for {@link #recognise} — flattened version of the REST request entry. */
    public record EclInput(UUID holdingId, BigDecimal eclAmount, Integer eclStage) {}
}
