package com.nubeero.cia.claims;

import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.common.entity.LockableByPeriod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "claim_expenses")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimExpense extends BaseEntity implements LockableByPeriod {

    // ── Slice 1.7a — period-lock opt-in ──────────────────────────────────────
    // Lock date is the approval timestamp truncated to LocalDate (UTC). A
    // draft/non-approved expense has no booking date yet; returning null
    // lets PeriodLockService.checkWrite ALLOW the save (it short-circuits on
    // null lockDate).
    @Override public LocalDate getLockDate() {
        return approvedAt == null ? null : approvedAt.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    // Cancellation is the closest thing to a reversal for ClaimExpense —
    // marking cancelledAt must be permitted after the original period
    // closes so audit-found errors can be corrected without reopening.
    @Override public boolean isReversal() { return cancelledAt != null; }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_type", nullable = false, length = 50)
    private ClaimExpenseType expenseType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ClaimExpenseStatus status = ClaimExpenseStatus.PENDING;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "vendor_name", nullable = false, length = 200)
    private String vendorName;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;
}
