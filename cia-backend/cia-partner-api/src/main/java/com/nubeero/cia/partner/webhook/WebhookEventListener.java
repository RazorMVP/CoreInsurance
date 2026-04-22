package com.nubeero.cia.partner.webhook;

import com.nubeero.cia.common.event.*;
import com.nubeero.cia.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges Spring ApplicationEvents to webhook fanout.
 * Listeners run synchronously on the request thread so TenantContext is still set.
 * Actual HTTP delivery is async inside Temporal workflows.
 */
@Component
@RequiredArgsConstructor
public class WebhookEventListener {

    private final WebhookService webhookService;

    @EventListener
    public void onPolicyApproved(PolicyApprovedEvent event) {
        webhookService.publish(TenantContext.getTenantId(), WebhookEvent.POLICY_BOUND, Map.of(
                "policyId", event.policyId(),
                "policyNumber", event.policyNumber(),
                "customerId", event.customerId(),
                "customerName", event.customerName(),
                "productName", event.productName(),
                "netPremium", event.netPremium(),
                "currencyCode", event.currencyCode()
        ));
    }

    @EventListener
    public void onEndorsementApproved(EndorsementApprovedEvent event) {
        webhookService.publish(TenantContext.getTenantId(), WebhookEvent.POLICY_ENDORSED, Map.of(
                "endorsementId", event.endorsementId(),
                "endorsementNumber", event.endorsementNumber(),
                "policyId", event.policyId(),
                "policyNumber", event.policyNumber(),
                "customerId", event.customerId(),
                "premiumAdjustment", event.premiumAdjustment(),
                "currencyCode", event.currencyCode()
        ));
    }

    @EventListener
    public void onClaimApproved(ClaimApprovedEvent event) {
        webhookService.publish(TenantContext.getTenantId(), WebhookEvent.CLAIM_APPROVED, Map.of(
                "claimId", event.claimId(),
                "claimNumber", event.claimNumber(),
                "policyId", event.policyId(),
                "policyNumber", event.policyNumber(),
                "customerId", event.customerId(),
                "approvedAmount", event.approvedAmount(),
                "currencyCode", event.currencyCode()
        ));
    }

    @EventListener
    public void onClaimSettled(ClaimSettledEvent event) {
        webhookService.publish(TenantContext.getTenantId(), WebhookEvent.CLAIM_SETTLED, Map.of(
                "claimId", event.claimId(),
                "claimNumber", event.claimNumber(),
                "policyId", event.policyId(),
                "policyNumber", event.policyNumber(),
                "customerId", event.customerId(),
                "settledAt", event.settledAt().toString()
        ));
    }
}
