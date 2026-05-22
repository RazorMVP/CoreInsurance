package com.nubeero.cia.setup.product;

/**
 * The three counterparties that earn commission on a policy, per PRD §2.1.17.
 *
 * <ul>
 *   <li>{@link #AGENT} — NAICOM-licensed individual or corporate agent who represents
 *       the <em>insurer</em>. See {@code cia.setup.org.Agent} (V48).</li>
 *   <li>{@link #BROKER} — NAICOM-licensed broker who represents the <em>insured</em>.
 *       See {@code cia.setup.org.Broker} (V49 added licenseNumber).</li>
 *   <li>{@link #RELATIONSHIP_MANAGER} — Insurer staff member who owns the customer
 *       relationship. See {@code cia.setup.org.RelationshipManager}; wired into
 *       Customer onboarding via V46.</li>
 * </ul>
 *
 * <p>Persisted as the {@code commission_source} column on {@code commission_setups}
 * with a CHECK constraint pinning these three values (V50). The legacy free-text
 * {@code broker_type} column with default {@code 'ALL'} is gone — three sources, no
 * sentinel.
 */
public enum CommissionSourceType {
    AGENT,
    BROKER,
    RELATIONSHIP_MANAGER
}
