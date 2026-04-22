package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PolicyApprovedEventListener {

    private final DebitNoteService debitNoteService;

    public PolicyApprovedEventListener(DebitNoteService debitNoteService) {
        this.debitNoteService = debitNoteService;
    }

    @EventListener
    @Transactional
    public void onPolicyApproved(PolicyApprovedEvent event) {
        debitNoteService.createForPolicy(event);
    }
}
