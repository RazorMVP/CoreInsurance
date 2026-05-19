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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fiscal period row. Created by {@code FiscalYearService} when a fiscal year
 * is activated (Slice 1.6, V31 schema). Slice 1.4 (gateway) treats this entity
 * as read-only: {@link FiscalPeriodResolver} looks up the MONTH row containing
 * a journal entry's business date so the {@code journal_entry.period_id} FK
 * can be populated.
 *
 * <p>Lifecycle mutation (soft-close / hard-close / reopen) is intentionally
 * absent from this slice. Those state transitions arrive in Slices 1.6 / 1.7.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "fiscal_period")
public class FiscalPeriod extends BaseEntity {

    @Column(name = "fiscal_year_id", nullable = false)
    private UUID fiscalYearId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private FiscalPeriodType periodType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FiscalPeriodStatus status = FiscalPeriodStatus.OPEN;

    @Column(name = "soft_closed_at")
    private Instant softClosedAt;

    @Column(name = "hard_closed_at")
    private Instant hardClosedAt;
}
