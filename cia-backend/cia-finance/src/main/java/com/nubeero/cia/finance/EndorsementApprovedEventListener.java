package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.EndorsementApprovedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
public class EndorsementApprovedEventListener {

    private final DebitNoteService debitNoteService;
    private final CreditNoteService creditNoteService;

    public EndorsementApprovedEventListener(DebitNoteService debitNoteService,
                                             CreditNoteService creditNoteService) {
        this.debitNoteService = debitNoteService;
        this.creditNoteService = creditNoteService;
    }

    @EventListener
    @Transactional
    public void onEndorsementApproved(EndorsementApprovedEvent event) {
        BigDecimal adjustment = event.premiumAdjustment();
        int cmp = adjustment.compareTo(BigDecimal.ZERO);

        if (cmp > 0) {
            debitNoteService.createForEndorsement(event);
        } else if (cmp < 0) {
            creditNoteService.create(
                    FinanceEntityType.ENDORSEMENT,
                    event.endorsementId(),
                    event.endorsementNumber(),
                    event.customerId(),
                    event.customerName(),
                    "Return premium for endorsement " + event.endorsementNumber()
                            + " on policy " + event.policyNumber(),
                    adjustment.abs(),
                    BigDecimal.ZERO,
                    event.currencyCode()
            );
        }
        // NON_PREMIUM_BEARING (adjustment == 0): no financial document needed
    }
}
