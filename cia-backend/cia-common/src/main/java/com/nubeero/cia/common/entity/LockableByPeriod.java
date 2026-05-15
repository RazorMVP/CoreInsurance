package com.nubeero.cia.common.entity;

import java.time.LocalDate;

/**
 * Marker interface for entities that participate in the period-close lock
 * enforcement layer (Slice 1.7, Module 12). Implementors return the
 * <strong>booking date</strong> that anchors the lock check — not their
 * business-effective date.
 *
 * <h2>Booking date vs effective date</h2>
 * <p>An endorsement effective on 1 Jan that is recorded by Finance on 15 Mar
 * has:
 * <ul>
 *   <li>{@code effectiveDate = 2026-01-01} — drives cover-period maths, IFRS 17
 *       coverage period, and the policy schedule.</li>
 *   <li>{@code bookedDate = 2026-03-15} — drives ledger period assignment and
 *       the period-close lock decision.</li>
 * </ul>
 * The lock interceptor cares about <em>when the row hits the books</em>, which
 * is the booking date. The IFRS 17 measurement engine (Phase 2) reads
 * effective dates separately and never flows through this interceptor.
 *
 * <h2>Reversal carve-out</h2>
 * <p>Reversal rows — entries that exist to offset a previously posted row —
 * are exempt from lock rejection: blocking reversals would make corrections
 * impossible after a period closes. Implementors override {@link #isReversal()}
 * to return {@code true} on their reversal aggregate.
 *
 * <h2>Opt-in by design</h2>
 * <p>Most {@link BaseEntity} subclasses (Customer, Broker, AccessGroup,
 * ChartOfAccount) have no economic posting semantics and intentionally do
 * not implement this interface. The interceptor only inspects entities that
 * declare themselves lockable via this marker.
 *
 * <h2>Why this lives in cia-common</h2>
 * <p>Pure interface, no Hibernate imports. Business modules
 * (cia-policy, cia-claims, cia-endorsement, cia-finance) implement it without
 * creating a dependency on cia-finance (where the interceptor + service live)
 * — avoiding a module cycle.
 *
 * @since Module 12, Slice 1.7
 */
public interface LockableByPeriod {

    /**
     * The booking date this entity anchors the period-lock check against.
     * <p>Non-null contract — return the entity's authoritative ledger-impact
     * date. For {@code JournalEntry} this is {@code businessDate}. For
     * {@code Receipt} this would be {@code receivedDate}, for {@code Payment}
     * {@code paidDate}, for {@code ClaimExpense} {@code incurredDate}.
     */
    LocalDate getLockDate();

    /**
     * Whether this entity is a reversal of a previously posted entity. Reversal
     * rows are exempt from period-lock rejection — the interceptor compares
     * the reversal's {@link #getLockDate()} against today's period, not
     * against the original row's locked period. Default is {@code false};
     * entities with a reversal model (e.g. {@code JournalEntry.reversalOf})
     * override.
     */
    default boolean isReversal() {
        return false;
    }
}
