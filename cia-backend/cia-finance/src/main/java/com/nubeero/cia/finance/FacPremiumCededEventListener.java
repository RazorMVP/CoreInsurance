package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.FacPremiumCededEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class FacPremiumCededEventListener {

    private final CreditNoteService creditNoteService;

    @EventListener
    @Transactional
    public void onFacPremiumCeded(FacPremiumCededEvent event) {
        creditNoteService.create(
                FinanceEntityType.REINSURANCE,
                event.facCoverId(),
                event.facReference(),
                event.reinsuranceCompanyId(),
                event.reinsuranceCompanyName(),
                "Outward FAC premium for policy " + event.policyNumber()
                        + " (" + event.facReference() + ")",
                event.netPremiumCeded(),
                java.math.BigDecimal.ZERO,
                event.currencyCode()
        );
    }
}
