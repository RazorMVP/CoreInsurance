package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.ClaimApprovedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ClaimApprovedEventListener {

    private final CreditNoteService creditNoteService;

    public ClaimApprovedEventListener(CreditNoteService creditNoteService) {
        this.creditNoteService = creditNoteService;
    }

    @EventListener
    @Transactional
    public void onClaimApproved(ClaimApprovedEvent event) {
        creditNoteService.create(
                FinanceEntityType.CLAIM,
                event.claimId(),
                event.claimNumber(),
                event.customerId(),
                event.customerName(),
                "DV for claim " + event.claimNumber() + " on policy " + event.policyNumber(),
                event.approvedAmount(),
                java.math.BigDecimal.ZERO,
                event.currencyCode()
        );
    }
}
