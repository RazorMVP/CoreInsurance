package com.nubeero.cia.partner.webhook;

public enum WebhookEvent {
    QUOTE_CREATED,
    QUOTE_EXPIRED,
    POLICY_BOUND,
    POLICY_ENDORSED,
    POLICY_CANCELLED,
    CLAIM_REGISTERED,
    CLAIM_APPROVED,
    CLAIM_SETTLED,
    KYC_COMPLETED,
    RENEWAL_DUE;

    public String eventName() {
        return name().toLowerCase().replace('_', '.');
    }
}
