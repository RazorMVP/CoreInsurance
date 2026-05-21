package com.nubeero.cia.finance.gl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for {@link FiscalPeriodResolver}. Verifies the
 * date-range MONTH lookup behaviour (Slice 1.4).
 */
@ExtendWith(MockitoExtension.class)
class FiscalPeriodResolverTest {

    @Mock
    private FiscalPeriodRepository periodRepository;

    @InjectMocks
    private FiscalPeriodResolver resolver;

    @Test
    @DisplayName("resolveMonthIdForBusinessDate returns the id when a covering MONTH exists")
    void resolveHit() {
        LocalDate businessDate = LocalDate.of(2026, 5, 14);
        FiscalPeriod period = new FiscalPeriod();
        UUID expectedId = UUID.randomUUID();
        period.setId(expectedId);
        period.setPeriodType(FiscalPeriodType.MONTH);
        period.setStartDate(LocalDate.of(2026, 5, 1));
        period.setEndDate(LocalDate.of(2026, 5, 31));
        when(periodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                eq(FiscalPeriodType.MONTH), eq(businessDate), eq(businessDate)))
            .thenReturn(Optional.of(period));

        assertThat(resolver.resolveMonthIdForBusinessDate(businessDate)).isEqualTo(expectedId);
    }

    @Test
    @DisplayName("resolveMonthIdForBusinessDate throws FiscalPeriodNotFoundException when no MONTH covers the date")
    void resolveMiss() {
        LocalDate businessDate = LocalDate.of(2025, 12, 1);
        when(periodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                eq(FiscalPeriodType.MONTH), eq(businessDate), eq(businessDate)))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveMonthIdForBusinessDate(businessDate))
            .isInstanceOf(FiscalPeriodNotFoundException.class)
            .hasMessageContaining("2025-12-01")
            .hasMessageContaining("MONTH");
    }

    @Test
    @DisplayName("resolveMonthForBusinessDate returns the entity (not just id) for callers that need full record")
    void resolveEntityHit() {
        LocalDate businessDate = LocalDate.of(2026, 5, 14);
        FiscalPeriod period = new FiscalPeriod();
        period.setPeriodType(FiscalPeriodType.MONTH);
        period.setStartDate(LocalDate.of(2026, 5, 1));
        period.setEndDate(LocalDate.of(2026, 5, 31));
        period.setStatus(FiscalPeriodStatus.OPEN);
        when(periodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                eq(FiscalPeriodType.MONTH), eq(businessDate), eq(businessDate)))
            .thenReturn(Optional.of(period));

        FiscalPeriod resolved = resolver.resolveMonthForBusinessDate(businessDate);
        assertThat(resolved.getStartDate()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(resolved.getStatus()).isEqualTo(FiscalPeriodStatus.OPEN);
    }
}
