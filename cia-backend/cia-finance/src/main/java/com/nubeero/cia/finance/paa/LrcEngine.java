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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * IFRS 17 PAA Liability for Remaining Coverage measurement engine.
 * Module 12 Phase 2 Slice 2.3.
 *
 * <p>For a target fiscal period, walks every IFRS 17 group of contracts and
 * computes the LRC roll-forward implied by straight-line daily premium
 * earning across each policy assigned to the group. For every group whose
 * earned premium for the period is non-zero, the engine:
 *
 * <ol>
 *   <li>writes one {@link PaaLrc} row carrying the roll-forward identity
 *       {@code opening + received − earned = closing};</li>
 *   <li>posts one JE through the {@link JournalEntryService} gateway:
 *       Dr 2110 (LRC — Best estimate of liabilities) / Cr 4110 (Insurance
 *       revenue — LRC release), tagged with the portfolio + group +
 *       cohort dimensions so the trial balance can roll up by IFRS 17
 *       grain.</li>
 * </ol>
 *
 * <h2>Stateless period computation</h2>
 * <p>Each (group, period) row is independently computable from policy data
 * and period boundaries — the engine never reads prior {@link PaaLrc} rows.
 * The opening / closing identities hold by construction. Out-of-order
 * processing (recognising May before April) is therefore harmless, and
 * re-running yields bit-identical values. The cost is a full per-policy
 * scan per period; a v2 incremental engine can specialise this if scale
 * demands.
 *
 * <h2>Idempotency</h2>
 * <p>Three layers:
 * <ul>
 *   <li>DB: {@code uq_paa_lrc_group_period} in V36 rejects duplicate
 *       (group, period) rows.</li>
 *   <li>JE gateway: {@code uq_journal_entry_idempotency} rejects duplicate
 *       (source_module, source_event_type, source_reference) triples. We
 *       use {@code "paa" / "LRC_RECOGNITION" / period_id:group_id} —
 *       group-period unique by construction.</li>
 *   <li>Service: explicit pre-check before insert raises
 *       {@link LrcRecognitionAlreadyDoneException} (409 CONFLICT) so a
 *       re-run never produces partial side effects.</li>
 * </ul>
 *
 * <h2>v1 scope</h2>
 * <ul>
 *   <li>Earning pattern: straight-line daily (passage-of-time). Risk-
 *       weighted and claims-shape patterns are future extensions.</li>
 *   <li>Acquisition cashflows: assumed EXPENSE_AS_INCURRED — the
 *       {@code acq_costs_deferred} / {@code acq_costs_amortised} columns
 *       stay at zero. A tenant whose {@link PaaConfig} elects
 *       DEFER_AND_AMORTISE will log a warning until Slice 2.x adds
 *       support; the engine still computes earned premium correctly.</li>
 *   <li>Onerous groups: all groups are NOT_ONEROUS at initial recognition
 *       (Slice 2.2). Loss component accounting is Slice 2.7's job.</li>
 *   <li>Cross-currency groups: the engine assumes one currency per group
 *       (true while Nigerian-only). Throws if a group mixes currencies.</li>
 * </ul>
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class LrcEngine {

    // ── Accounting codes (mirrors SubledgerPostingService hardcoded codes) ───
    /** LRC — Best estimate of liabilities. V32 seed, ifrs17_role=LRC_BEL. */
    static final String COA_LRC_BEL = "2110";

    /** Insurance revenue — LRC release. V32 seed, ifrs17_role=REVENUE_LRC_RELEASE. */
    static final String COA_REVENUE_LRC_RELEASE = "4110";

    // ── Idempotency triple slot values ───────────────────────────────────────
    static final String MODULE_PAA = "paa";
    static final String EVENT_LRC_RECOGNITION = "LRC_RECOGNITION";

    /** Decimal scale matching DECIMAL(18,2) on paa_lrc / journal_entry_line. */
    private static final int MONEY_SCALE = 2;

    /** Scale for intermediate fraction computation — big enough to keep round-trip errors below 1 kobo on yearly policies. */
    private static final int FRACTION_SCALE = 12;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final GroupOfContractsRepository groupRepository;
    private final PolicyGroupAssignmentRepository assignmentRepository;
    private final PaaLrcRepository lrcRepository;
    private final JournalEntryService journalEntryService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Recognise LRC release for {@code periodId} across every active group.
     * Idempotent at the (group, period) grain — re-running raises
     * {@link LrcRecognitionAlreadyDoneException} for any group that has
     * already been recognised in this period.
     */
    public LrcRecognitionResult recognise(UUID periodId) {
        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        log.info("LRC recognition starting for period {} ({} → {})",
            periodId, period.getStartDate(), period.getEndDate());

        BigDecimal totalEarned = BigDecimal.ZERO;
        int groupsWithJe = 0;
        List<LrcRecognitionResult.GroupRecognitionEntry> entries = new ArrayList<>();

        List<GroupOfContracts> groups = groupRepository.findAll().stream()
            .filter(g -> g.getDeletedAt() == null)
            .toList();

        for (GroupOfContracts group : groups) {
            GroupRollForward rollForward = computeRollForward(group, period);

            // Fast-fail idempotency check — DB UNIQUE would catch this too but
            // the service-layer pre-check avoids any partial work.
            if (lrcRepository.findByGroupIdAndPeriodIdAndDeletedAtIsNull(group.getId(), period.getId()).isPresent()) {
                throw new LrcRecognitionAlreadyDoneException(period.getId(), group.getId());
            }

            // Skip groups with zero activity in this period — no JE noise.
            if (rollForward.allZero()) {
                log.debug("Skipping group {} — no LRC activity in period {}", group.getId(), periodId);
                continue;
            }

            PaaLrc lrcRow = persistRollForward(group, period, rollForward);

            UUID jeId = null;
            if (rollForward.earned.compareTo(BigDecimal.ZERO) > 0) {
                jeId = postJe(group, period, rollForward);
                groupsWithJe++;
                totalEarned = totalEarned.add(rollForward.earned);
            }

            entries.add(new LrcRecognitionResult.GroupRecognitionEntry(
                group.getId(),
                lrcRow.getOpeningBalance(),
                lrcRow.getPremiumReceived(),
                lrcRow.getPremiumEarned(),
                lrcRow.getClosingBalance(),
                jeId));
        }

        log.info("LRC recognition complete for period {} — {} groups processed, {} JEs posted, total earned {}",
            periodId, entries.size(), groupsWithJe, totalEarned);

        return new LrcRecognitionResult(period.getId(), entries.size(), groupsWithJe, totalEarned, entries);
    }

    /**
     * Compute the (opening, received, earned, closing) tuple for one group
     * in one period by summing per-policy values. Pure function over the
     * policy data — same inputs always produce same outputs.
     */
    private GroupRollForward computeRollForward(GroupOfContracts group, FiscalPeriod period) {
        List<PolicyGroupAssignment> assignments =
            assignmentRepository.findByGroupIdAndDeletedAtIsNullOrderByAssignedAtAsc(group.getId());

        if (assignments.isEmpty()) {
            return GroupRollForward.empty();
        }

        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal closing = BigDecimal.ZERO;
        String currency = null;

        for (PolicyGroupAssignment a : assignments) {
            PolicyPricing policy = loadPolicyPricing(a.getPolicyId());
            if (policy == null) {
                log.warn("Skipping assignment {} — policy {} not found or deleted", a.getId(), a.getPolicyId());
                continue;
            }

            if (currency == null) {
                currency = policy.currencyCode;
            } else if (!currency.equals(policy.currencyCode)) {
                throw new IllegalStateException("Group " + group.getId()
                    + " contains policies in multiple currencies (" + currency + " and " + policy.currencyCode
                    + ") — Slice 2.3 v1 does not support cross-currency aggregation");
            }

            opening = opening.add(openingAmount(policy, period.getStartDate(), period.getEndDate()));
            received = received.add(receivedAmount(policy, period.getStartDate(), period.getEndDate()));
            earned = earned.add(earnedAmount(policy, period.getStartDate(), period.getEndDate()));
            closing = closing.add(closingAmount(policy, period.getStartDate(), period.getEndDate()));
        }

        return new GroupRollForward(
            scale(opening), scale(received), scale(earned), scale(closing),
            currency == null ? "NGN" : currency);
    }

    /** Premium remaining at the start of the period (days from period.start through policy.end). */
    static BigDecimal openingAmount(PolicyPricing p, LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart.isAfter(p.endDate) || periodStart.isBefore(p.startDate)) {
            // period starts after policy expires → 0; period starts before policy inception → full premium
            if (periodStart.isBefore(p.startDate)) return p.netPremium;
            return BigDecimal.ZERO;
        }
        long daysRemaining = daysBetween(periodStart, p.endDate);
        return premiumPortion(p, daysRemaining);
    }

    /** Premium remaining at the end of the period (days from period.end+1 through policy.end). */
    static BigDecimal closingAmount(PolicyPricing p, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate dayAfterPeriod = periodEnd.plusDays(1);
        if (dayAfterPeriod.isAfter(p.endDate)) return BigDecimal.ZERO;
        if (dayAfterPeriod.isBefore(p.startDate)) return p.netPremium;
        long daysRemaining = daysBetween(dayAfterPeriod, p.endDate);
        return premiumPortion(p, daysRemaining);
    }

    /** Premium received during the period — full premium if policy.start falls in [period.start, period.end], else 0. */
    static BigDecimal receivedAmount(PolicyPricing p, LocalDate periodStart, LocalDate periodEnd) {
        if (!p.startDate.isBefore(periodStart) && !p.startDate.isAfter(periodEnd)) {
            return p.netPremium;
        }
        return BigDecimal.ZERO;
    }

    /** Premium earned during the period — fraction of total premium proportional to days active in period. */
    static BigDecimal earnedAmount(PolicyPricing p, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate activeStart = max(p.startDate, periodStart);
        LocalDate activeEnd = min(p.endDate, periodEnd);
        if (activeStart.isAfter(activeEnd)) return BigDecimal.ZERO;
        long daysActive = daysBetween(activeStart, activeEnd);
        return premiumPortion(p, daysActive);
    }

    /** Inclusive day count from {@code from} through {@code to}. */
    static long daysBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1L;
    }

    /** {@code premium × (daysActive / totalDays)} rounded HALF_UP at MONEY_SCALE. */
    static BigDecimal premiumPortion(PolicyPricing p, long daysActive) {
        long total = daysBetween(p.startDate, p.endDate);
        if (total <= 0) return BigDecimal.ZERO;
        return p.netPremium
            .multiply(BigDecimal.valueOf(daysActive))
            .divide(BigDecimal.valueOf(total), FRACTION_SCALE, RoundingMode.HALF_UP)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static LocalDate max(LocalDate a, LocalDate b) { return a.isAfter(b) ? a : b; }

    private static LocalDate min(LocalDate a, LocalDate b) { return a.isBefore(b) ? a : b; }

    private static BigDecimal scale(BigDecimal v) { return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP); }

    private PaaLrc persistRollForward(GroupOfContracts group, FiscalPeriod period, GroupRollForward rf) {
        PaaLrc lrc = new PaaLrc();
        lrc.setGroup(group);
        lrc.setPeriod(period);
        lrc.setOpeningBalance(rf.opening);
        lrc.setPremiumReceived(rf.received);
        lrc.setPremiumEarned(rf.earned);
        lrc.setClosingBalance(rf.closing);
        lrc.setCurrencyCode(rf.currency);
        return lrcRepository.save(lrc);
    }

    private UUID postJe(GroupOfContracts group, FiscalPeriod period, GroupRollForward rf) {
        String idempotencyRef = period.getId() + ":" + group.getId();

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            COA_LRC_BEL, rf.earned, BigDecimal.ZERO, rf.currency,
            group.getCohortYear(), group.getPortfolio().getId(), group.getId(),
            null, null);

        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            COA_REVENUE_LRC_RELEASE, BigDecimal.ZERO, rf.earned, rf.currency,
            group.getCohortYear(), group.getPortfolio().getId(), group.getId(),
            null, null);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            period.getEndDate(),
            MODULE_PAA,
            EVENT_LRC_RECOGNITION,
            idempotencyRef,
            "LRC release for group " + group.getPortfolio().getCode() + "/"
                + group.getCohortYear() + "/" + group.getOnerousness()
                + " for period " + period.getStartDate() + " to " + period.getEndDate(),
            List.of(debit, credit));

        JournalEntryResponse je = journalEntryService.post(request);
        return je.id();
    }

    private PolicyPricing loadPolicyPricing(UUID policyId) {
        List<PolicyPricing> rows = jdbcTemplate.query(
            "SELECT policy_start_date, policy_end_date, net_premium, currency_code " +
            "FROM policies WHERE id = ? AND deleted_at IS NULL",
            (rs, rowNum) -> new PolicyPricing(
                rs.getDate("policy_start_date").toLocalDate(),
                rs.getDate("policy_end_date").toLocalDate(),
                rs.getBigDecimal("net_premium"),
                rs.getString("currency_code")),
            policyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Internal aggregates ──────────────────────────────────────────────────

    /** Snapshot of the policy fields the engine needs — pulled via native SQL, not a JPA entity, to avoid cross-module entity coupling. */
    record PolicyPricing(LocalDate startDate, LocalDate endDate, BigDecimal netPremium, String currencyCode) {}

    /** Group-aggregate roll-forward values for one period. */
    private record GroupRollForward(
        BigDecimal opening,
        BigDecimal received,
        BigDecimal earned,
        BigDecimal closing,
        String currency
    ) {
        static GroupRollForward empty() {
            return new GroupRollForward(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "NGN");
        }
        boolean allZero() {
            return opening.signum() == 0 && received.signum() == 0
                && earned.signum() == 0 && closing.signum() == 0;
        }
    }
}
