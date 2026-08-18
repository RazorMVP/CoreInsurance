package com.nubeero.cia.finance.paa;

import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import com.nubeero.cia.finance.gl.JournalEntryRepository;
import com.nubeero.cia.finance.gl.JournalEntryService;
import com.nubeero.cia.finance.gl.PeriodLockService;
import com.nubeero.cia.finance.gl.PolicyClassResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * FAC / IFRS-17 PAA workstream Task 5 — modified-prospective transition for
 * facultative reinsurance contracts that were in force before this system's
 * PAA measurement went live.
 *
 * <p>{@link #runCutover(UUID)} is a one-time, idempotent-per-contract
 * operation:
 * <ol>
 *   <li>enumerates in-force FAC contracts (inward {@code ACTIVE}, outward
 *       {@code CONFIRMED}, not soft-deleted) that have no {@link
 *       ContractGroupAssignment} yet — i.e. contracts that pre-date Task 2's
 *       grouping listeners;</li>
 *   <li>groups each one via {@link ContractGroupingService#assign} directly
 *       (NOT by re-publishing {@code RiFacInwardAcceptedEvent} /
 *       {@code FacPremiumCededEvent} — that would also re-trigger {@code
 *       SubledgerPostingService}'s accept-time GL posting, which is wrong
 *       for a contract that was already accepted/confirmed before this
 *       system existed);</li>
 *   <li>posts a one-time catch-up journal entry for the premium "earned"
 *       from the contract's cover inception through the day BEFORE the
 *       cutover period starts — the backlog no prior period ever
 *       recognised — bounded entirely into the given (OPEN) period. Coverage
 *       from the period's start date onward is left for the next ordinary
 *       {@link LrcEngine#recognise(UUID)} run, exactly like a
 *       normally-tracked contract's roll-forward.</li>
 * </ol>
 *
 * <h2>Period-lock guard</h2>
 * <p>{@link PeriodLockService#assertOpenForPosting(UUID)} runs FIRST, before
 * any enumeration, grouping, or posting — a cutover against a closed period
 * throws {@link com.nubeero.cia.finance.gl.PeriodLockedException} with zero
 * side effects.
 *
 * <h2>Reuse from {@link LrcEngine}</h2>
 * <p>The catch-up amount uses the exact same day-count math ({@link
 * LrcEngine#daysBetween} / {@link LrcEngine#premiumPortion}) and pricing
 * dispatch ({@link LrcEngine#loadPricing}) the periodic engine uses, and
 * posts through the same nature-selected accounts ({@link
 * LrcEngine#accountsFor}) — {@code Dr 2210 / Cr 4330} for FAC_INWARD,
 * {@code Dr 5210 / Cr 1410} for FAC_OUTWARD.
 *
 * <h2>GL is the sole source of truth (fix round 2)</h2>
 * <p>{@link #postCatchUp} posts the catch-up GL journal entry ONLY — it does
 * NOT write a {@code paa_lrc} row (see {@link #postCatchUp}'s javadoc for
 * why: it would collide with {@link LrcEngine#recognise(UUID)}'s own row for
 * the same {@code (group, period)} and abort recognition tenant-wide). The
 * subsequent ordinary {@code recognise(periodId)} call for the open period
 * produces the group's first {@code paa_lrc} row on its own, correctly.
 *
 * <h2>Idempotency across repeated {@code runCutover} calls</h2>
 * <p>Re-running {@link #runCutover(UUID)} against the same (or a later)
 * period is a no-op for contracts already grouped — {@link
 * #findUngroupedInward} / {@link #findUngroupedOutward} exclude any contract
 * that already has a {@link ContractGroupAssignment} row, so a second call
 * never re-enumerates (and therefore never re-catches-up) a contract the
 * first call already processed. {@link #postCatchUp}'s own JE idempotency
 * pre-check is a second, independent guard for the same invariant.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class FacPaaCutoverService {

    static final String EVENT_PAA_CUTOVER = "PAA_CUTOVER";

    private final PeriodLockService periodLockService;
    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final ContractGroupingService contractGroupingService;
    private final LrcEngine lrcEngine;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalEntryService journalEntryService;
    private final PolicyClassResolver policyClassResolver;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs the cutover for every ungrouped in-force FAC contract, posting
     * each one's catch-up entirely into {@code periodId}.
     *
     * @throws com.nubeero.cia.finance.gl.PeriodLockedException if {@code
     *         periodId} is not OPEN (SOFT- or HARD-closed) — checked BEFORE
     *         any enumeration or posting.
     */
    public CutoverResult runCutover(UUID periodId) {
        periodLockService.assertOpenForPosting(periodId);

        FiscalPeriod period = fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));

        log.info("FAC PAA cutover starting for period {} ({} → {})",
            periodId, period.getStartDate(), period.getEndDate());

        List<InForceContract> inward = findUngroupedInward();
        List<InForceContract> outward = findUngroupedOutward();

        int contractsGrouped = 0;
        BigDecimal totalCatchUp = BigDecimal.ZERO;
        List<CutoverResult.CutoverEntry> entries = new ArrayList<>();

        for (InForceContract c : inward) {
            ContractGroupAssignment assignment = contractGroupingService.assign(
                ContractType.FAC_INWARD, c.id(), c.classOfBusinessId(), c.coverFrom().getYear(),
                ContractNature.FAC_INWARD);
            contractsGrouped++;
            BigDecimal earned = postCatchUp(assignment.getGroup(), ContractType.FAC_INWARD, c.id(), period);
            totalCatchUp = totalCatchUp.add(earned);
            entries.add(new CutoverResult.CutoverEntry(
                ContractType.FAC_INWARD, c.id(), assignment.getGroup().getId(), earned));
        }

        for (InForceContract c : outward) {
            ContractGroupAssignment assignment = contractGroupingService.assign(
                ContractType.FAC_OUTWARD, c.id(), c.classOfBusinessId(), c.coverFrom().getYear(),
                ContractNature.FAC_OUTWARD);
            contractsGrouped++;
            BigDecimal earned = postCatchUp(assignment.getGroup(), ContractType.FAC_OUTWARD, c.id(), period);
            totalCatchUp = totalCatchUp.add(earned);
            entries.add(new CutoverResult.CutoverEntry(
                ContractType.FAC_OUTWARD, c.id(), assignment.getGroup().getId(), earned));
        }

        log.info("FAC PAA cutover complete for period {} — {} contracts grouped, total catch-up earned {}",
            periodId, contractsGrouped, totalCatchUp);

        return new CutoverResult(periodId, contractsGrouped, totalCatchUp, entries);
    }

    /**
     * Posts the one-time catch-up JE for {@code contractId}'s group, bounded
     * to {@code [pricing.startDate, period.startDate − 1 day]}. Returns zero
     * (no JE) when the contract's cover starts on or after the cutover
     * period's start date — nothing pre-dates the transition for that
     * contract.
     *
     * <p><strong>No {@code paa_lrc} row is written here (fix round 2).</strong>
     * {@link LrcEngine} is the sole writer of {@code paa_lrc} — it recomputes
     * every group's earning from scratch on every {@link
     * LrcEngine#recognise(UUID)} call, keyed uniquely by {@code (group,
     * period)}. Writing an ad-hoc row for {@code (group, periodId)} here
     * would collide with the engine's own row the next time {@code
     * recognise(periodId)} runs for the SAME open period — the engine's
     * idempotency pre-check runs BEFORE its zero-activity skip, so the
     * collision would throw {@link LrcRecognitionAlreadyDoneException} and
     * roll back recognition for EVERY group in the tenant, not just this
     * one. The catch-up GL JE is posted regardless (that IS the mandated
     * accounting); the subsequent ordinary {@code recognise(periodId)} run
     * produces the correct {@code paa_lrc} row for the open period on its
     * own — its {@code openingAmount(period.startDate)} already equals
     * {@code premium − backlog}, which reconciles against the GL account
     * this catch-up JE just reduced by the backlog.
     *
     * <p><strong>Operational ordering assumption:</strong> cutover MUST run
     * before the open period's first {@code recognise()} call — that is the
     * modified-prospective transition sequence (cut over once, then resume
     * normal closes). The backlog {@code [coverFrom, periodStart − 1]}
     * belongs to this catch-up; {@code [periodStart, periodEnd]} onward
     * belongs to {@code recognise()}.
     */
    private BigDecimal postCatchUp(GroupOfContracts group, ContractType type, UUID contractId, FiscalPeriod period) {
        LrcEngine.PolicyPricing pricing = lrcEngine.loadPricing(type, contractId);
        if (pricing == null) {
            log.warn("Cutover: no pricing found for {} {} — skipping catch-up", type, contractId);
            return BigDecimal.ZERO;
        }

        LocalDate catchupThrough = period.getStartDate().minusDays(1);
        if (catchupThrough.isBefore(pricing.startDate())) {
            return BigDecimal.ZERO;
        }
        LocalDate cappedEnd = catchupThrough.isAfter(pricing.endDate()) ? pricing.endDate() : catchupThrough;
        long daysActive = LrcEngine.daysBetween(pricing.startDate(), cappedEnd);
        BigDecimal earned = LrcEngine.premiumPortion(pricing, daysActive);
        if (earned.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        String idempotencyRef = type + ":" + contractId;
        if (journalEntryRepository.findBySourceModuleAndSourceEventTypeAndSourceReference(
                LrcEngine.MODULE_PAA, EVENT_PAA_CUTOVER, idempotencyRef).isPresent()) {
            log.info("Cutover catch-up already posted for {} {} — skipping (idempotent)", type, contractId);
            return BigDecimal.ZERO;
        }

        LrcEngine.NatureAccounts accounts = LrcEngine.accountsFor(group.getPortfolio().getContractNature());

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            accounts.debitAccount(), earned, BigDecimal.ZERO, pricing.currencyCode(),
            group.getCohortYear(), group.getPortfolio().getId(), group.getId(), null, null);
        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            accounts.creditAccount(), BigDecimal.ZERO, earned, pricing.currencyCode(),
            group.getCohortYear(), group.getPortfolio().getId(), group.getId(), null, null);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            period.getStartDate(),
            LrcEngine.MODULE_PAA,
            EVENT_PAA_CUTOVER,
            idempotencyRef,
            "Modified-prospective PAA cutover catch-up for " + type + " " + contractId
                + " — group " + group.getPortfolio().getCode() + "/" + group.getCohortYear()
                + "/" + group.getOnerousness(),
            List.of(debit, credit));
        journalEntryService.post(request);

        return earned;
    }

    private List<InForceContract> findUngroupedInward() {
        return jdbcTemplate.query(
            "SELECT fi.id, fi.class_of_business_id, fi.cover_from " +
            "FROM ri_fac_inwards fi " +
            "WHERE fi.status = 'ACTIVE' AND fi.deleted_at IS NULL " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM contract_group_assignment cga " +
            "  WHERE cga.contract_type = 'FAC_INWARD' AND cga.contract_id = fi.id AND cga.deleted_at IS NULL" +
            ")",
            (rs, rowNum) -> new InForceContract(
                UUID.fromString(rs.getString("id")),
                rs.getString("class_of_business_id") != null ? UUID.fromString(rs.getString("class_of_business_id")) : null,
                rs.getDate("cover_from").toLocalDate()));
    }

    /**
     * The per-row {@link PolicyClassResolver#findClassByPolicyId} call below
     * is an N+1 query pattern — accepted here because {@code runCutover} is
     * a rare one-time (or small, infrequent) batch, not a hot request path;
     * the row count is bounded by "in-force FAC contracts never yet grouped
     * under PAA", which shrinks to zero after the first successful cutover.
     */
    private List<InForceContract> findUngroupedOutward() {
        return jdbcTemplate.query(
            "SELECT fc.id, fc.policy_id, fc.cover_from " +
            "FROM ri_fac_covers fc " +
            "WHERE fc.status = 'CONFIRMED' AND fc.deleted_at IS NULL " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM contract_group_assignment cga " +
            "  WHERE cga.contract_type = 'FAC_OUTWARD' AND cga.contract_id = fc.id AND cga.deleted_at IS NULL" +
            ")",
            (rs, rowNum) -> {
                UUID id = UUID.fromString(rs.getString("id"));
                UUID policyId = rs.getString("policy_id") != null ? UUID.fromString(rs.getString("policy_id")) : null;
                UUID cob = policyClassResolver.findClassByPolicyId(policyId);
                return new InForceContract(id, cob, rs.getDate("cover_from").toLocalDate());
            });
    }

    /** Lightweight row shape for an ungrouped in-force FAC contract, read via raw JDBC to avoid a cia-reinsurance entity dependency. */
    private record InForceContract(UUID id, UUID classOfBusinessId, LocalDate coverFrom) {}
}
