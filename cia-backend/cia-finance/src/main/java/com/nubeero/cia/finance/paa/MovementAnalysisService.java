package com.nubeero.cia.finance.paa;

import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the IFRS 17 §103 {@link MovementAnalysis} for one fiscal
 * period. Reads exclusively from the {@code paa_movement_analysis} view
 * (V38; V78 appends {@code contract_nature}) — no joins or computation
 * logic lives here; the view is the single source of truth for the
 * {@code paa_lrc}/{@code paa_lic}-backed roll-forward.
 *
 * <p>Module 12 Phase 2 Slice 2.8. Read-only — never writes anything,
 * never posts a JE. Phase 4's NAICOM submission tooling will also consume
 * the view directly without going through this service.
 *
 * <h2>FAC-derecognition composition (FAC / IFRS-17 PAA workstream Task 6b)</h2>
 * <p>{@code FacDerecognitionListener} (Task 5) releases a cancelled FAC
 * group's remaining LRC liability / reinsurance-held asset as a GL journal
 * entry ONLY — it deliberately writes no {@code paa_lrc} row (see that
 * class's javadoc for why: a second writer would collide with {@link
 * LrcEngine}'s idempotency key on the same {@code (group, period)}). Left
 * alone, a group whose only activity in a period is a derecognition would
 * be invisible in this disclosure, because the view's {@code WHERE
 * lrc.id IS NOT NULL OR lic.id IS NOT NULL} filter never sees it.
 * {@link #compute} closes that gap by composing a second aggregate read —
 * {@link #queryDerecognitionReleases} — over {@code journal_entry_line}
 * for {@code FAC_DERECOGNITION} JEs in the period, mirroring the shape
 * {@code Ifrs9MovementAnalysisService.computePremiumReceivableSection}
 * already established for deriving a roll-forward section from a JE
 * aggregate rather than a dedicated table.
 *
 * <p>Accounting treatment: the derecognition JE credits {@code 4330}
 * (inward premium income) / debits {@code 5210} (outward RI expense) —
 * the SAME income/expense accounts periodic LRC recognition uses. A
 * derecognition is therefore modelled as accelerated premium
 * earning/amortisation, not a new movement category. For a
 * derecognition-only group (no view row this period) a synthetic entry is
 * appended with the released amount as {@code lrcOpening == premiumEarned}
 * and {@code lrcClosing} zeroed. A group already present in {@code byGroup}
 * for the period (LRC engine recognised, then a contract in the group was
 * cancelled in the SAME period) has the release folded into its existing
 * {@code premiumEarned} and its {@code lrcClosing} REDUCED by exactly the
 * released amount (final-review per-contract fix — the cancelled contract's
 * portion is removed while any surviving contract's closing is preserved),
 * rather than emitting a duplicate row.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MovementAnalysisService {

    private static final int MONEY_SCALE = 2;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final JdbcTemplate jdbcTemplate;

    public MovementAnalysis compute(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT * FROM paa_movement_analysis WHERE period_id = ? " +
            "ORDER BY portfolio_code, cohort_year, onerousness",
            periodId);

        // LinkedHashMap keyed by groupId (not a List) so the FAC-derecognition
        // composition pass below can look up / replace an existing entry by
        // group id. Insertion order doesn't matter here — the final list is
        // re-sorted by (portfolio_code, cohort_year, onerousness) below (M2)
        // before being returned, so the view's per-group ordering guarantee
        // holds for synthetic derecognition-only entries too.
        Map<UUID, MovementAnalysis.GroupMovementEntry> byGroup = new LinkedHashMap<>(rows.size());

        // LRC totals
        BigDecimal lrcOpening = BigDecimal.ZERO;
        BigDecimal premiumsReceived = BigDecimal.ZERO;
        BigDecimal premiumEarned = BigDecimal.ZERO;
        BigDecimal acqDeferred = BigDecimal.ZERO;
        BigDecimal acqAmortised = BigDecimal.ZERO;
        BigDecimal lossComponent = BigDecimal.ZERO;
        BigDecimal lossComponentChange = BigDecimal.ZERO;
        BigDecimal lrcClosing = BigDecimal.ZERO;

        // LIC totals
        BigDecimal licOpening = BigDecimal.ZERO;
        BigDecimal claimsIncurred = BigDecimal.ZERO;
        BigDecimal claimsPaid = BigDecimal.ZERO;
        BigDecimal caseReserveChange = BigDecimal.ZERO;
        BigDecimal ibnrEstimate = BigDecimal.ZERO;
        BigDecimal ibnrChange = BigDecimal.ZERO;
        BigDecimal riskAdjustment = BigDecimal.ZERO;
        BigDecimal riskAdjustmentChange = BigDecimal.ZERO;
        BigDecimal discountUnwind = BigDecimal.ZERO;
        BigDecimal licClosing = BigDecimal.ZERO;

        BigDecimal totalOpening = BigDecimal.ZERO;
        BigDecimal totalClosing = BigDecimal.ZERO;

        for (Map<String, Object> r : rows) {
            BigDecimal rLrcOpening = bd(r.get("lrc_opening"));
            BigDecimal rPremReceived = bd(r.get("premium_received"));
            BigDecimal rPremEarned = bd(r.get("premium_earned"));
            BigDecimal rAcqDeferred = bd(r.get("acquisition_costs_deferred"));
            BigDecimal rAcqAmortised = bd(r.get("acquisition_costs_amortised"));
            BigDecimal rLossComp = bd(r.get("loss_component"));
            BigDecimal rLossCompChange = bd(r.get("loss_component_change"));
            BigDecimal rLrcClosing = bd(r.get("lrc_closing"));

            BigDecimal rLicOpening = bd(r.get("lic_opening"));
            BigDecimal rClaimsIncurred = bd(r.get("claims_incurred"));
            BigDecimal rClaimsPaid = bd(r.get("claims_paid"));
            BigDecimal rCaseReserveChange = bd(r.get("case_reserve_change"));
            BigDecimal rIbnrEstimate = bd(r.get("ibnr_estimate"));
            BigDecimal rIbnrChange = bd(r.get("ibnr_change"));
            BigDecimal rRiskAdjustment = bd(r.get("risk_adjustment"));
            BigDecimal rRiskAdjustmentChange = bd(r.get("risk_adjustment_change"));
            BigDecimal rDiscountUnwind = bd(r.get("discount_unwind"));
            BigDecimal rLicClosing = bd(r.get("lic_closing"));

            BigDecimal rTotalOpening = bd(r.get("total_opening"));
            BigDecimal rTotalClosing = bd(r.get("total_closing"));

            // Aggregate totals
            lrcOpening = lrcOpening.add(rLrcOpening);
            premiumsReceived = premiumsReceived.add(rPremReceived);
            premiumEarned = premiumEarned.add(rPremEarned);
            acqDeferred = acqDeferred.add(rAcqDeferred);
            acqAmortised = acqAmortised.add(rAcqAmortised);
            lossComponent = lossComponent.add(rLossComp);
            lossComponentChange = lossComponentChange.add(rLossCompChange);
            lrcClosing = lrcClosing.add(rLrcClosing);

            licOpening = licOpening.add(rLicOpening);
            claimsIncurred = claimsIncurred.add(rClaimsIncurred);
            claimsPaid = claimsPaid.add(rClaimsPaid);
            caseReserveChange = caseReserveChange.add(rCaseReserveChange);
            ibnrEstimate = ibnrEstimate.add(rIbnrEstimate);
            ibnrChange = ibnrChange.add(rIbnrChange);
            riskAdjustment = riskAdjustment.add(rRiskAdjustment);
            riskAdjustmentChange = riskAdjustmentChange.add(rRiskAdjustmentChange);
            discountUnwind = discountUnwind.add(rDiscountUnwind);
            licClosing = licClosing.add(rLicClosing);

            totalOpening = totalOpening.add(rTotalOpening);
            totalClosing = totalClosing.add(rTotalClosing);

            UUID groupId = (UUID) r.get("group_id");
            byGroup.put(groupId, new MovementAnalysis.GroupMovementEntry(
                groupId,
                (String) r.get("portfolio_code"),
                (String) r.get("portfolio_name"),
                (Integer) r.get("cohort_year"),
                (String) r.get("onerousness"),
                (String) r.get("group_status"),
                rLrcOpening, rPremReceived, rPremEarned,
                rAcqDeferred, rAcqAmortised,
                rLossComp, rLossCompChange, rLrcClosing,
                rLicOpening, rClaimsIncurred, rClaimsPaid,
                rCaseReserveChange, rIbnrEstimate, rIbnrChange,
                rRiskAdjustment, rRiskAdjustmentChange,
                rDiscountUnwind, rLicClosing,
                rTotalOpening, rTotalClosing,
                (String) r.get("currency_code"),
                (String) r.get("contract_nature")));
        }

        // ── FAC-derecognition composition (Task 6b) ─────────────────────────
        // See class javadoc. For each group with FAC_DERECOGNITION release
        // activity in this period: fold it into an existing view-sourced
        // entry (recognise-then-cancel in the same period), or append a
        // synthetic derecognition-only entry (the group had no other LRC/LIC
        // activity this period, so the view never surfaced it).
        for (Map<String, Object> d : queryDerecognitionReleases(period.getStartDate(), period.getEndDate())) {
            BigDecimal released = bd(d.get("released"));
            if (released.signum() <= 0) {
                // Defensive: FacDerecognitionListener never posts a
                // non-positive release, so this branch is not expected to be
                // reachable — skip rather than emit a no-op synthetic row.
                continue;
            }

            UUID groupId = (UUID) d.get("group_id");
            MovementAnalysis.GroupMovementEntry existing = byGroup.get(groupId);

            if (existing != null) {
                BigDecimal groupLrcClosing = existing.lrcClosing();

                // M1 (final-review): the derecognition released ONE contract's
                // remaining carrying (per-contract release — FacDerecognition
                // listener no longer releases the group aggregate), so for a
                // multi-contract group with a survivor, released is legitimately
                // LESS than the group's LRC closing — the surviving contract's
                // portion must remain. The merge therefore REDUCES lrcClosing by
                // exactly `released` (clamped >= 0) rather than zeroing the whole
                // group. released > groupLrcClosing is the genuine inconsistency
                // to flag: you cannot release more than the group holds
                // (over-release would drive the disclosed closing negative).
                BigDecimal closingReduction = released.min(groupLrcClosing).max(BigDecimal.ZERO);
                if (released.compareTo(groupLrcClosing) > 0) {
                    log.warn("FAC derecognition release {} exceeds group LRC closing {} for group {} — "
                            + "§103 merge would drive the disclosed closing negative; clamping to the "
                            + "group closing", released, groupLrcClosing, groupId);
                }
                BigDecimal newLrcClosing = groupLrcClosing.subtract(closingReduction);

                byGroup.put(groupId, new MovementAnalysis.GroupMovementEntry(
                    existing.groupId(), existing.portfolioCode(), existing.portfolioName(),
                    existing.cohortYear(), existing.onerousness(), existing.groupStatus(),
                    existing.lrcOpening(), existing.premiumReceived(), existing.premiumEarned().add(released),
                    existing.acquisitionCostsDeferred(), existing.acquisitionCostsAmortised(),
                    existing.lossComponent(), existing.lossComponentChange(), newLrcClosing,
                    existing.licOpening(), existing.claimsIncurred(), existing.claimsPaid(),
                    existing.caseReserveChange(), existing.ibnrEstimate(), existing.ibnrChange(),
                    existing.riskAdjustment(), existing.riskAdjustmentChange(),
                    existing.discountUnwind(), existing.licClosing(),
                    existing.totalOpening(), existing.totalClosing().subtract(closingReduction),
                    existing.currencyCode(), existing.contractNature()));

                premiumEarned = premiumEarned.add(released);
                lrcClosing = lrcClosing.subtract(closingReduction);
                totalClosing = totalClosing.subtract(closingReduction);
            } else {
                byGroup.put(groupId, new MovementAnalysis.GroupMovementEntry(
                    groupId,
                    (String) d.get("portfolio_code"),
                    (String) d.get("portfolio_name"),
                    (Integer) d.get("cohort_year"),
                    (String) d.get("onerousness"),
                    (String) d.get("group_status"),
                    released, BigDecimal.ZERO, released,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO,
                    released, BigDecimal.ZERO,
                    (String) d.get("currency_code"),
                    (String) d.get("contract_nature")));

                lrcOpening = lrcOpening.add(released);
                premiumEarned = premiumEarned.add(released);
                totalOpening = totalOpening.add(released);
            }
        }

        log.info("Movement analysis computed for period {} — {} groups; "
                + "LRC opening {} → closing {}; LIC opening {} → closing {}",
            periodId, byGroup.size(),
            scale(lrcOpening), scale(lrcClosing),
            scale(licOpening), scale(licClosing));

        // M2 (Task 6b review): the view already returns its rows ordered by
        // (portfolio_code, cohort_year, onerousness), but synthetic
        // derecognition-only entries are appended after the loop above in
        // their own (also portfolio_code-ordered) aggregate-query order — so
        // the raw byGroup.values() sequence is two independently-sorted
        // blocks, not one. Re-sort the WHOLE list by the same key the view
        // uses so the §103 per-group ordering guarantee holds regardless of
        // whether a group's only activity this period was a derecognition.
        List<MovementAnalysis.GroupMovementEntry> sortedByGroup = new ArrayList<>(byGroup.values());
        sortedByGroup.sort(Comparator
            .comparing(MovementAnalysis.GroupMovementEntry::portfolioCode)
            .thenComparing(MovementAnalysis.GroupMovementEntry::cohortYear)
            .thenComparing(MovementAnalysis.GroupMovementEntry::onerousness));

        return new MovementAnalysis(
            period.getId(),
            period.getStartDate(),
            period.getEndDate(),
            new MovementAnalysis.LrcMovementTotals(
                scale(lrcOpening), scale(premiumsReceived), scale(premiumEarned),
                scale(acqDeferred), scale(acqAmortised),
                scale(lossComponent), scale(lossComponentChange),
                scale(lrcClosing)),
            new MovementAnalysis.LicMovementTotals(
                scale(licOpening), scale(claimsIncurred), scale(claimsPaid),
                scale(caseReserveChange), scale(ibnrEstimate), scale(ibnrChange),
                scale(riskAdjustment), scale(riskAdjustmentChange),
                scale(discountUnwind), scale(licClosing)),
            scale(totalOpening),
            scale(totalClosing),
            sortedByGroup);
    }

    /**
     * Aggregates {@code FAC_DERECOGNITION} JE releases per group for the
     * period — mirrors {@code Ifrs9MovementAnalysisService
     * .sumPremiumReceivableAllowance}'s "derive a roll-forward figure from a
     * journal_entry_line aggregate" shape.
     *
     * <p>{@code FacDerecognitionListener} posts exactly two lines per JE: a
     * pure-debit leg on {@code 2210} (inward) or a pure-credit leg on
     * {@code 1410} (outward) carries the released amount — the OTHER leg
     * (revenue/expense side, {@code 4330}/{@code 5210}) is excluded by the
     * {@code coa.code IN (...)} filter. Since each matched line is
     * pure-debit XOR pure-credit, {@code SUM(debit_amount + credit_amount)}
     * over the matched lines equals the released amount per group.
     *
     * <p>{@code business_date} is filtered inclusively within the period's
     * {@code [start, end]} — matching {@code JournalEntryRepository}'s
     * {@code businessDate >= / <=} range convention.
     *
     * <h2>Reversal exclusion (M3, Task 6b review)</h2>
     * <p>This aggregate is a <strong>magnitude</strong> sum ({@code
     * SUM(debit + credit)}), unlike {@code Ifrs9MovementAnalysisService}'s
     * netting {@code SUM(credit − debit)} — a magnitude sum does not
     * self-cancel a reversal. {@code JournalEntryService.reverse} (D2=A)
     * flips the original JE to {@code REVERSED} (both rows remain in the
     * GL) and posts a NEW JE with {@code reversalOf} set and {@code
     * sourceEventType = REVERSAL} — never {@code FAC_DERECOGNITION} — so the
     * reversal row itself is already excluded by the {@code
     * source_event_type} filter above. The REVERSED original, however, IS
     * still {@code FAC_DERECOGNITION} and would otherwise keep contributing
     * its released amount forever. {@code AND je.status = 'POSTED'} excludes
     * it; {@code AND je.reversal_of IS NULL} is a defensive second guard in
     * case a future write path ever posts a reversal under the same event
     * type. Net effect: a reversed derecognition contributes zero.
     */
    private List<Map<String, Object>> queryDerecognitionReleases(LocalDate periodStart, LocalDate periodEnd) {
        return jdbcTemplate.queryForList(
            "SELECT g.id AS group_id, " +
            "       p.code AS portfolio_code, " +
            "       p.name AS portfolio_name, " +
            "       g.cohort_year AS cohort_year, " +
            "       g.onerousness AS onerousness, " +
            "       g.status AS group_status, " +
            "       l.currency_code AS currency_code, " +
            "       p.contract_nature AS contract_nature, " +
            "       SUM(l.debit_amount + l.credit_amount) AS released " +
            "FROM journal_entry_line l " +
            "JOIN journal_entry je ON je.id = l.journal_entry_id " +
            "JOIN chart_of_account coa ON coa.id = l.account_id " +
            "JOIN group_of_contracts g ON g.id = l.contract_group_id " +
            "JOIN portfolio p ON p.id = g.portfolio_id " +
            "WHERE je.source_module = ? " +
            "  AND je.source_event_type = ? " +
            "  AND je.status = 'POSTED' " +
            "  AND je.reversal_of IS NULL " +
            "  AND coa.code IN (?, ?) " +
            "  AND je.business_date >= ? AND je.business_date <= ? " +
            "  AND l.deleted_at IS NULL " +
            "  AND je.deleted_at IS NULL " +
            "  AND g.deleted_at IS NULL " +
            "  AND p.deleted_at IS NULL " +
            "GROUP BY g.id, p.code, p.name, g.cohort_year, g.onerousness, g.status, " +
            "         l.currency_code, p.contract_nature " +
            "ORDER BY p.code, g.cohort_year, g.onerousness",
            LrcEngine.MODULE_PAA, FacDerecognitionListener.EVENT_FAC_DERECOGNITION,
            LrcEngine.COA_INWARD_LRC, LrcEngine.COA_REINSURANCE_HELD_LRC_ASSET,
            java.sql.Date.valueOf(periodStart), java.sql.Date.valueOf(periodEnd));
    }

    private static BigDecimal bd(Object o) {
        return o == null ? BigDecimal.ZERO : (BigDecimal) o;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
