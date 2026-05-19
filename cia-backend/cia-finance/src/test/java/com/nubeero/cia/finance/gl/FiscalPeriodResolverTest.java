package com.nubeero.cia.finance.gl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests for {@link FiscalPeriodResolver}. Verifies the
 * date-range MONTH lookup behaviour (Slice 1.4) and the lazy DAY-period
 * generation introduced in Slice 1.6 (d10).
 */
@ExtendWith(MockitoExtension.class)
class FiscalPeriodResolverTest {

    @Mock
    private FiscalPeriodRepository periodRepository;

    @Mock
    private FiscalYearRepository fiscalYearRepository;

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

    // ── Slice 1.6 d10: lazy DAY-period generation ────────────────────────────

    @Test
    @DisplayName("resolveDayForBusinessDate returns an existing DAY period without creating a new one")
    void resolveDayHit() {
        LocalDate businessDate = LocalDate.of(2026, 5, 14);
        FiscalPeriod existing = new FiscalPeriod();
        existing.setId(UUID.randomUUID());
        existing.setPeriodType(FiscalPeriodType.DAY);
        existing.setStartDate(businessDate);
        existing.setEndDate(businessDate);
        when(periodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                eq(FiscalPeriodType.DAY), eq(businessDate), eq(businessDate)))
            .thenReturn(Optional.of(existing));

        FiscalPeriod resolved = resolver.resolveDayForBusinessDate(businessDate);
        assertThat(resolved.getId()).isEqualTo(existing.getId());
        // No save when row already exists.
        verify(periodRepository, times(0)).save(any(FiscalPeriod.class));
    }

    @Test
    @DisplayName("resolveDayForBusinessDate lazily creates a DAY period anchored to the enclosing FY")
    void resolveDayLazyCreate() {
        LocalDate businessDate = LocalDate.of(2026, 5, 14);
        when(periodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                eq(FiscalPeriodType.DAY), eq(businessDate), eq(businessDate)))
            .thenReturn(Optional.empty());

        FiscalYear enclosingFy = new FiscalYear();
        enclosingFy.setId(UUID.randomUUID());
        enclosingFy.setName("FY2026");
        enclosingFy.setStartDate(LocalDate.of(2026, 1, 1));
        enclosingFy.setEndDate(LocalDate.of(2026, 12, 31));
        enclosingFy.setStatus(FiscalYearStatus.ACTIVE);
        when(fiscalYearRepository.findEnclosing(businessDate)).thenReturn(Optional.of(enclosingFy));
        when(periodRepository.save(any(FiscalPeriod.class))).thenAnswer(inv -> {
            FiscalPeriod p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        FiscalPeriod resolved = resolver.resolveDayForBusinessDate(businessDate);

        ArgumentCaptor<FiscalPeriod> captor = ArgumentCaptor.forClass(FiscalPeriod.class);
        verify(periodRepository, times(1)).save(captor.capture());
        FiscalPeriod saved = captor.getValue();
        assertThat(saved.getPeriodType()).isEqualTo(FiscalPeriodType.DAY);
        assertThat(saved.getStartDate()).isEqualTo(businessDate);
        assertThat(saved.getEndDate()).isEqualTo(businessDate);
        assertThat(saved.getFiscalYearId()).isEqualTo(enclosingFy.getId());
        assertThat(saved.getStatus()).isEqualTo(FiscalPeriodStatus.OPEN);
        assertThat(resolved.getId()).isNotNull();
    }

    @Test
    @DisplayName("resolveDayForBusinessDate throws FiscalPeriodNotFoundException when no FY encloses the date")
    void resolveDayNoEnclosingFy() {
        LocalDate businessDate = LocalDate.of(2099, 6, 1);
        when(periodRepository
            .findFirstByPeriodTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndDeletedAtIsNull(
                eq(FiscalPeriodType.DAY), eq(businessDate), eq(businessDate)))
            .thenReturn(Optional.empty());
        when(fiscalYearRepository.findEnclosing(businessDate)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveDayForBusinessDate(businessDate))
            .isInstanceOf(FiscalPeriodNotFoundException.class)
            .hasMessageContaining("2099-06-01")
            .hasMessageContaining("DAY");

        verify(periodRepository, times(0)).save(any(FiscalPeriod.class));
    }
}
