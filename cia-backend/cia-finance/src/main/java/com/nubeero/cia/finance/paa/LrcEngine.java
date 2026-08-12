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
 *
 * <h2>Contract-type dispatch (FAC / IFRS-17 PAA workstream Task 3 + 4)</h2>
 * <p>Pricing is loaded per {@link ContractGroupAssignment#getContractType()}
 * via {@link #loadPricing}: {@code POLICY} reads {@code policies}
 * (net premium is the LRC basis); {@code FAC_INWARD} reads
 * {@code ri_fac_inwards} (gross premium is the LRC basis — the accepted
 * inward cover's whole gross premium sets up the liability, mirroring the
 * accept-time posting); {@code FAC_OUTWARD} reads {@code ri_fac_covers}
 * (NET premium is the LRC basis — §65 commission-netting, see Task 4). The
 * posting accounts are resolved once per group from
 * {@link Portfolio#getContractNature()} via {@link #accountsFor} — DIRECT
 * posts Dr 2110 / Cr 4110 (unchanged); FAC_INWARD posts Dr 2210 / Cr 4330
 * (a liability release — {@code Dr LRC / Cr revenue}). {@code FAC_OUTWARD}
 * is the mirror-image <em>asset</em> shape: it posts
 * {@code Dr 5210 (RI premium expense) / Cr 1410 (reinsurance-held LRC
 * asset)} — the asset is credited (run down) as it amortises to expense,
 * the inverse of the liability-release sign used by DIRECT/FAC_INWARD.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class LrcEngine {

    // ── Accounting codes (mirrors SubledgerPostingService hardcoded codes) ───
    /** DIRECT — LRC — Best estimate of liabilities. V32 seed, ifrs17_role=LRC_BEL. */
    static final String COA_LRC_BEL = "2110";

    /** DIRECT — Insurance revenue — LRC release. V32 seed, ifrs17_role=REVENUE_LRC_RELEASE. */
    static final String COA_REVENUE_LRC_RELEASE = "4110";

    /**
     * FAC_INWARD — Inward reinsurance LRC. V32 seed, ifrs17_role=LRC_BEL.
     * Mirrors {@code SubledgerPostingService.COA_INWARD_LRC} — set up at
     * accept time ({@code replayFacPremiumAccepted}) at the full gross
     * premium, then released to income over the cover period by this engine.
     */
    static final String COA_INWARD_LRC = "2210";

    /**
     * FAC_INWARD — Inward reinsurance premium income. V75 seed. Mirrors
     * {@code SubledgerPostingService.COA_INWARD_PREMIUM_INCOME} — no longer
     * credited at accept time; only this engine's periodic LRC release
     * credits it now.
     */
    static final String COA_INWARD_PREMIUM_INCOME = "4330";

    /**
     * FAC_OUTWARD — Outward reinsurance premium expense. V32 seed. Mirrors
     * {@code SubledgerPostingService.COA_RI_PREMIUM_EXPENSE} — no longer
     * receives the full ceded premium at confirm time (Task 4 nets that
     * into {@link #COA_REINSURANCE_HELD_LRC_ASSET}); only this engine's
     * periodic straight-line amortisation debits it now.
     */
    static final String COA_RI_PREMIUM_EXPENSE = "5210";

    /**
     * FAC_OUTWARD — Reinsurance-held LRC asset. V32 seed,
     * ifrs17_role=LRC_REINSURANCE. Set up at confirm time
     * ({@code SubledgerPostingService.replayFacPremiumCeded}) at the NET
     * ceded premium (§65 commission-netting), then run down (credited) to
     * expense over the cover period by this engine — the sign-flip mirror
     * of {@link #COA_INWARD_LRC}, which is a liability credited at accept
     * and debited down as it releases to income.
     */
    static final String COA_REINSURANCE_HELD_LRC_ASSET = "1410";

    // ── Idempotency triple slot values ───────────────────────────────────────
    static final String MODULE_PAA = "paa";
    static final String EVENT_LRC_RECOGNITION = "LRC_RECOGNITION";

    /**
     * Decimal scale matching DECIMAL(18,2) on paa_lrc / journal_entry_line.
     * Package-private (Task 5 fix round 2) so {@code FacDerecognitionListener}
     * reuses this constant instead of duplicating it.
     */
    static final int MONEY_SCALE = 2;

    /** Scale for intermediate fraction computation — big enough to keep round-trip errors below 1 kobo on yearly policies. */
    private static final int FRACTION_SCALE = 12;

    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final GroupOfContractsRepository groupRepository;
    private final ContractGroupAssignmentRepository assignmentRepository;
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
        List<ContractGroupAssignment> assignments =
            assignmentRepository.findByGroupIdAndDeletedAtIsNullOrderByAssignedAtAsc(group.getId());

        if (assignments.isEmpty()) {
            return GroupRollForward.empty();
        }

        BigDecimal opening = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal closing = BigDecimal.ZERO;
        String currency = null;

        for (ContractGroupAssignment a : assignments) {
            PolicyPricing policy = loadPricing(a.getContractType(), a.getContractId());
            if (policy == null) {
                log.warn("Skipping assignment {} ({}) — contract {} not found, deleted, or not yet "
                        + "supported for LRC pricing", a.getId(), a.getContractType(), a.getContractId());
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
            if (periodStart.isBefore(p.startDate)) return p.premiumAmount;
            return BigDecimal.ZERO;
        }
        long daysRemaining = daysBetween(periodStart, p.endDate);
        return premiumPortion(p, daysRemaining);
    }

    /** Premium remaining at the end of the period (days from period.end+1 through policy.end). */
    static BigDecimal closingAmount(PolicyPricing p, LocalDate periodStart, LocalDate periodEnd) {
        LocalDate dayAfterPeriod = periodEnd.plusDays(1);
        if (dayAfterPeriod.isAfter(p.endDate)) return BigDecimal.ZERO;
        if (dayAfterPeriod.isBefore(p.startDate)) return p.premiumAmount;
        long daysRemaining = daysBetween(dayAfterPeriod, p.endDate);
        return premiumPortion(p, daysRemaining);
    }

    /** Premium received during the period — full premium if policy.start falls in [period.start, period.end], else 0. */
    static BigDecimal receivedAmount(PolicyPricing p, LocalDate periodStart, LocalDate periodEnd) {
        if (!p.startDate.isBefore(periodStart) && !p.startDate.isAfter(periodEnd)) {
            return p.premiumAmount;
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
        return p.premiumAmount
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
        NatureAccounts accounts = accountsFor(group.getPortfolio().getContractNature());

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            accounts.debitAccount(), rf.earned, BigDecimal.ZERO, rf.currency,
            group.getCohortYear(), group.getPortfolio().getId(), group.getId(),
            null, null);

        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            accounts.creditAccount(), BigDecimal.ZERO, rf.earned, rf.currency,
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

    /**
     * Nature-selected LRC posting accounts, resolved once per group from
     * {@link Portfolio#getContractNature()}. DIRECT and FAC_INWARD both
     * release a <em>liability</em> to revenue — {@code (debit=LRC, credit=
     * revenue)}, matching how {@link #postJe} always posts
     * {@code Dr <debitAccount>=earned / Cr <creditAccount>=earned}.
     *
     * <p>{@code FAC_OUTWARD} is the mirror-image <em>asset</em> shape (Task
     * 4): the reinsurance-held asset is <em>credited</em> (run down) as it
     * amortises, and the corresponding outward RI premium expense is
     * <em>debited</em>. So for FAC_OUTWARD, {@code NatureAccounts.debitAccount}
     * is the expense account ({@link #COA_RI_PREMIUM_EXPENSE}) and
     * {@code creditAccount} is the asset account
     * ({@link #COA_REINSURANCE_HELD_LRC_ASSET}) — package-private (Task 5)
     * so {@code FacDerecognitionListener} / {@code FacPaaCutoverService} can
     * reuse the exact same nature-account resolution for a derecognition or
     * cutover-catch-up JE — the same
     * {@code (debitAccount, creditAccount)} tuple shape as DIRECT/FAC_INWARD,
     * just pointed at a debit-expense/credit-asset pair instead of a
     * debit-liability/credit-revenue pair.
     */
    static NatureAccounts accountsFor(ContractNature nature) {
        return switch (nature) {
            case DIRECT -> new NatureAccounts(COA_LRC_BEL, COA_REVENUE_LRC_RELEASE);
            case FAC_INWARD -> new NatureAccounts(COA_INWARD_LRC, COA_INWARD_PREMIUM_INCOME);
            case FAC_OUTWARD -> new NatureAccounts(COA_RI_PREMIUM_EXPENSE, COA_REINSURANCE_HELD_LRC_ASSET);
        };
    }

    /**
     * Dispatches the pricing lookup by {@link ContractType} — the LRC basis
     * differs per contract type: a direct policy's <em>net</em> premium
     * ({@code policies.net_premium}); an accepted inward FAC's
     * <em>gross</em> premium ({@code ri_fac_inwards.gross_premium} — the
     * whole gross premium is what {@code SubledgerPostingService
     * .replayFacPremiumAccepted} sets up as the LRC liability at accept
     * time, so gross is what this engine must release to income over the
     * cover period); a ceded outward FAC's <em>net</em> premium
     * ({@code ri_fac_covers.net_premium} — §65 commission-netting: the
     * commission is netted into the asset at confirm time by
     * {@code SubledgerPostingService.replayFacPremiumCeded}, so net is what
     * this engine must amortise to expense over the cover period).
     *
     * <p><strong>In-force filter (FAC / IFRS-17 PAA workstream Task 5, fix
     * round 2 - C2).</strong> {@link #loadFacInwardPricing} / {@link
     * #loadFacOutwardPricing} filter on the contract's in-force status
     * ({@code ACTIVE} / {@code CONFIRMED}) in addition to {@code deleted_at
     * IS NULL}. This engine is stateless and re-derives every group's
     * earning from scratch on every {@link #recognise} call - without this
     * filter, a CANCELLED contract (whose remaining balance {@code
     * FacDerecognitionListener} already released via a one-time GL JE) would
     * keep being "found" and re-earned every subsequent period, silently
     * double-booking against the account the derecognition JE already
     * zeroed out. Excluding the row from the denominator computation
     * (rather than shrinking {@code cover_to}, which would rebase the
     * original-window day-count denominator and retroactively corrupt
     * already-posted prior periods) is the safe way to stop future earning.
     */
    PolicyPricing loadPricing(ContractType type, UUID contractId) {
        return switch (type) {
            case POLICY -> loadPolicyPricing(contractId);
            case FAC_INWARD -> loadFacInwardPricing(contractId);
            case FAC_OUTWARD -> loadFacOutwardPricing(contractId);
        };
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

    /**
     * LRC basis = gross premium (see {@link #loadPricing} javadoc for why).
     * Mirrors {@link #loadPolicyPricing}'s native-SQL list-query pattern —
     * avoids pulling a cia-reinsurance entity into cia-finance's persistence
     * context (same loose-coupling seam {@code ContractGroupingService}
     * already established for {@code ri_fac_inwards.cover_from}).
     *
     * <p>{@code status = 'ACTIVE'} (Task 5 fix round 2) — a CANCELLED inward
     * cover returns no pricing, so {@link #computeRollForward} skips it (logs
     * and continues) and it earns zero in every future period. Matches
     * {@code RiFacInwardStatus.ACTIVE}, the only in-force inward status.
     */
    private PolicyPricing loadFacInwardPricing(UUID facInwardId) {
        List<PolicyPricing> rows = jdbcTemplate.query(
            "SELECT cover_from, cover_to, gross_premium, currency_code " +
            "FROM ri_fac_inwards WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL",
            (rs, rowNum) -> new PolicyPricing(
                rs.getDate("cover_from").toLocalDate(),
                rs.getDate("cover_to").toLocalDate(),
                rs.getBigDecimal("gross_premium"),
                rs.getString("currency_code")),
            facInwardId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * LRC basis = NET premium (§65 commission-netting — see {@link
     * #loadPricing} javadoc). Mirrors {@link #loadFacInwardPricing}'s
     * native-SQL list-query pattern.
     *
     * <p>{@code status = 'CONFIRMED'} (Task 5 fix round 2) — a CANCELLED
     * outward cover returns no pricing, for the same in-force-only reason as
     * {@link #loadFacInwardPricing}. Matches {@code FacCoverStatus.CONFIRMED},
     * the only in-force outward status.
     */
    private PolicyPricing loadFacOutwardPricing(UUID facCoverId) {
        List<PolicyPricing> rows = jdbcTemplate.query(
            "SELECT cover_from, cover_to, net_premium, currency_code " +
            "FROM ri_fac_covers WHERE id = ? AND status = 'CONFIRMED' AND deleted_at IS NULL",
            (rs, rowNum) -> new PolicyPricing(
                rs.getDate("cover_from").toLocalDate(),
                rs.getDate("cover_to").toLocalDate(),
                rs.getBigDecimal("net_premium"),
                rs.getString("currency_code")),
            facCoverId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Internal aggregates ──────────────────────────────────────────────────

    /**
     * Snapshot of the contract fields the engine needs — pulled via native
     * SQL, not a JPA entity, to avoid cross-module entity coupling.
     * {@code premiumAmount} is the LRC basis: net premium for {@code POLICY},
     * gross premium for {@code FAC_INWARD}, net premium for
     * {@code FAC_OUTWARD} — see {@link #loadPricing}.
     */
    record PolicyPricing(LocalDate startDate, LocalDate endDate, BigDecimal premiumAmount, String currencyCode) {}

    /**
     * Nature-selected posting accounts for one group's period JE. For
     * DIRECT/FAC_INWARD this is {@code (debit=LRC liability, credit=revenue)}
     * — a liability release. For FAC_OUTWARD this is {@code (debit=RI
     * premium expense, credit=reinsurance-held asset)} — the mirror-image
     * asset amortisation. In both cases {@link #postJe} posts
     * {@code Dr debitAccount=earned / Cr creditAccount=earned} unchanged;
     * only the account identities differ by nature.
     */
    record NatureAccounts(String debitAccount, String creditAccount) {}

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
