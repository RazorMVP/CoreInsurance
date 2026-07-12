package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.RiFacInwardAcceptedEvent;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inward FAC (v1) Task 6 — bridges {@link RiFacInwardAcceptedEvent} (fired by
 * {@code RiFacInwardService} on create/renew/extend) into cia-finance.
 *
 * <p>Unlike the outward FAC pair — {@code SubledgerPostingService} owns its
 * own {@code @EventListener} for the GL leg, and {@code
 * FacPremiumCededEventListener} separately owns the {@code CreditNote} leg —
 * this single listener drives both the receivable {@link DebitNote} and the
 * GL posting for the inward direction. Both calls join the publisher's
 * transaction (REQUIRED propagation): if either fails, the originating
 * accept/renew/extend rolls back too.
 */
@Component
@RequiredArgsConstructor
public class RiFacInwardAcceptedEventListener {

    private final DebitNoteService debitNoteService;
    private final SubledgerPostingService subledgerPostingService;

    @EventListener
    @Transactional
    public void onInwardFacAccepted(RiFacInwardAcceptedEvent event) {
        debitNoteService.createForInwardFac(event);
        subledgerPostingService.replayFacPremiumAccepted(event);
    }
}
