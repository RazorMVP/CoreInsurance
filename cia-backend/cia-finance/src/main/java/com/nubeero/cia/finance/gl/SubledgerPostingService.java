package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.finance.dto.JournalEntryLineRequest;
import com.nubeero.cia.finance.dto.PostJournalEntryRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Bridges sub-ledger business events into the general ledger.
 *
 * <p>Slice 1.5 (Module 12 — Period-End Closures). Every business module
 * publishes Spring application events when a financial state change is
 * approved; this service is the single sibling listener that translates
 * each event into a {@link JournalEntryService#post post} call.
 *
 * <p>Execution model (D1=A): {@link EventListener} + {@link Transactional}.
 * The listener method joins the publisher's transaction via REQUIRED
 * propagation — if posting fails, the publishing business operation rolls
 * back too. Atomicity is non-negotiable for accounting; the
 * {@code UNIQUE(source_module, source_event_type, source_reference)}
 * constraint on {@code journal_entry} closes the duplicate-post risk if
 * the same event is somehow re-fired.
 *
 * <p>Rule resolution (D2=A): five of the six events look up a
 * {@link PostingRule} from {@code posting_rule} (seeded by V33). The sixth,
 * {@link FacPremiumCededEvent}, is compound (3 lines) and the
 * {@code posting_rule} shape (1 Dr + 1 Cr per row) can't express it — the
 * listener builds the JE inline using hardcoded COA codes documented in
 * the method.
 *
 * <p>Idempotency triple (D4=A): every JE this service emits uses
 * {@code (business-module-name, EVENT_CONSTANT, entity.id.toString())} so
 * each JE traces back to the originating business entity by UUID.
 *
 * @see JournalEntryService
 * @see PostingRuleService
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class SubledgerPostingService {

    // ── Source-module names (idempotency triple slot 1) ───────────────────────
    static final String MODULE_POLICY = "policy";
    static final String MODULE_CLAIM = "claim";
    static final String MODULE_ENDORSEMENT = "endorsement";
    static final String MODULE_REINSURANCE = "reinsurance";

    // ── Source-event-type constants (idempotency triple slot 2) ──────────────
    // Must match V33__seed_posting_rules.sql exactly.
    static final String EVENT_POLICY_APPROVED = "POLICY_APPROVED";
    static final String EVENT_CLAIM_APPROVED = "CLAIM_APPROVED";
    static final String EVENT_CLAIM_SETTLED = "CLAIM_SETTLED";
    static final String EVENT_CLAIM_EXPENSE_APPROVED = "CLAIM_EXPENSE_APPROVED";
    static final String EVENT_ENDORSEMENT_PREMIUM_ADDITIONAL = "ENDORSEMENT_PREMIUM_ADDITIONAL";
    static final String EVENT_ENDORSEMENT_PREMIUM_REFUND = "ENDORSEMENT_PREMIUM_REFUND";
    static final String EVENT_FAC_PREMIUM_CEDED = "FAC_PREMIUM_CEDED";

    // ── Hardcoded COA codes for the compound FAC 3-line posting ──────────────
    private static final String COA_RI_PREMIUM_EXPENSE = "5210";   // Outward reinsurance premium
    private static final String COA_RI_COMMISSION_INCOME = "4300"; // Reinsurance income (ceded)
    private static final String COA_RI_PREMIUM_PAYABLE = "2310";   // RI premium payable (outward)

    private final JournalEntryService journalEntryService;
    private final PostingRuleService postingRuleService;
    private final Clock clock;

    // ── 1. Policy approved → Dr Premium receivable, Cr LRC BEL ───────────────
    @EventListener
    public void onPolicyApproved(PolicyApprovedEvent event) {
        if (zeroOrNull(event.netPremium())) {
            log.debug("Skipping JE for PolicyApproved {} — net premium is zero", event.policyNumber());
            return;
        }
        postTwoLine(
            MODULE_POLICY,
            EVENT_POLICY_APPROVED,
            event.policyId().toString(),
            event.netPremium(),
            event.policyStartDate(),
            event.currencyCode(),
            event.policyNumber());
    }

    // ── 2. Claim approved → Dr Incurred claims, Cr LIC OCR ───────────────────
    @EventListener
    public void onClaimApproved(ClaimApprovedEvent event) {
        if (zeroOrNull(event.approvedAmount())) {
            log.debug("Skipping JE for ClaimApproved {} — approved amount is zero", event.claimNumber());
            return;
        }
        postTwoLine(
            MODULE_CLAIM,
            EVENT_CLAIM_APPROVED,
            event.claimId().toString(),
            event.approvedAmount(),
            today(),
            event.currencyCode(),
            event.claimNumber(),
            event.policyNumber());
    }

    // ── 3. Claim settled → Dr LIC OCR, Cr Bank current accounts ──────────────
    @EventListener
    public void onClaimSettled(ClaimSettledEvent event) {
        if (zeroOrNull(event.settledAmount())) {
            log.debug("Skipping JE for ClaimSettled {} — settled amount is zero", event.claimNumber());
            return;
        }
        postTwoLine(
            MODULE_CLAIM,
            EVENT_CLAIM_SETTLED,
            event.claimId().toString(),
            event.settledAmount(),
            LocalDate.ofInstant(event.settledAt(), ZoneOffset.UTC),
            event.currencyCode(),
            event.claimNumber());
    }

    // ── 4. Claim expense approved → Dr Other direct expenses, Cr Claims payable
    @EventListener
    public void onClaimExpenseApproved(ClaimExpenseApprovedEvent event) {
        if (zeroOrNull(event.amount())) {
            log.debug("Skipping JE for ClaimExpenseApproved {} — amount is zero", event.expenseReference());
            return;
        }
        postTwoLine(
            MODULE_CLAIM,
            EVENT_CLAIM_EXPENSE_APPROVED,
            event.expenseId().toString(),
            event.amount(),
            today(),
            event.currencyCode(),
            event.expenseReference(),
            event.claimNumber());
    }

    // ── 5. Endorsement approved → sign-dispatched (ADDITIONAL or REFUND) ─────
    @EventListener
    public void onEndorsementApproved(EndorsementApprovedEvent event) {
        int sign = event.premiumAdjustment() == null ? 0 : event.premiumAdjustment().signum();
        if (sign == 0) {
            log.debug("Skipping JE for EndorsementApproved {} — premium adjustment is zero", event.endorsementNumber());
            return;
        }
        String eventType = sign > 0
            ? EVENT_ENDORSEMENT_PREMIUM_ADDITIONAL
            : EVENT_ENDORSEMENT_PREMIUM_REFUND;
        // Refund posts the absolute amount against swapped Dr/Cr (encoded in the
        // posting rule). The amount on the JE is always positive — sign is
        // captured in the choice of rule, not the value.
        postTwoLine(
            MODULE_ENDORSEMENT,
            eventType,
            event.endorsementId().toString(),
            event.premiumAdjustment().abs(),
            today(),
            event.currencyCode(),
            event.endorsementNumber(),
            event.policyNumber());
    }

    // ── 6. FAC premium ceded → compound 3-line posting (hardcoded) ───────────
    //
    // posting_rule (1 Dr + 1 Cr per row, UNIQUE on source_event_type) cannot
    // express three legs, so this listener bypasses the table and builds the
    // request inline. The accounts and signs are explicit here so review can
    // diff the contract directly.
    //
    //   Dr 5210 Outward RI premium expense       = premiumCeded
    //   Cr 4300 RI commission income             = commissionAmount
    //   Cr 2310 RI premium payable (outward)     = netPremiumCeded
    //
    // Invariant: premiumCeded == commissionAmount + netPremiumCeded.
    // {@link JournalEntryService#post} re-checks this at the GL boundary.
    @EventListener
    public void onFacPremiumCeded(FacPremiumCededEvent event) {
        if (zeroOrNull(event.premiumCeded())) {
            log.debug("Skipping JE for FacPremiumCeded {} — premium ceded is zero", event.facReference());
            return;
        }
        String narrative = String.format(
            "Outward FAC %s ceded to %s",
            event.facReference(), event.reinsuranceCompanyName());

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            today(),
            MODULE_REINSURANCE,
            EVENT_FAC_PREMIUM_CEDED,
            event.facCoverId().toString(),
            narrative,
            List.of(
                line(COA_RI_PREMIUM_EXPENSE,    event.premiumCeded(),    BigDecimal.ZERO,         event.currencyCode()),
                line(COA_RI_COMMISSION_INCOME,  BigDecimal.ZERO,         event.commissionAmount(), event.currencyCode()),
                line(COA_RI_PREMIUM_PAYABLE,    BigDecimal.ZERO,         event.netPremiumCeded(),  event.currencyCode())));
        journalEntryService.post(request);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Posts a 2-line balanced journal entry by looking up the
     * {@link PostingRule} for {@code eventType} and binding {@code amount}
     * to the rule's Dr / Cr account codes. Throws
     * {@link PostingRuleNotFoundException} (422) if no active rule exists.
     */
    private void postTwoLine(
        String module,
        String eventType,
        String reference,
        BigDecimal amount,
        LocalDate businessDate,
        String currencyCode,
        Object... narrativeArgs) {

        PostingRule rule = postingRuleService.findByEventType(eventType);
        String narrative = rule.getNarrativeTemplate() != null
            ? String.format(rule.getNarrativeTemplate(), narrativeArgs)
            : null;

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate,
            module,
            eventType,
            reference,
            narrative,
            List.of(
                line(rule.getDebitAccountCode(),  amount,           BigDecimal.ZERO, currencyCode),
                line(rule.getCreditAccountCode(), BigDecimal.ZERO,  amount,          currencyCode)));
        journalEntryService.post(request);
    }

    private static JournalEntryLineRequest line(String code, BigDecimal debit, BigDecimal credit, String currencyCode) {
        return new JournalEntryLineRequest(
            code,
            debit != null ? debit : BigDecimal.ZERO,
            credit != null ? credit : BigDecimal.ZERO,
            currencyCode,
            null, null, null, null, null);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static boolean zeroOrNull(BigDecimal value) {
        return value == null || value.signum() == 0;
    }
}
