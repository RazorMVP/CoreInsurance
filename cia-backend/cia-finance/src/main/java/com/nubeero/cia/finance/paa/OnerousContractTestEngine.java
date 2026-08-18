package com.nubeero.cia.finance.paa;

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
 * IFRS 17 §47-49 onerous contract test + loss component recognition.
 * Module 12 Phase 2 Slice 2.7.
 *
 * <p>Runs each period after LRC + LIC + DiscountUnwind. For every group
 * with a paa_lrc row in the period <strong>whose portfolio is not
 * {@code FAC_OUTWARD}</strong> (FAC / IFRS-17 PAA workstream Task 4 —
 * reinsurance held has no onerous test; §66A loss-recovery is out of scope
 * for this plan), the engine:
 * <ol>
 *   <li>aggregates cumulative earned premium (paa_lrc.premium_earned) and
 *       cumulative incurred claims (paa_lic.claims_incurred) across every
 *       fiscal period up to and including the test period;</li>
 *   <li>computes the target loss component
 *       {@code new_lc = max(0, cumulative_incurred − cumulative_earned)};</li>
 *   <li>compares against the existing {@code paa_lrc.loss_component} for
 *       this period (carried forward from prior periods by reading the
 *       paa_lrc row written by LrcEngine — v1 defaults to zero);</li>
 *   <li>if {@code delta != 0}, posts the corresponding JE through the
 *       gateway and updates the paa_lrc row.</li>
 * </ol>
 *
 * <h2>JE shapes</h2>
 * <ul>
 *   <li>Increase ({@code delta > 0}):
 *       {@code Dr 5150 (Loss component change) / Cr 2130 (LRC Loss component)}</li>
 *   <li>Reversal ({@code delta < 0}):
 *       {@code Dr 2130 / Cr 5150} — IFRS-17 §50 allows reversal when
 *       conditions improve.</li>
 * </ul>
 *
 * <h2>Permanence vs measurement (§22 vs §47-49)</h2>
 * <p>§22 fixes a group's onerousness at initial recognition — the
 * {@code group_of_contracts.onerousness} column never changes. §47-49
 * tracks deteriorating measurement *within* the assigned group as a
 * loss component on the LRC. This engine implements the §47-49 measurement;
 * the §22 assignment is the responsibility of Slice 2.2's
 * {@code ContractGroupingService}.
 *
 * <h2>Idempotency</h2>
 * <p>The engine is naturally idempotent: it computes the target loss
 * component state and reconciles. A re-run with no underlying movement
 * produces {@code delta == 0} and posts no JE. The
 * {@code uq_journal_entry_idempotency} on
 * {@code (source_module, source_event_type, source_reference)} catches the
 * race window. Reference is {@code period_id + ":" + group_id}.
 *
 * <h2>v1 simplifications</h2>
 * <ul>
 *   <li>Loss-component formula uses the simple cumulative-incurred-minus-
 *       earned proxy. IFRS 17 §49 prefers full fulfilment-cashflow
 *       projection (including RA + future-incurred + acquisition costs).
 *       The engine signature accommodates v2 swapping in a richer formula
 *       without changing the orchestration or JE shape.</li>
 *   <li>No Risk Adjustment in the formula (Slice 2.7b will add this).</li>
 *   <li>No IBNR projection in the formula (Slice 2.7b will add this).</li>
 *   <li>Cross-currency: not supported — all currency comes from paa_lrc.</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class OnerousContractTestEngine {

    /** Loss component change expense. V32 seed, ifrs17_role=LC_CHANGE. */
    static final String COA_LC_CHANGE = "5150";

    /** LRC - Loss component (onerous). V32 seed, ifrs17_role=LRC_LC. */
    static final String COA_LRC_LC = "2130";

    static final String MODULE_PAA = "paa";
    static final String EVENT_PAA_ONEROUS_TEST = "PAA_ONEROUS_TEST";

    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final PaaLrcRepository lrcRepository;
    private final JournalEntryService journalEntryService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Run the onerous test for every paa_lrc row in {@code periodId} and
     * reconcile each row's loss component to the target value.
     */
    public OnerousTestResult test(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        log.info("Onerous test starting for period {} ({} → {})",
            periodId, period.getStartDate(), period.getEndDate());

        List<PaaLrc> lrcRows = lrcRepository.findByPeriodIdAndDeletedAtIsNullOrderByGroupIdAsc(periodId);

        BigDecimal totalIncrease = BigDecimal.ZERO;
        BigDecimal totalReversal = BigDecimal.ZERO;
        int groupsWithChange = 0;
        List<OnerousTestResult.GroupOnerousEntry> entries = new ArrayList<>();

        for (PaaLrc lrc : lrcRows) {
            UUID groupId = lrc.getGroup().getId();

            // FAC / IFRS-17 PAA workstream Task 4 — reinsurance held (outward
            // FAC) has no onerous test under IFRS 17: §66A loss-recovery on
            // ceded contracts is out of scope for this plan. Skip these
            // groups entirely so no loss-component JE is ever posted for a
            // reinsurance-held asset.
            if (lrc.getGroup().getPortfolio().getContractNature() == ContractNature.FAC_OUTWARD) {
                log.debug("Skipping onerous test for group {} — FAC_OUTWARD (reinsurance held) is exempt", groupId);
                continue;
            }

            Cumulative cum = aggregateCumulative(groupId, period.getEndDate());
            BigDecimal newLc = scale(targetLossComponent(cum.earned, cum.incurred));
            BigDecimal priorLc = lrc.getLossComponent();
            BigDecimal delta = newLc.subtract(priorLc);

            UUID jeId = null;
            if (delta.signum() != 0) {
                jeId = postJe(lrc, period, delta);
                lrc.setLossComponent(newLc);
                lrc.setLossComponentChange(delta);
                lrc.setClosingBalance(lrc.getClosingBalance().add(delta));
                lrcRepository.save(lrc);

                if (delta.signum() > 0) {
                    totalIncrease = totalIncrease.add(delta);
                } else {
                    totalReversal = totalReversal.add(delta.abs());
                }
                groupsWithChange++;
            }

            entries.add(new OnerousTestResult.GroupOnerousEntry(
                groupId,
                cum.earned,
                cum.incurred,
                priorLc,
                newLc,
                delta,
                jeId));
        }

        log.info("Onerous test complete for period {} — {} groups tested, {} LC changes ({} increases, {} reversals)",
            periodId, entries.size(), groupsWithChange, totalIncrease, totalReversal);

        return new OnerousTestResult(
            period.getId(),
            entries.size(),
            groupsWithChange,
            scale(totalIncrease),
            scale(totalReversal),
            entries);
    }

    /**
     * v1 simplified target: {@code max(0, cumulativeIncurred − cumulativeEarned)}.
     * v2 will plug in full §49 fulfilment-cashflow projection (incurred + RA +
     * future-expected + acquisition costs vs earned + premium-yet-to-earn).
     */
    static BigDecimal targetLossComponent(BigDecimal earned, BigDecimal incurred) {
        BigDecimal excess = incurred.subtract(earned);
        return excess.signum() <= 0 ? BigDecimal.ZERO : excess;
    }

    /**
     * Sum paa_lrc.premium_earned + paa_lic.claims_incurred for the group
     * across every fiscal period whose end_date is &le; the test period's
     * end. One query each so we don't pull rows we don't need; both joins
     * fiscal_period purely for the end-date filter.
     */
    private Cumulative aggregateCumulative(UUID groupId, java.time.LocalDate periodEnd) {
        BigDecimal cumEarned = Optional.ofNullable(jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(lrc.premium_earned), 0) " +
            "FROM paa_lrc lrc " +
            "JOIN fiscal_period fp ON fp.id = lrc.period_id " +
            "WHERE lrc.group_id = ? " +
            "  AND lrc.deleted_at IS NULL " +
            "  AND fp.end_date <= ?",
            BigDecimal.class, groupId, java.sql.Date.valueOf(periodEnd))).orElse(BigDecimal.ZERO);

        BigDecimal cumIncurred = Optional.ofNullable(jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(lic.claims_incurred), 0) " +
            "FROM paa_lic lic " +
            "JOIN fiscal_period fp ON fp.id = lic.period_id " +
            "WHERE lic.group_id = ? " +
            "  AND lic.deleted_at IS NULL " +
            "  AND fp.end_date <= ?",
            BigDecimal.class, groupId, java.sql.Date.valueOf(periodEnd))).orElse(BigDecimal.ZERO);

        return new Cumulative(scale(cumEarned), scale(cumIncurred));
    }

    private UUID postJe(PaaLrc lrc, FiscalPeriod period, BigDecimal delta) {
        String idempotencyRef = period.getId() + ":" + lrc.getGroup().getId();
        BigDecimal absAmount = delta.abs();

        // Increase (delta > 0): Dr 5150 (expense) / Cr 2130 (LRC LC, liability up).
        // Reversal (delta < 0): Dr 2130 (LRC LC, liability down) / Cr 5150 (expense reversed).
        String debitAccount = delta.signum() > 0 ? COA_LC_CHANGE : COA_LRC_LC;
        String creditAccount = delta.signum() > 0 ? COA_LRC_LC : COA_LC_CHANGE;
        String direction = delta.signum() > 0 ? "increase" : "reversal";

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            debitAccount, absAmount, BigDecimal.ZERO, lrc.getCurrencyCode(),
            lrc.getGroup().getCohortYear(),
            lrc.getGroup().getPortfolio().getId(),
            lrc.getGroup().getId(),
            null, null);

        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            creditAccount, BigDecimal.ZERO, absAmount, lrc.getCurrencyCode(),
            lrc.getGroup().getCohortYear(),
            lrc.getGroup().getPortfolio().getId(),
            lrc.getGroup().getId(),
            null, null);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            period.getEndDate(),
            MODULE_PAA,
            EVENT_PAA_ONEROUS_TEST,
            idempotencyRef,
            "Onerous-test loss component " + direction + " for group "
                + lrc.getGroup().getPortfolio().getCode() + "/"
                + lrc.getGroup().getCohortYear() + "/" + lrc.getGroup().getOnerousness()
                + " for period " + period.getStartDate() + " to " + period.getEndDate(),
            List.of(debit, credit));

        JournalEntryResponse je = journalEntryService.post(request);
        return je.id();
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private record Cumulative(BigDecimal earned, BigDecimal incurred) {}
}
