package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.event.PolicyApprovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component("reinsurancePolicyApprovedEventListener")
@RequiredArgsConstructor
public class PolicyApprovedEventListener {

    private final AllocationService allocationService;

    @EventListener
    public void onPolicyApproved(PolicyApprovedEvent event) {
        try {
            allocationService.autoAllocate(event);
        } catch (Exception ex) {
            log.error("RI auto-allocation failed for policy {}: {}", event.policyNumber(), ex.getMessage(), ex);
        }
    }
}
