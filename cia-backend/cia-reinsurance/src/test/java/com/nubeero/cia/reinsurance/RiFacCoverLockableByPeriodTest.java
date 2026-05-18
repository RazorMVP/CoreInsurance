package com.nubeero.cia.reinsurance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1.7b contract test for {@link RiFacCover}. Same booking-vs-effective
 * separation as Endorsement — lockDate is the approval timestamp, not any
 * coverFrom/coverTo dates which represent the cover period.
 */
class RiFacCoverLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate null until approvedAt is set")
    void getLockDateNullForUnapproved() {
        assertThat(new RiFacCover().getLockDate()).isNull();
    }

    @Test
    @DisplayName("getLockDate uses approvedAt (booking), NOT coverFrom (effective)")
    void getLockDateUsesApprovedAtNotCoverFrom() {
        RiFacCover cover = new RiFacCover();
        cover.setCoverFrom(LocalDate.of(2026, 1, 1));
        cover.setApprovedAt(Instant.parse("2026-03-15T10:00:00Z"));

        assertThat(cover.getLockDate())
            .as("booking date wins — cover may be effective Jan 1 but it BOOKS in March")
            .isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    @DisplayName("isReversal tracks cancelledAt")
    void isReversalTracksCancelledAt() {
        RiFacCover cover = new RiFacCover();
        cover.setApprovedAt(Instant.parse("2026-05-15T10:00:00Z"));
        assertThat(cover.isReversal()).isFalse();

        cover.setCancelledAt(Instant.now());
        assertThat(cover.isReversal()).isTrue();
    }
}
