package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Slice 84c — generates the payables credit note for broker commission when
 * a policy with a V51 commission snapshot is approved. Sibling to
 * {@link FacPremiumCededEventListener}: same event-driven CN creation, same
 * payables-side semantics, different beneficiary type.
 *
 * <p>Skip conditions (all silently — never fails the policy approval flow):
 * <ul>
 *   <li>No commission snapshot — {@code commissionSourceType} or
 *       {@code commissionAmount} is null. Matches V51's paired-CHECK semantics.</li>
 *   <li>Zero commission amount — there's no payable to record.</li>
 *   <li>Non-broker source — agent and relationship-manager attribution at the
 *       policy level is Open Question #11 in PRD v2.7. When those sources
 *       become resolvable, a follow-up slice extends this listener.</li>
 *   <li>No broker on the event — defensive guard. A BROKER source without a
 *       brokerId is a data-quality issue; logged and skipped.</li>
 * </ul>
 *
 * <p>The credit note created here is the operational artifact behind the
 * GL entry posted by {@link com.nubeero.cia.finance.gl.SubledgerPostingService#replayPolicyApproved}.
 * Both fire on the same event; the JE is the GL truth (NAICOM / IFRS) and the
 * CN is what Module 8 Finance pays out against. Posted by a separate listener
 * so the GL path remains independent of payables document state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyCommissionCreditNoteListener {

    private final CreditNoteService creditNoteService;

    @EventListener
    @Transactional
    public void onPolicyApproved(PolicyApprovedEvent event) {
        if (event.commissionSourceType() == null) return;
        if (event.commissionAmount() == null) return;
        if (event.commissionAmount().signum() <= 0) return;

        // Resolve the beneficiary from the source. RELATIONSHIP_MANAGER stays
        // out — RM commission is a staff payroll incentive, not a commission
        // CN. When that path lands it'll be a separate listener/document.
        UUID beneficiaryId;
        String beneficiaryName;
        String label;
        switch (event.commissionSourceType()) {
            case "BROKER" -> {
                if (event.brokerId() == null) {
                    log.warn("Skipping broker commission CN for policy {} — BROKER snapshot without brokerId",
                            event.policyNumber());
                    return;
                }
                beneficiaryId = event.brokerId();
                beneficiaryName = event.brokerName();
                label = "Broker";
            }
            case "AGENT" -> {
                if (event.agentId() == null) {
                    log.warn("Skipping agent commission CN for policy {} — AGENT snapshot without agentId",
                            event.policyNumber());
                    return;
                }
                beneficiaryId = event.agentId();
                beneficiaryName = event.agentName();
                label = "Agent";
            }
            default -> {
                log.debug("Skipping commission CN for policy {} — source {} not supported",
                        event.policyNumber(), event.commissionSourceType());
                return;
            }
        }

        creditNoteService.create(
                FinanceEntityType.POLICY,
                event.policyId(),
                event.policyNumber(),
                beneficiaryId,
                beneficiaryName,
                label + " commission for policy " + event.policyNumber(),
                event.commissionAmount(),
                BigDecimal.ZERO,
                event.currencyCode()
        );
    }
}
