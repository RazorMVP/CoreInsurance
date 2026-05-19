package com.nubeero.cia.finance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1.7a contract test for {@link Payment}. Same shape as
 * {@code ReceiptLockableByPeriodTest} — Payment's lock date is its
 * {@code paymentDate} (the date money was paid out, which is the booking
 * date for GL purposes), and the reversal carve-out fires once
 * {@code reversedAt} is set.
 */
class PaymentLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate returns paymentDate")
    void getLockDateReturnsPaymentDate() {
        Payment payment = new Payment();
        LocalDate paymentDate = LocalDate.of(2026, 5, 15);
        payment.setPaymentDate(paymentDate);

        assertThat(payment.getLockDate()).isEqualTo(paymentDate);
    }

    @Test
    @DisplayName("isReversal tracks reversedAt")
    void isReversalTracksReversedAt() {
        Payment payment = new Payment();
        payment.setPaymentDate(LocalDate.of(2026, 5, 15));
        assertThat(payment.isReversal()).isFalse();

        payment.setReversedAt(Instant.now());
        assertThat(payment.isReversal()).isTrue();
    }
}
