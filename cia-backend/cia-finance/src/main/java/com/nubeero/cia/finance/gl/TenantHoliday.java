package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * NAICOM-aligned public holiday per tenant — Slice 1.7c. Consumed by
 * {@link PeriodLockService#addBusinessDays} so the 5-business-day grace
 * window after a soft close honours holidays in addition to weekends.
 *
 * <p>{@code recurring = true} reserves the date for a future generator that
 * materialises annual holidays (e.g. New Year) into specific years; v1 only
 * matches by exact {@code holidayDate}.
 */
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "tenant_holiday")
public class TenantHoliday extends BaseEntity {

    @Column(name = "holiday_date", nullable = false, unique = true)
    private LocalDate holidayDate;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "recurring", nullable = false)
    private boolean recurring = false;
}
