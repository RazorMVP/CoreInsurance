package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.event.FacDerecognisedEvent;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.JournalEntryRepository;
import com.nubeero.cia.finance.gl.JournalEntryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FAC / IFRS-17 PAA workstream Task 5 — derecognises a cancelled facultative
 * reinsurance contract's remaining LRC liability (inward) or unamortised
 * reinsurance-held asset (outward), releasing it in a single GL journal
 * entry dated the cancellation's effective date.
 *
 * <p>Listens for {@link FacDerecognisedEvent}, published by {@code
 * RiFacInwardService.cancel} / {@code FacCoverService.cancel} after the
 * contract's status row is persisted as {@code CANCELLED}.
 *
 * <h2>GL is the sole source of truth — no {@code paa_lrc} write (fix round 2)</h2>
 * <p>{@link LrcEngine} is a <em>stateless</em> batch engine: {@code
 * recognise(periodId)} recomputes every group's earning from scratch from
 * contract dates on every call, and is the SOLE writer of {@code paa_lrc}
 * rows — uniquely keyed {@code (group, period)} by {@code
 * uq_paa_lrc_group_period}, with a service-layer idempotency pre-check
 * ({@link LrcRecognitionAlreadyDoneException}) that runs BEFORE the
 * zero-activity skip. An earlier version of this listener also wrote an
 * ad-hoc {@code paa_lrc} row for the derecognition — that collided with the
 * engine's own row for the SAME {@code (group, period)} the next time {@code
 * recognise} ran for that period, throwing and rolling back recognition for
 * EVERY group in the tenant, not just the cancelled FAC's. This listener now
 * posts the GL journal entry ONLY; {@link LrcEngine#loadFacInwardPricing} /
 * {@link LrcEngine#loadFacOutwardPricing} filter on the contract's in-force
 * status, so once cancelled the engine simply stops finding — and therefore
 * stops re-earning — the contract in every future period. No {@code
 * paa_lrc} row ever needs to exist for a derecognition event; the {@code
 * paa_lrc}-level disclosure of this movement (so the roll-forward view
 * reflects it) is Task 6's job (V78 movement-view rebuild), not this one's.
 *
 * <h2>Remaining-balance computation — per-contract (final-review Critical fix)</h2>
 * <p>Releases ONLY the cancelled contract's own remaining carrying balance,
 * never the group aggregate. Task 2's grouping keys purely off
 * portfolio/cohort/onerousness (onerousness always {@code NOT_ONEROUS} in
 * v1), so every same-(class, nature, cover-start-year) FAC contract pools
 * into ONE group — multi-contract groups are the default for facultative
 * business. An earlier version released the group's aggregate {@code
 * paa_lrc.closing_balance}; on a single contract's cancellation that
 * over-released the surviving contracts' unearned balance to income and then
 * double-earned them next period (the in-force filter keeps earning the
 * survivors), driving {@code 2210}/{@code 1410} negative — silently, in GL
 * balances.
 *
 * <p>The remaining balance is computed per-contract as {@code premium −
 * Σ(this contract's earned slice across every period the group has a
 * paa_lrc row for)}:
 * <ul>
 *   <li>The contract's own {@code (cover_from, cover_to, premium, currency)}
 *       come from a <em>status-agnostic</em> direct read ({@link
 *       #readPricingStatusAgnostic}) — the contract is already CANCELLED by
 *       the time this fires, so {@link LrcEngine#loadPricing}'s in-force
 *       filter would (correctly) return nothing. Basis matches {@link
 *       LrcEngine#loadPricing}: gross for inward, net for outward (§65).</li>
 *   <li>The already-recognised amount is summed from the contract's OWN dates
 *       via {@link LrcEngine#earnedAmount} over each period the group was
 *       recognised in ({@link #sumEarnedOverRecognisedPeriods}) — the exact
 *       per-period slice the periodic engine posted for this contract, so
 *       {@code premium − Σ earned} equals its precise GL carrying.</li>
 *   <li><strong>No {@code paa_lrc} history</strong> (cancelled before any
 *       {@code recognise()} ever ran) collapses to the special case where the
 *       sum is zero → release the FULL LRC-basis premium (the accept-time
 *       standing balance that {@code SubledgerPostingService
 *       .replayFacPremiumAccepted}/{@code replayFacPremiumCeded} set up and
 *       that would otherwise linger forever) — preserving the prior
 *       no-history behaviour exactly.</li>
 * </ul>
 * <p>The release is clamped {@code >= 0}.
 *
 * <h2>Posting shape (reused from {@link LrcEngine})</h2>
 * <p>{@link LrcEngine#accountsFor} resolves the same {@code (debitAccount,
 * creditAccount)} pair the periodic release engine uses for the group's
 * {@link com.nubeero.cia.finance.paa.Portfolio#getContractNature()} — DIRECT
 * and FAC_INWARD release a liability ({@code Dr LRC / Cr revenue}) and
 * FAC_OUTWARD is the mirror asset run-down ({@code Dr expense / Cr asset}).
 * Derecognition posts the SAME shape for the FULL remaining balance instead
 * of one period's earned slice.
 *
 * <h2>Idempotency</h2>
 * <p>The JE idempotency triple is {@code (paa, FAC_DERECOGNITION,
 * "<contractType>:<contractId>")} — unique per contract (a contract is
 * derecognised at most once; {@code cancel()} itself rejects a second
 * cancellation with a 4xx before ever re-publishing the event). A pre-check
 * against {@link JournalEntryRepository} short-circuits a re-fired event
 * rather than surfacing a raw {@code JournalEntryDuplicateException}.
 *
 * <h2>Period-lock enforcement</h2>
 * <p>The JE posts through the same {@link JournalEntryService#post} gateway
 * every other posting in this module uses — {@link
 * com.nubeero.cia.common.entity.LockableByPeriod} enforcement on {@code
 * JournalEntry} (via {@code PeriodLockInterceptor}, when wired into the
 * EntityManagerFactory) applies automatically for the resolved business
 * date, with no special-casing needed here.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class FacDerecognitionListener {

    static final String EVENT_FAC_DERECOGNITION = "FAC_DERECOGNITION";

    private final ContractGroupAssignmentRepository assignmentRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalEntryService journalEntryService;
    private final JdbcTemplate jdbcTemplate;

    @EventListener
    @Transactional
    public void onFacDerecognised(FacDerecognisedEvent event) {
        ContractType financeType = toFinanceType(event.contractType());

        Optional<ContractGroupAssignment> assignmentOpt = assignmentRepository
            .findByContractTypeAndContractIdAndDeletedAtIsNull(financeType, event.contractId());
        if (assignmentOpt.isEmpty()) {
            log.warn("FacDerecognisedEvent for {} {} — no contract_group_assignment found; "
                    + "nothing to derecognise (contract was never grouped under PAA)",
                event.contractType(), event.contractId());
            return;
        }
        GroupOfContracts group = assignmentOpt.get().getGroup();

        String idempotencyRef = event.contractType() + ":" + event.contractId();
        if (journalEntryRepository.findBySourceModuleAndSourceEventTypeAndSourceReference(
                LrcEngine.MODULE_PAA, EVENT_FAC_DERECOGNITION, idempotencyRef).isPresent()) {
            log.info("Derecognition already posted for {} {} — skipping (idempotent)",
                event.contractType(), event.contractId());
            return;
        }

        // Per-contract remaining carrying (final-review Critical fix). Release
        // ONLY the cancelled contract's own remaining balance, never the group
        // aggregate — see the class javadoc for why the group aggregate
        // over-releases surviving contracts in a multi-contract group.
        LrcEngine.PolicyPricing pricing = readPricingStatusAgnostic(financeType, event.contractId());
        if (pricing == null || pricing.premiumAmount() == null) {
            log.info("No premium found for contract {} {} — nothing to derecognise",
                event.contractType(), event.contractId());
            return;
        }
        String currency = pricing.currencyCode();

        // gross − Σ(this contract's own earned slice over every recognised
        // period) == the contract's exact GL carrying. No paa_lrc history ⇒
        // sum is zero ⇒ full premium released (the no-history special case).
        BigDecimal earnedToDate = sumEarnedOverRecognisedPeriods(group.getId(), pricing);
        BigDecimal remaining = pricing.premiumAmount()
            .subtract(earnedToDate)
            .setScale(LrcEngine.MONEY_SCALE, RoundingMode.HALF_UP);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Contract {} {} has no remaining LRC/asset balance to derecognise "
                    + "(premium {} already fully recognised)",
                event.contractType(), event.contractId(), pricing.premiumAmount());
            return;
        }

        LrcEngine.NatureAccounts accounts = LrcEngine.accountsFor(group.getPortfolio().getContractNature());

        JournalEntryLineRequest debit = new JournalEntryLineRequest(
            accounts.debitAccount(), remaining, BigDecimal.ZERO, currency,
            group.getCohortYear(), group.getPortfolio().getId(), group.getId(), null, null);
        JournalEntryLineRequest credit = new JournalEntryLineRequest(
            accounts.creditAccount(), BigDecimal.ZERO, remaining, currency,
            group.getCohortYear(), group.getPortfolio().getId(), group.getId(), null, null);

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            event.effectiveDate(),
            LrcEngine.MODULE_PAA,
            EVENT_FAC_DERECOGNITION,
            idempotencyRef,
            "Derecognition of " + event.contractType() + " " + event.contractId()
                + " — remaining balance released for group " + group.getPortfolio().getCode() + "/"
                + group.getCohortYear() + "/" + group.getOnerousness(),
            List.of(debit, credit));

        journalEntryService.post(request);
    }

    /**
     * Status-agnostic per-contract pricing read — deliberately does NOT filter
     * on status (the contract is already CANCELLED by the time this fires, so
     * {@link LrcEngine#loadPricing}'s in-force filter would return nothing).
     * Returns the contract's own {@code (cover_from, cover_to, premium,
     * currency)} as an {@link LrcEngine.PolicyPricing} carrier — the LRC basis
     * is gross for inward (the accept-time liability basis), net for outward
     * (§65 commission-netting), matching {@link LrcEngine#loadPricing}'s
     * per-nature basis exactly.
     */
    private LrcEngine.PolicyPricing readPricingStatusAgnostic(ContractType type, UUID contractId) {
        String sql = switch (type) {
            case FAC_INWARD -> "SELECT cover_from, cover_to, gross_premium, currency_code "
                + "FROM ri_fac_inwards WHERE id = ? AND deleted_at IS NULL";
            case FAC_OUTWARD -> "SELECT cover_from, cover_to, net_premium, currency_code "
                + "FROM ri_fac_covers WHERE id = ? AND deleted_at IS NULL";
            case POLICY -> throw new IllegalStateException(
                "FacDerecognitionListener never handles ContractType.POLICY");
        };
        List<LrcEngine.PolicyPricing> rows = jdbcTemplate.query(sql,
            (rs, rowNum) -> new LrcEngine.PolicyPricing(
                rs.getDate(1).toLocalDate(),
                rs.getDate(2).toLocalDate(),
                rs.getBigDecimal(3),
                rs.getString(4)),
            contractId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Sums this contract's OWN earned slice across every period the group was
     * recognised in — i.e. every period with a {@code paa_lrc} row for the
     * group. This contract's contribution to each of those periods' group JE
     * was exactly {@link LrcEngine#earnedAmount}(pricing, period.start,
     * period.end), so the sum is the total already recognised into income for
     * this contract by prior {@code recognise()} closes. Reading the periods
     * from {@code paa_lrc} (not from an assumed contiguous range) means the
     * sum exactly mirrors the GL postings regardless of which periods were
     * closed. A group with no {@code paa_lrc} rows yields zero (nothing
     * earned yet) — the no-history case, where the full premium is released.
     */
    private BigDecimal sumEarnedOverRecognisedPeriods(UUID groupId, LrcEngine.PolicyPricing pricing) {
        List<PeriodBounds> periods = jdbcTemplate.query(
            "SELECT fp.start_date, fp.end_date "
                + "FROM paa_lrc pl JOIN fiscal_period fp ON fp.id = pl.period_id "
                + "WHERE pl.group_id = ? AND pl.deleted_at IS NULL",
            (rs, rowNum) -> new PeriodBounds(
                rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date").toLocalDate()),
            groupId);

        BigDecimal total = BigDecimal.ZERO;
        for (PeriodBounds p : periods) {
            total = total.add(LrcEngine.earnedAmount(pricing, p.start(), p.end()));
        }
        return total;
    }

    private static ContractType toFinanceType(FacDerecognisedEvent.ContractType eventType) {
        return switch (eventType) {
            case FAC_INWARD -> ContractType.FAC_INWARD;
            case FAC_OUTWARD -> ContractType.FAC_OUTWARD;
        };
    }

    private record PeriodBounds(java.time.LocalDate start, java.time.LocalDate end) {}
}
