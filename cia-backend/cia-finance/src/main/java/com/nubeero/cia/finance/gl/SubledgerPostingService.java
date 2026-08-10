package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import com.nubeero.cia.common.event.ClaimSettledEvent;
import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import com.nubeero.cia.common.event.FacPremiumCededEvent;
import com.nubeero.cia.common.event.PolicyApprovedEvent;
import com.nubeero.cia.common.event.RiFacInwardAcceptedEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    // Inward FAC (v1) Task 6 — mirrors EVENT_FAC_PREMIUM_CEDED for the inward
    // (accepted-from-ceding-company) direction.
    static final String EVENT_FAC_PREMIUM_ACCEPTED = "FAC_PREMIUM_ACCEPTED";
    // Slice 84c — Broker commission JE chained from POLICY_APPROVED. Agent /
    // RM equivalents will be added when Open Q#11 unblocks per-policy
    // attribution for those sources.
    static final String EVENT_POLICY_COMMISSION_BROKER = "POLICY_COMMISSION_BROKER";
    static final String SOURCE_BROKER = "BROKER";
    // Slice 84d — AGENT branch mirrors BROKER, distinct rule key + Cr account
    // (V54 → 2330 Commission payable - Agents).
    static final String EVENT_POLICY_COMMISSION_AGENT = "POLICY_COMMISSION_AGENT";
    static final String SOURCE_AGENT = "AGENT";
    // B2 Task 3.2 — RM branch mirrors BROKER/AGENT, distinct rule key + Cr account
    // (V63 → Dr 5130 / Cr 2520). Narrative has a single %s (policy number only) —
    // RM is identified by policies.relationship_manager_id + the per-RM report,
    // not by a payee name on the JE narrative.
    static final String EVENT_POLICY_COMMISSION_RM = "POLICY_COMMISSION_RM";
    static final String SOURCE_RM = "RELATIONSHIP_MANAGER";

    // ── Hardcoded COA codes for the compound FAC 3-line posting ──────────────
    private static final String COA_RI_PREMIUM_EXPENSE = "5210";   // Outward reinsurance premium
    private static final String COA_RI_COMMISSION_INCOME = "4300"; // Reinsurance income (ceded)
    private static final String COA_RI_PREMIUM_PAYABLE = "2310";   // RI premium payable (outward)

    // ── Hardcoded COA codes for the compound inward FAC 3-line posting ───────
    // (V75 — 4330/5240 net new; 1330 pre-existed from V32 R1=A scope decision.)
    private static final String COA_INWARD_PREMIUM_RECEIVABLE = "1330"; // Premium receivable - Coinsurer (inward)
    private static final String COA_INWARD_COMMISSION_EXPENSE  = "5240"; // Inward reinsurance commission expense
    // FAC / IFRS-17 PAA workstream Task 3 — the credit leg moved from 4330
    // (immediate income) to 2210 (LRC liability, V32 seed, ifrs17_role=LRC_BEL):
    // accept now SETS UP the liability at the full gross premium instead of
    // booking income immediately. 4330 (Inward reinsurance premium income,
    // V75 seed) is credited only by LrcEngine's periodic release from here on
    // — see LrcEngine.COA_INWARD_PREMIUM_INCOME.
    private static final String COA_INWARD_LRC = "2210"; // Inward reinsurance LRC (was COA_INWARD_PREMIUM_INCOME = "4330")

    private final JournalEntryService journalEntryService;
    private final PostingRuleService postingRuleService;
    private final PolicyClassResolver policyClassResolver;
    private final Clock clock;

    // ── Event listeners (delegate to public replay methods) ───────────────────
    //
    // Slice 1.8a extracted the bodies of the listener methods into the public
    // `replay*` methods below so the retroactive backfill workflow can invoke
    // the same code path as the live event flow. The listeners stay public —
    // Spring's @EventListener mechanism requires it — but their job is
    // reduced to forwarding the event. Every JE this service emits, whether
    // produced live or via backfill, traverses identical logic and writes
    // identical (sourceModule, sourceEventType, sourceReference) triples.

    @EventListener
    public void onPolicyApproved(PolicyApprovedEvent event) { replayPolicyApproved(event); }

    @EventListener
    public void onClaimApproved(ClaimApprovedEvent event) { replayClaimApproved(event); }

    @EventListener
    public void onClaimSettled(ClaimSettledEvent event) { replayClaimSettled(event); }

    @EventListener
    public void onClaimExpenseApproved(ClaimExpenseApprovedEvent event) { replayClaimExpenseApproved(event); }

    @EventListener
    public void onEndorsementApproved(EndorsementApprovedEvent event) { replayEndorsementApproved(event); }

    @EventListener
    public void onFacPremiumCeded(FacPremiumCededEvent event) { replayFacPremiumCeded(event); }

    // ── Public replay methods — invoked by event listeners AND backfill ──────

    /** 1. Policy approved → Dr Premium receivable, Cr LRC BEL. */
    public void replayPolicyApproved(PolicyApprovedEvent event) {
        if (zeroOrNull(event.netPremium())) {
            log.debug("Skipping JE for PolicyApproved {} — net premium is zero", event.policyNumber());
            return;
        }
        // PolicyApprovedEvent is the only event that already carries
        // classOfBusinessId on the payload — no resolver lookup needed.
        postTwoLine(
            MODULE_POLICY,
            EVENT_POLICY_APPROVED,
            event.policyId().toString(),
            event.netPremium(),
            event.approvalDate(),
            event.currencyCode(),
            event.classOfBusinessId(),
            event.policyNumber());

        // Slice 84c — chain commission JE when the V51 snapshot is populated.
        // Distinct idempotency triple from the premium JE: same policyId, same
        // module, but different event type (POLICY_COMMISSION_*), so the
        // UNIQUE constraint on (source_module, source_event_type, source_reference)
        // never collides with line 1's POLICY_APPROVED row.
        if (!zeroOrNull(event.commissionAmount())) {
            if (SOURCE_BROKER.equals(event.commissionSourceType())) {
                postTwoLine(
                    MODULE_POLICY,
                    EVENT_POLICY_COMMISSION_BROKER,
                    event.policyId().toString(),
                    event.commissionAmount(),
                    event.approvalDate(),
                    event.currencyCode(),
                    event.classOfBusinessId(),
                    event.policyNumber(),
                    event.brokerName());
            } else if (SOURCE_AGENT.equals(event.commissionSourceType())) {
                // Slice 84d — AGENT branch routes to V54's POLICY_COMMISSION_AGENT
                // rule (Cr 2330 Commission payable - Agents). Narrative uses
                // agentName, which V53 / Slice 84d puts on the event.
                postTwoLine(
                    MODULE_POLICY,
                    EVENT_POLICY_COMMISSION_AGENT,
                    event.policyId().toString(),
                    event.commissionAmount(),
                    event.approvalDate(),
                    event.currencyCode(),
                    event.classOfBusinessId(),
                    event.policyNumber(),
                    event.agentName());
            } else if (SOURCE_RM.equals(event.commissionSourceType())) {
                // B2 Task 3.2 — RM commission: Dr 5130 / Cr 2520 (V63). No payee
                // name in the narrative (RM identified by
                // policies.relationship_manager_id + the per-RM report) → the
                // narrative template has a single %s (policy number), so we pass
                // exactly the same arg set as broker/agent minus the trailing name.
                postTwoLine(
                    MODULE_POLICY,
                    EVENT_POLICY_COMMISSION_RM,
                    event.policyId().toString(),
                    event.commissionAmount(),
                    event.approvalDate(),
                    event.currencyCode(),
                    event.classOfBusinessId(),
                    event.policyNumber());
            }
        }
    }

    /** 2. Claim approved → Dr Incurred claims, Cr LIC OCR. Live path: uses {@code today()}. */
    public void replayClaimApproved(ClaimApprovedEvent event) {
        replayClaimApproved(event, today());
    }

    /**
     * 2b. Backfill overload — caller supplies the historical {@code
     * businessDate} (typically {@code claims.approved_at::date}) so the
     * replayed JE lands in the period the approval actually occurred in
     * rather than today's period.
     */
    public void replayClaimApproved(ClaimApprovedEvent event, LocalDate businessDate) {
        if (zeroOrNull(event.approvedAmount())) {
            log.debug("Skipping JE for ClaimApproved {} — approved amount is zero", event.claimNumber());
            return;
        }
        postTwoLine(
            MODULE_CLAIM,
            EVENT_CLAIM_APPROVED,
            event.claimId().toString(),
            event.approvedAmount(),
            businessDate,
            event.currencyCode(),
            policyClassResolver.findClassByClaimId(event.claimId()),
            event.claimNumber(),
            event.policyNumber());
    }

    /** 3. Claim settled → Dr LIC OCR, Cr Bank current accounts. */
    public void replayClaimSettled(ClaimSettledEvent event) {
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
            policyClassResolver.findClassByClaimId(event.claimId()),
            event.claimNumber());
    }

    /** 4. Claim expense approved → Dr Other direct expenses, Cr Claims payable. Live path: uses {@code today()}. */
    public void replayClaimExpenseApproved(ClaimExpenseApprovedEvent event) {
        replayClaimExpenseApproved(event, today());
    }

    /**
     * 4b. Backfill overload — caller supplies historical {@code businessDate}
     * (typically {@code claim_expenses.approved_at::date}).
     */
    public void replayClaimExpenseApproved(ClaimExpenseApprovedEvent event, LocalDate businessDate) {
        if (zeroOrNull(event.amount())) {
            log.debug("Skipping JE for ClaimExpenseApproved {} — amount is zero", event.expenseReference());
            return;
        }
        postTwoLine(
            MODULE_CLAIM,
            EVENT_CLAIM_EXPENSE_APPROVED,
            event.expenseId().toString(),
            event.amount(),
            businessDate,
            event.currencyCode(),
            policyClassResolver.findClassByClaimId(event.claimId()),
            event.expenseReference(),
            event.claimNumber());
    }

    /**
     * 5. Endorsement approved → sign-dispatched (ADDITIONAL or REFUND). Live
     * path: uses {@code today()}.
     *
     * <p>Refund posts the absolute amount against swapped Dr/Cr (encoded in
     * the posting rule). The amount on the JE is always positive — sign is
     * captured in the choice of rule, not the value.
     */
    public void replayEndorsementApproved(EndorsementApprovedEvent event) {
        replayEndorsementApproved(event, today());
    }

    /**
     * 5b. Backfill overload — caller supplies historical {@code businessDate}
     * (typically {@code endorsements.approved_at::date}).
     */
    public void replayEndorsementApproved(EndorsementApprovedEvent event, LocalDate businessDate) {
        int sign = event.premiumAdjustment() == null ? 0 : event.premiumAdjustment().signum();
        if (sign == 0) {
            log.debug("Skipping JE for EndorsementApproved {} — premium adjustment is zero", event.endorsementNumber());
            return;
        }
        String eventType = sign > 0
            ? EVENT_ENDORSEMENT_PREMIUM_ADDITIONAL
            : EVENT_ENDORSEMENT_PREMIUM_REFUND;
        postTwoLine(
            MODULE_ENDORSEMENT,
            eventType,
            event.endorsementId().toString(),
            event.premiumAdjustment().abs(),
            businessDate,
            event.currencyCode(),
            policyClassResolver.findClassByPolicyId(event.policyId()),
            event.endorsementNumber(),
            event.policyNumber());
    }

    /**
     * 6. FAC premium ceded → compound 3-line posting (hardcoded).
     *
     * <p>{@code posting_rule} (1 Dr + 1 Cr per row, UNIQUE on
     * {@code source_event_type}) cannot express three legs, so this method
     * bypasses the table and builds the request inline. The accounts and
     * signs are explicit so review can diff the contract directly.
     *
     * <pre>
     *   Dr 5210 Outward RI premium expense       = premiumCeded
     *   Cr 4300 RI commission income             = commissionAmount
     *   Cr 2310 RI premium payable (outward)     = netPremiumCeded
     * </pre>
     *
     * <p>Invariant: {@code premiumCeded == commissionAmount + netPremiumCeded}.
     * {@link JournalEntryService#post} re-checks this at the GL boundary.
     */
    public void replayFacPremiumCeded(FacPremiumCededEvent event) {
        replayFacPremiumCeded(event, today());
    }

    /**
     * 6b. Backfill overload — caller supplies historical {@code businessDate}
     * (typically {@code ri_fac_covers.approved_at::date}).
     */
    public void replayFacPremiumCeded(FacPremiumCededEvent event, LocalDate businessDate) {
        if (zeroOrNull(event.premiumCeded())) {
            log.debug("Skipping JE for FacPremiumCeded {} — premium ceded is zero", event.facReference());
            return;
        }
        String narrative = String.format(
            "Outward FAC %s ceded to %s",
            event.facReference(), event.reinsuranceCompanyName());

        UUID classOfBusinessId = policyClassResolver.findClassByPolicyId(event.policyId());

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate,
            MODULE_REINSURANCE,
            EVENT_FAC_PREMIUM_CEDED,
            event.facCoverId().toString(),
            narrative,
            List.of(
                line(COA_RI_PREMIUM_EXPENSE,    event.premiumCeded(),    BigDecimal.ZERO,         event.currencyCode(), classOfBusinessId),
                line(COA_RI_COMMISSION_INCOME,  BigDecimal.ZERO,         event.commissionAmount(), event.currencyCode(), classOfBusinessId),
                line(COA_RI_PREMIUM_PAYABLE,    BigDecimal.ZERO,         event.netPremiumCeded(),  event.currencyCode(), classOfBusinessId)));
        journalEntryService.post(request);
    }

    /**
     * 7. Inward FAC accepted → compound 3-line posting (hardcoded, mirrors
     * {@link #replayFacPremiumCeded}).
     *
     * <pre>
     *   Dr 1330 Premium receivable - Coinsurer (inward) = netPremium
     *   Dr 5240 Inward reinsurance commission expense    = commissionAmount
     *   Cr 2210 Inward reinsurance LRC                   = grossPremium
     * </pre>
     *
     * <p>Invariant: {@code grossPremium == commissionAmount + netPremium}.
     * {@link JournalEntryService#post} re-checks this at the GL boundary.
     * <strong>IFRS-17 PAA LRC posting (FAC / IFRS-17 PAA workstream Task 3):</strong>
     * accept sets up the LRC liability at the full gross premium instead of
     * booking income immediately (the pre-Task-3 shape credited 4330 here).
     * {@link com.nubeero.cia.finance.paa.LrcEngine} releases the earned
     * portion to 4330 (Inward reinsurance premium income) straight-line over
     * the FAC's cover period each fiscal period-close.
     *
     * <p><strong>Idempotency reference:</strong> only {@code create} followed
     * by a same-day {@code extend}, or two same-day {@code extend}s, publish
     * {@link RiFacInwardAcceptedEvent} for the SAME {@code facInwardId} —
     * {@code extend} mutates and re-publishes against the existing row's id,
     * while {@code renew} on {@code RiFacInwardService} always persists a
     * brand-new {@code RiFacInward} (a fresh id) and publishes with THAT new
     * id, so a renewal can never collide with its source. The JE gateway's
     * {@code UNIQUE(source_module, source_event_type, source_reference)}
     * constraint would reject a second posting keyed only on {@code
     * facInwardId.toString()}, so the reference here is {@code
     * facInwardId:businessDate} instead. This keeps create + a later extend
     * (different day) from colliding, but two same-facInwardId accepts fired
     * on the SAME calendar day still collide — accepted for v1 (the whole
     * publisher transaction rolls back atomically on the second accept, so
     * no orphan receivable can result); a fuller fix threads a
     * per-transaction sequence into the reference.
     */
    public void replayFacPremiumAccepted(RiFacInwardAcceptedEvent event) {
        replayFacPremiumAccepted(event, today());
    }

    /**
     * 7b. Backfill/explicit-date overload — see {@link
     * #replayFacPremiumAccepted(RiFacInwardAcceptedEvent)} for the posting
     * contract and the idempotency-reference caveat.
     */
    public void replayFacPremiumAccepted(RiFacInwardAcceptedEvent event, LocalDate businessDate) {
        if (zeroOrNull(event.grossPremium())) {
            log.debug("Skipping JE for RiFacInwardAccepted {} — gross premium is zero", event.facInwardReference());
            return;
        }
        String narrative = String.format("Inward FAC %s accepted from %s",
                event.facInwardReference(), event.cedingCompanyName());

        // The Dr 5240 commission line is emitted ONLY when commissionAmount > 0.
        // JournalEntryService rejects any line whose debit and credit are both
        // zero (each line must have exactly one side > 0), so a zero-commission
        // accept — commissionRate is optional and defaults to 0, a normal
        // facultative case and the default FE form state — would otherwise post
        // a Dr 5240 = 0.00 line, be rejected (422), and roll back the whole
        // accept. With zero commission net == gross, so the 2-line
        // Dr 1330 (net) / Cr 2210 (gross) balances on its own. Same guard covers
        // an extend whose pro-rata delta commission rounds to 0.00 while delta
        // gross > 0. (The outward replayFacPremiumCeded shares this latent shape
        // — backlog fac-zero-commission-je-line.)
        List<JournalEntryLineRequest> lines = new ArrayList<>();
        lines.add(line(COA_INWARD_PREMIUM_RECEIVABLE, event.netPremium(), BigDecimal.ZERO, event.currencyCode(), event.classOfBusinessId()));
        if (event.commissionAmount() != null && event.commissionAmount().signum() > 0) {
            lines.add(line(COA_INWARD_COMMISSION_EXPENSE, event.commissionAmount(), BigDecimal.ZERO, event.currencyCode(), event.classOfBusinessId()));
        }
        lines.add(line(COA_INWARD_LRC, BigDecimal.ZERO, event.grossPremium(), event.currencyCode(), event.classOfBusinessId()));

        PostJournalEntryRequest request = new PostJournalEntryRequest(
            businessDate,
            MODULE_REINSURANCE,
            EVENT_FAC_PREMIUM_ACCEPTED,
            event.facInwardId().toString() + ":" + businessDate,
            narrative,
            lines);
        journalEntryService.post(request);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Posts a 2-line balanced journal entry by looking up the
     * {@link PostingRule} for {@code eventType} and binding {@code amount}
     * to the rule's Dr / Cr account codes. Throws
     * {@link PostingRuleNotFoundException} (422) if no active rule exists.
     *
     * <p>Both lines carry {@code classOfBusinessId} (Slice 1.10a) — the
     * class-of-business dimension is per-event, not per-line. Null is
     * acceptable; the V42 column is nullable.
     */
    private void postTwoLine(
        String module,
        String eventType,
        String reference,
        BigDecimal amount,
        LocalDate businessDate,
        String currencyCode,
        UUID classOfBusinessId,
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
                line(rule.getDebitAccountCode(),  amount,           BigDecimal.ZERO, currencyCode, classOfBusinessId),
                line(rule.getCreditAccountCode(), BigDecimal.ZERO,  amount,          currencyCode, classOfBusinessId)));
        journalEntryService.post(request);
    }

    private static JournalEntryLineRequest line(String code, BigDecimal debit, BigDecimal credit,
                                                 String currencyCode, UUID classOfBusinessId) {
        return new JournalEntryLineRequest(
            code,
            debit != null ? debit : BigDecimal.ZERO,
            credit != null ? credit : BigDecimal.ZERO,
            currencyCode,
            null, null, null, null, null,
            classOfBusinessId);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static boolean zeroOrNull(BigDecimal value) {
        return value == null || value.signum() == 0;
    }
}
