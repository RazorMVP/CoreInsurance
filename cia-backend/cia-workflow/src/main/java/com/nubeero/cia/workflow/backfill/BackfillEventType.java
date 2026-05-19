package com.nubeero.cia.workflow.backfill;

/**
 * The six {@code SubledgerPostingService} event types that the retroactive
 * journal-entry backfill can replay (Slice 1.8a). One-to-one with the
 * {@code source_event_type} values seeded in V33__seed_posting_rules.sql,
 * with the two endorsement variants collapsed into one {@code ENDORSEMENT}
 * driver — sign of {@code premiumAdjustment} picks between the
 * {@code ENDORSEMENT_PREMIUM_ADDITIONAL} and
 * {@code ENDORSEMENT_PREMIUM_REFUND} posting rules inside the replay.
 */
public enum BackfillEventType {
    POLICY_APPROVED,
    CLAIM_APPROVED,
    CLAIM_SETTLED,
    CLAIM_EXPENSE_APPROVED,
    ENDORSEMENT_APPROVED,
    FAC_PREMIUM_CEDED
}
