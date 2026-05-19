package com.nubeero.cia.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1.7a contract test — verifies {@link Receipt} opts into
 * {@code LockableByPeriod} with the documented {@code getLockDate() =
 * paymentDate} and {@code isReversal() = reversedAt != null} contract.
 * Pure entity-level test: no Spring context, no DB. The interceptor's
 * runtime behaviour against a real DB is covered separately by
 * ReconciliationGateIT (which plays JEs through the live interceptor).
 */
class ReceiptLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate returns paymentDate (booking date, not effective date)")
    void getLockDateReturnsPaymentDate() {
        Receipt receipt = new Receipt();
        LocalDate paymentDate = LocalDate.of(2026, 5, 15);
        receipt.setPaymentDate(paymentDate);

        assertThat(receipt.getLockDate()).isEqualTo(paymentDate);
    }

    @Test
    @DisplayName("isReversal is false for a fresh receipt; true once reversedAt is set")
    void isReversalTracksReversedAt() {
        Receipt receipt = new Receipt();
        receipt.setPaymentDate(LocalDate.of(2026, 5, 15));

        assertThat(receipt.isReversal())
            .as("a fresh, non-reversed receipt must NOT carry the reversal carve-out — otherwise "
                + "backdated CREATE operations would slip past the lock check")
            .isFalse();

        receipt.setReversedAt(Instant.now());
        assertThat(receipt.isReversal())
            .as("once reversedAt is set the row is in a reversal state and the carve-out applies — "
                + "this is what permits reversing a receipt after its original period closes")
            .isTrue();
    }
}
