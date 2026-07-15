package com.nubeero.cia.reinsurance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Period-lock contract test for {@link RiFacInward}. Inward FAC is created live
 * with no approval step, so its booking date — the period-lock anchor — is the
 * {@code createdAt} timestamp, NOT the {@code coverFrom}/{@code coverTo}
 * business-effective cover period. Anchoring on {@code coverFrom} would let a
 * backdated cover period be wrongly evaluated against a closed fiscal period
 * (CLAUDE.md §Period-Lock Design).
 */
class RiFacInwardLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate null until createdAt is populated by auditing")
    void getLockDateNullBeforeCreatedAt() {
        assertThat(new RiFacInward().getLockDate()).isNull();
    }

    @Test
    @DisplayName("getLockDate uses createdAt (booking), NOT coverFrom (effective)")
    void getLockDateUsesCreatedAtNotCoverFrom() {
        RiFacInward inward = new RiFacInward();
        inward.setCoverFrom(LocalDate.of(2026, 1, 1));
        inward.setCoverTo(LocalDate.of(2026, 12, 31));
        inward.setCreatedAt(Instant.parse("2026-03-15T10:00:00Z"));

        assertThat(inward.getLockDate())
            .as("booking date wins — cover may be effective Jan 1 but it BOOKS in March")
            .isEqualTo(LocalDate.of(2026, 3, 15))
            .isNotEqualTo(inward.getCoverFrom());
    }

    @Test
    @DisplayName("isReversal is false — inward FAC has no reversal model")
    void isReversalDefaultsFalse() {
        assertThat(new RiFacInward().isReversal()).isFalse();
    }
}
