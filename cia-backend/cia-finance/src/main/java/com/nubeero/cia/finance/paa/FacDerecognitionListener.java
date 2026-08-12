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
 * <h2>Remaining-balance computation</h2>
 * <p>Two branches, both keyed off the cancelled contract's <em>group</em>
 * (Task 2's grouping keys purely off portfolio/cohort/onerousness — a
 * multi-contract group is possible; releasing the whole group's balance on
 * one contract's cancellation is a known v1-scope simplification, exact for
 * today's only production shape: single-contract groups):
 * <ol>
 *   <li><strong>Group has {@code paa_lrc} history</strong> — release the
 *       group's latest closing balance ({@link
 *       PaaLrcRepository#findFirstByGroupIdAndDeletedAtIsNullOrderByPeriodEndDateDesc}).</li>
 *   <li><strong>Group has no {@code paa_lrc} history yet</strong> (cancelled
 *       before any {@code recognise()} ever ran for it) — the accept-time
 *       posting ({@code SubledgerPostingService.replayFacPremiumAccepted} /
 *       {@code replayFacPremiumCeded}) already set up the FULL LRC-basis
 *       premium as a standing balance ({@code 2210} gross for inward,
 *       {@code 1410} net for outward) that would otherwise linger forever.
 *       Release it via a small <em>status-agnostic</em> direct read
 *       ({@link #readFullPremiumStatusAgnostic}) — the contract is already
 *       CANCELLED at this point, so {@link LrcEngine#loadPricing}'s
 *       in-force filter would (correctly) return nothing.</li>
 * </ol>
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
    private final PaaLrcRepository lrcRepository;
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

        BigDecimal remaining;
        String currency;

        Optional<PaaLrc> latest =
            lrcRepository.findFirstByGroupIdAndDeletedAtIsNullOrderByPeriodEndDateDesc(group.getId());
        if (latest.isPresent()) {
            PaaLrc lastRow = latest.get();
            remaining = lastRow.getClosingBalance().setScale(LrcEngine.MONEY_SCALE, RoundingMode.HALF_UP);
            currency = lastRow.getCurrencyCode();
        } else {
            FullPremium full = readFullPremiumStatusAgnostic(financeType, event.contractId());
            if (full == null || full.amount() == null) {
                log.info("Group {} has no paa_lrc history and no premium found for contract {} {} — "
                        + "nothing to derecognise", group.getId(), event.contractType(), event.contractId());
                return;
            }
            remaining = full.amount().setScale(LrcEngine.MONEY_SCALE, RoundingMode.HALF_UP);
            currency = full.currencyCode();
        }

        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Group {} has no remaining LRC/asset balance to derecognise (contract {} {})",
                group.getId(), event.contractType(), event.contractId());
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
     * Status-agnostic full-premium read for the "cancelled before any
     * recognise() ever ran" branch — deliberately does NOT filter on
     * status (the contract is already CANCELLED by the time this fires, so
     * {@link LrcEngine#loadPricing}'s in-force filter would return nothing).
     * Gross for inward (the accept-time LRC basis), net for outward (§65
     * commission-netting basis) — matches {@link LrcEngine#loadPricing}'s
     * per-nature basis exactly.
     */
    private FullPremium readFullPremiumStatusAgnostic(ContractType type, UUID contractId) {
        String sql = switch (type) {
            case FAC_INWARD -> "SELECT gross_premium, currency_code FROM ri_fac_inwards "
                + "WHERE id = ? AND deleted_at IS NULL";
            case FAC_OUTWARD -> "SELECT net_premium, currency_code FROM ri_fac_covers "
                + "WHERE id = ? AND deleted_at IS NULL";
            case POLICY -> throw new IllegalStateException(
                "FacDerecognitionListener never handles ContractType.POLICY");
        };
        List<FullPremium> rows = jdbcTemplate.query(sql,
            (rs, rowNum) -> new FullPremium(rs.getBigDecimal(1), rs.getString(2)),
            contractId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static ContractType toFinanceType(FacDerecognisedEvent.ContractType eventType) {
        return switch (eventType) {
            case FAC_INWARD -> ContractType.FAC_INWARD;
            case FAC_OUTWARD -> ContractType.FAC_OUTWARD;
        };
    }

    private record FullPremium(BigDecimal amount, String currencyCode) {}
}
