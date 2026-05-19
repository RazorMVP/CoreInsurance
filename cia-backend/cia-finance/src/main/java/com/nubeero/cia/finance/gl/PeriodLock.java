package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Period-lock row — Type-2 SCD over the V31 {@code period_lock} table.
 *
 * <p>A row with {@code releasedAt == null} is the <strong>active</strong>
 * lock for its period; once a HARD lock is released (Slice 1.7
 * {@code PeriodLockService.reopen}), {@code releasedAt + releasedBy +
 * releaseReason} are set together (DB CHECK {@code ck_period_lock_release}
 * enforces all-or-nothing). The row stays — it IS the audit trail. A new
 * SOFT/HARD row can then be inserted for the same period, building up the
 * full open → soft → hard → reopen → soft-again → hard-again transition
 * chain that NAICOM auditors request on sampled periods.
 *
 * <h2>Schema notes (from V31)</h2>
 * <ul>
 *   <li>{@code lock_type} is {@link LockType} (SOFT / HARD) — matches the
 *       V31 CHECK constraint {@code ck_period_lock_type}.</li>
 *   <li>{@code grace_window_until} is per-lock (not a global constant):
 *       computed at soft-close time as {@code locked_at + 5 business days}
 *       by {@link PeriodLockService}, but stored so future per-tenant
 *       overrides (e.g. year-end gets 10 BD) don't require a schema change.</li>
 *   <li>HARD locks set {@code graceWindowUntil = null} — there is no grace
 *       window past a hard close; only an explicit reopen can release them.</li>
 * </ul>
 *
 * @since Module 12, Slice 1.7
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "period_lock")
public class PeriodLock extends BaseEntity {

    @Column(name = "fiscal_period_id", nullable = false)
    private UUID fiscalPeriodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lock_type", nullable = false, length = 10)
    private LockType lockType;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;

    @Column(name = "locked_by", nullable = false, length = 100)
    private String lockedBy;

    /**
     * SOFT locks: timestamp past which writes against this period's
     * {@code business_date} require {@code FINANCE_OVERRIDE_LOCK}. HARD locks:
     * always {@code null} (no grace path past a hard close).
     */
    @Column(name = "grace_window_until")
    private Instant graceWindowUntil;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by", length = 100)
    private String releasedBy;

    @Column(name = "release_reason")
    private String releaseReason;

    public boolean isActive() {
        return releasedAt == null && !isDeleted();
    }
}
