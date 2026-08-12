package com.nubeero.cia.finance.paa;

import com.nubeero.cia.common.event.FacDerecognisedEvent;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodResolver;
import com.nubeero.cia.finance.gl.JournalEntryRepository;
import com.nubeero.cia.finance.gl.JournalEntryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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
 * reinsurance-held asset (outward), releasing it in the currently OPEN
 * fiscal period.
 *
 * <p>Listens for {@link FacDerecognisedEvent}, published by {@code
 * RiFacInwardService.cancel} / {@code FacCoverService.cancel} after the
 * contract's status row is persisted as {@code CANCELLED}.
 *
 * <h2>Remaining-balance computation (v1 scope)</h2>
 * <p>The remaining balance is read off the cancelled contract's <em>group's</em>
 * latest {@link PaaLrc} closing balance ({@link
 * PaaLrcRepository#findFirstByGroupIdAndDeletedAtIsNullOrderByPeriodEndDateDesc}),
 * not recomputed from the contract's own pricing. This is exact for a
 * single-contract group (today's only production shape — Task 2's grouping
 * keys purely off portfolio/cohort/onerousness, so multiple FAC contracts
 * CAN land in the same group); a future multi-contract-per-group scenario
 * would need this handler to compute the cancelled contract's own unearned
 * portion instead of releasing the whole group's balance. Not a concern at
 * this workstream's scope — flagged here for the next reader.
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

    private static final int MONEY_SCALE = 2;

    private final ContractGroupAssignmentRepository assignmentRepository;
    private final PaaLrcRepository lrcRepository;
    private final FiscalPeriodResolver fiscalPeriodResolver;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalEntryService journalEntryService;

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

        Optional<PaaLrc> latest =
            lrcRepository.findFirstByGroupIdAndDeletedAtIsNullOrderByPeriodEndDateDesc(group.getId());
        if (latest.isEmpty()) {
            log.info("Group {} has no paa_lrc history — nothing recognised yet for contract {} {}, "
                    + "no derecognition posting needed", group.getId(), event.contractType(), event.contractId());
            return;
        }

        PaaLrc lastRow = latest.get();
        BigDecimal remaining = lastRow.getClosingBalance().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            log.info("Group {} has no remaining LRC/asset balance to derecognise (contract {} {})",
                group.getId(), event.contractType(), event.contractId());
            return;
        }

        FiscalPeriod period = fiscalPeriodResolver.resolveMonthForBusinessDate(event.effectiveDate());
        String currency = lastRow.getCurrencyCode();

        // Roll forward: the whole remaining balance is "earned" (released) at
        // once, so the group's next roll-forward row correctly opens at zero.
        PaaLrc derecognitionRow = new PaaLrc();
        derecognitionRow.setGroup(group);
        derecognitionRow.setPeriod(period);
        derecognitionRow.setOpeningBalance(remaining);
        derecognitionRow.setPremiumReceived(BigDecimal.ZERO.setScale(MONEY_SCALE));
        derecognitionRow.setPremiumEarned(remaining);
        derecognitionRow.setClosingBalance(BigDecimal.ZERO.setScale(MONEY_SCALE));
        derecognitionRow.setCurrencyCode(currency);
        lrcRepository.save(derecognitionRow);

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

    private static ContractType toFinanceType(FacDerecognisedEvent.ContractType eventType) {
        return switch (eventType) {
            case FAC_INWARD -> ContractType.FAC_INWARD;
            case FAC_OUTWARD -> ContractType.FAC_OUTWARD;
        };
    }
}
