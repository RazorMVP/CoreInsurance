package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.ClaimExpenseApprovedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ClaimExpenseApprovedEventListener {

    private final CreditNoteService creditNoteService;

    public ClaimExpenseApprovedEventListener(CreditNoteService creditNoteService) {
        this.creditNoteService = creditNoteService;
    }

    @EventListener
    @Transactional
    public void onClaimExpenseApproved(ClaimExpenseApprovedEvent event) {
        creditNoteService.create(
                FinanceEntityType.CLAIM_EXPENSE,
                event.claimId(),
                event.claimNumber(),
                event.vendorId(),
                event.vendorName(),
                event.expenseType() + " for claim " + event.claimNumber(),
                event.amount(),
                java.math.BigDecimal.ZERO,
                event.currencyCode()
        );
    }
}
