package com.nubeero.cia.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1.7a contract test for {@link ClaimExpense}. Lock date is the
 * approval timestamp truncated to LocalDate (UTC); null while in draft so
 * {@code PeriodLockService.checkWrite} ALLOWs the save.
 */
class ClaimExpenseLockableByPeriodTest {

    @Test
    @DisplayName("getLockDate is null until approvedAt is set — draft expenses pass the lock check")
    void getLockDateIsNullForDraft() {
        ClaimExpense expense = new ClaimExpense();
        assertThat(expense.getLockDate())
            .as("draft expenses have no booking date and must not be rejected by the interceptor")
            .isNull();
    }

    @Test
    @DisplayName("getLockDate returns approvedAt converted to LocalDate (UTC)")
    void getLockDateReturnsApprovedDate() {
        ClaimExpense expense = new ClaimExpense();
        Instant approvedAt = Instant.parse("2026-05-15T10:00:00Z");
        expense.setApprovedAt(approvedAt);

        assertThat(expense.getLockDate()).isEqualTo(LocalDate.of(2026, 5, 15));
        assertThat(expense.getLockDate())
            .as("UTC conversion is the documented contract — using system default would be host-dependent")
            .isEqualTo(approvedAt.atOffset(ZoneOffset.UTC).toLocalDate());
    }

    @Test
    @DisplayName("isReversal tracks cancelledAt — cancellation is the reversal carve-out for ClaimExpense")
    void isReversalTracksCancelledAt() {
        ClaimExpense expense = new ClaimExpense();
        expense.setApprovedAt(Instant.parse("2026-05-15T10:00:00Z"));
        assertThat(expense.isReversal()).isFalse();

        expense.setCancelledAt(Instant.now());
        assertThat(expense.isReversal()).isTrue();
    }
}
