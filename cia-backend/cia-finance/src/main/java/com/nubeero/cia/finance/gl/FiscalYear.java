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

import java.time.LocalDate;

/**
 * Tenant-configurable fiscal year. The container for {@link FiscalPeriod}
 * child rows (12 MONTH + 4 QUARTER + 2 HALF_YEAR + 1 YEAR per FY, generated
 * at {@code create} time per D2=A; DAY periods are lazy).
 *
 * <p>Slice 1.6 (Module 12 — Period-End Closures). The schema (V31) supports
 * any 12-month window; Slice 1.6 enforces month-aligned boundaries at the
 * application layer ({@code FiscalYearService.create} validates that
 * {@code startDate} is the first day of its month and {@code endDate} is
 * the last day of its month).
 *
 * <p>One {@link FiscalYearStatus#ACTIVE} row per tenant — enforced by the
 * service, not by DB constraint (V31 comment).
 *
 * @see FiscalYearService
 * @see FiscalPeriod
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "fiscal_year")
public class FiscalYear extends BaseEntity {

    /**
     * Display name; defaults to {@code "FY" + startDate.getYear()} when the
     * caller omits it (d9). Must be UNIQUE per tenant (V31 constraint).
     */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FiscalYearStatus status = FiscalYearStatus.PLANNING;
}
