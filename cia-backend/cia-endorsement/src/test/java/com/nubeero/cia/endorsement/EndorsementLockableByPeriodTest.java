package com.nubeero.cia.endorsement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1.7a contract test for {@link Endorsement}.
 *
 * <h2>Critical invariant</h2>
 * <p>{@code getLockDate()} returns the BOOKING date (approvedAt → LocalDate),
 * NOT the business-effective date. An endorsement effective 2026-01-01 but
 * approved 2026-03-15 books into March, not January. Mixing these two
 * anchors silently breaks the period-lock semantics — see the
 * {@code LockableByPeriod} javadoc.
 */
class EndorsementLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate is null while approvedAt is null — draft endorsements bypass the lock check")
    void getLockDateIsNullForDraft() {
        Endorsement endorsement = new Endorsement();
        endorsement.setEffectiveDate(LocalDate.of(2026, 1, 1));
        assertThat(endorsement.getLockDate())
            .as("draft endorsements have no booking date yet")
            .isNull();
    }

    @Test
    @DisplayName("getLockDate uses approvedAt (booking date), NOT effectiveDate (business date)")
    void getLockDateUsesApprovedAtNotEffectiveDate() {
        Endorsement endorsement = new Endorsement();
        // Effective 2026-01-01 but only approved 2026-03-15. The booking
        // period is March, NOT January. This is the canonical IFRS 17
        // booking-vs-effective distinction.
        endorsement.setEffectiveDate(LocalDate.of(2026, 1, 1));
        endorsement.setApprovedAt(Instant.parse("2026-03-15T10:00:00Z"));

        assertThat(endorsement.getLockDate())
            .as("lock date must follow the booking date, not the effective date")
            .isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(endorsement.getLockDate())
            .as("regression guard: returning effectiveDate would silently mis-route the lock check "
                + "to the wrong fiscal period")
            .isNotEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("isReversal tracks cancelledAt")
    void isReversalTracksCancelledAt() {
        Endorsement endorsement = new Endorsement();
        endorsement.setApprovedAt(Instant.parse("2026-05-15T10:00:00Z"));
        assertThat(endorsement.isReversal()).isFalse();

        endorsement.setCancelledAt(Instant.now());
        assertThat(endorsement.isReversal()).isTrue();
    }
}
