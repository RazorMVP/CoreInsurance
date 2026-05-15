package com.nubeero.cia.finance.gl;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.finance.dto.CreateFiscalYearRequest;
import com.nubeero.cia.finance.dto.FiscalPeriodResponse;
import com.nubeero.cia.finance.dto.FiscalYearResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit tests for {@link FiscalYearService}. Covers:
 *
 * <ul>
 *   <li>Default date / name derivation when the request omits them (D1=A, d9)</li>
 *   <li>Period generation count (12+4+2+1 = 19) and FY-relative quarter math (d8)</li>
 *   <li>Leap-year MONTH end date (29 Feb 2028) for a non-calendar FY (d7)</li>
 *   <li>Bounds validation: non-first-day startDate, non-12-month length</li>
 *   <li>Name conflict surfacing as a clean 409</li>
 *   <li>Activation conflict (D3=B) — refuse if a sibling is ACTIVE</li>
 *   <li>Close lifecycle — only ACTIVE → CLOSED valid</li>
 *   <li>Bootstrap idempotence (D4=A)</li>
 *   <li>Delete blocked by referenced JEs (d11)</li>
 * </ul>
 *
 * <p>Test design note: mirrors Slice 1.4's approach of avoiding inline
 * mocking of concrete services. {@link FiscalYearService} is the SUT here
 * and gets real construction; only its repository collaborators are
 * mocked (interfaces — clean dynamic-proxy mocking).
 */
@ExtendWith(MockitoExtension.class)
class FiscalYearServiceTest {

    private static final Clock FIXED_CLOCK_2026 = Clock.fixed(
        Instant.parse("2026-05-15T09:00:00Z"), ZoneOffset.UTC);

    @Mock private FiscalYearRepository fiscalYearRepository;
    @Mock private FiscalPeriodRepository fiscalPeriodRepository;
    @Mock private JournalEntryRepository journalEntryRepository;

    private FiscalYearService service;

    @BeforeEach
    void setup() {
        service = new FiscalYearService(
            fiscalYearRepository, fiscalPeriodRepository, journalEntryRepository, FIXED_CLOCK_2026);
    }

    // ── create() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create with all-null request defaults to current calendar year FY (D1=A, d9)")
    void createDefaultsCalendarYear() {
        when(fiscalYearRepository.findByNameAndDeletedAtIsNull("FY2026")).thenReturn(Optional.empty());
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(invocation -> {
            FiscalYear fy = invocation.getArgument(0);
            fy.setId(UUID.randomUUID());
            return fy;
        });

        FiscalYearResponse response = service.create(new CreateFiscalYearRequest(null, null, null));

        assertThat(response.name()).isEqualTo("FY2026");
        assertThat(response.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(response.endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(response.status()).isEqualTo(FiscalYearStatus.PLANNING);
    }

    @Test
    @DisplayName("create generates exactly 19 child periods (12 MONTH + 4 QUARTER + 2 HALF_YEAR + 1 YEAR)")
    @SuppressWarnings("unchecked")
    void createGeneratesNineteenPeriods() {
        when(fiscalYearRepository.findByNameAndDeletedAtIsNull("FY2026")).thenReturn(Optional.empty());
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(invocation -> {
            FiscalYear fy = invocation.getArgument(0);
            fy.setId(UUID.randomUUID());
            return fy;
        });

        FiscalYearResponse response = service.create(new CreateFiscalYearRequest(null, null, null));

        assertThat(response.periods()).hasSize(19);
        long monthCount = response.periods().stream().filter(p -> p.periodType() == FiscalPeriodType.MONTH).count();
        long quarterCount = response.periods().stream().filter(p -> p.periodType() == FiscalPeriodType.QUARTER).count();
        long halfCount = response.periods().stream().filter(p -> p.periodType() == FiscalPeriodType.HALF_YEAR).count();
        long yearCount = response.periods().stream().filter(p -> p.periodType() == FiscalPeriodType.YEAR).count();
        assertThat(monthCount).isEqualTo(12L);
        assertThat(quarterCount).isEqualTo(4L);
        assertThat(halfCount).isEqualTo(2L);
        assertThat(yearCount).isEqualTo(1L);

        // saveAll captured the full collection
        ArgumentCaptor<List<FiscalPeriod>> captor = ArgumentCaptor.forClass(List.class);
        verify(fiscalPeriodRepository, times(1)).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(19);
    }

    @Test
    @DisplayName("MONTH periods are calendar-aligned (Jan 1-31, Feb 1-28, ..., Dec 1-31)")
    void createMonthBoundariesCalendarYear() {
        when(fiscalYearRepository.findByNameAndDeletedAtIsNull("FY2026")).thenReturn(Optional.empty());
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(invocation -> {
            FiscalYear fy = invocation.getArgument(0);
            fy.setId(UUID.randomUUID());
            return fy;
        });

        List<FiscalPeriodResponse> months = service.create(new CreateFiscalYearRequest(null, null, null))
            .periods().stream()
            .filter(p -> p.periodType() == FiscalPeriodType.MONTH)
            .sorted((a, b) -> a.startDate().compareTo(b.startDate()))
            .toList();

        // Jan: 2026-01-01 to 2026-01-31
        assertThat(months.get(0).startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(months.get(0).endDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        // Feb (non-leap): 2026-02-01 to 2026-02-28
        assertThat(months.get(1).endDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        // Dec: 2026-12-01 to 2026-12-31
        assertThat(months.get(11).endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("MONTH end date handles leap-year February (29 Feb 2028) correctly (d7)")
    void createLeapYearFebruary() {
        when(fiscalYearRepository.findByNameAndDeletedAtIsNull("FY2028")).thenReturn(Optional.empty());
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(invocation -> {
            FiscalYear fy = invocation.getArgument(0);
            fy.setId(UUID.randomUUID());
            return fy;
        });

        FiscalYearResponse response = service.create(new CreateFiscalYearRequest(
            null, LocalDate.of(2028, 1, 1), LocalDate.of(2028, 12, 31)));

        // Find February
        FiscalPeriodResponse february = response.periods().stream()
            .filter(p -> p.periodType() == FiscalPeriodType.MONTH)
            .filter(p -> p.startDate().equals(LocalDate.of(2028, 2, 1)))
            .findFirst()
            .orElseThrow();
        assertThat(february.endDate()).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("QUARTER boundaries are FY-relative for a calendar FY: Q1=Jan-Mar, Q2=Apr-Jun, Q3=Jul-Sep, Q4=Oct-Dec (d8)")
    void quartersCalendarYear() {
        when(fiscalYearRepository.findByNameAndDeletedAtIsNull("FY2026")).thenReturn(Optional.empty());
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(invocation -> {
            FiscalYear fy = invocation.getArgument(0);
            fy.setId(UUID.randomUUID());
            return fy;
        });

        List<FiscalPeriodResponse> quarters = service.create(new CreateFiscalYearRequest(null, null, null))
            .periods().stream()
            .filter(p -> p.periodType() == FiscalPeriodType.QUARTER)
            .sorted((a, b) -> a.startDate().compareTo(b.startDate()))
            .toList();

        assertThat(quarters.get(0).startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(quarters.get(0).endDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(quarters.get(1).startDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(quarters.get(1).endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(quarters.get(2).startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(quarters.get(2).endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(quarters.get(3).startDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(quarters.get(3).endDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("QUARTER boundaries are FY-relative for an April-March FY (d8 — matches management reporting)")
    void quartersAprilMarchFiscalYear() {
        when(fiscalYearRepository.findByNameAndDeletedAtIsNull("FY2026")).thenReturn(Optional.empty());
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(invocation -> {
            FiscalYear fy = invocation.getArgument(0);
            fy.setId(UUID.randomUUID());
            return fy;
        });

        FiscalYearResponse response = service.create(new CreateFiscalYearRequest(
            null, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31)));

        List<FiscalPeriodResponse> quarters = response.periods().stream()
            .filter(p -> p.periodType() == FiscalPeriodType.QUARTER)
            .sorted((a, b) -> a.startDate().compareTo(b.startDate()))
            .toList();

        // Apr-Jun, Jul-Sep, Oct-Dec, Jan-Mar — FY-relative, not calendar.
        assertThat(quarters.get(0).startDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(quarters.get(0).endDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(quarters.get(3).startDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(quarters.get(3).endDate()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    @DisplayName("create rejects a startDate that's not the first day of a month")
    void createRejectsNonFirstDayStart() {
        assertThatThrownBy(() -> service.create(new CreateFiscalYearRequest(
            null, LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 14))))
            .isInstanceOf(InvalidFiscalYearBoundsException.class)
            .hasMessageContaining("first day of a month");

        verify(fiscalYearRepository, never()).save(any(FiscalYear.class));
    }

    @Test
    @DisplayName("create rejects bounds that don't span exactly 12 months minus one day")
    void createRejectsNonTwelveMonthSpan() {
        assertThatThrownBy(() -> service.create(new CreateFiscalYearRequest(
            null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)))) // half year
            .isInstanceOf(InvalidFiscalYearBoundsException.class)
            .hasMessageContaining("12 months");
    }

    @Test
    @DisplayName("create throws FiscalYearNameConflictException on duplicate name")
    void createNameConflict() {
        FiscalYear existing = new FiscalYear();
        existing.setId(UUID.randomUUID());
        existing.setName("FY2026");
        when(fiscalYearRepository.findByNameAndDeletedAtIsNull("FY2026")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(new CreateFiscalYearRequest(null, null, null)))
            .isInstanceOf(FiscalYearNameConflictException.class)
            .hasMessageContaining("FY2026");

        verify(fiscalYearRepository, never()).save(any(FiscalYear.class));
    }

    @Test
    @DisplayName("create with explicit name overrides the auto-derived FYxxxx")
    void createExplicitName() {
        when(fiscalYearRepository.findByNameAndDeletedAtIsNull("Custom FY 2026")).thenReturn(Optional.empty());
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(invocation -> {
            FiscalYear fy = invocation.getArgument(0);
            fy.setId(UUID.randomUUID());
            return fy;
        });

        FiscalYearResponse response = service.create(new CreateFiscalYearRequest("Custom FY 2026", null, null));
        assertThat(response.name()).isEqualTo("Custom FY 2026");
    }

    // ── activate() ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("activate refuses with FiscalYearActivationConflictException if another FY is ACTIVE (D3=B)")
    void activateConflict() {
        UUID newId = UUID.randomUUID();
        FiscalYear newFy = freshPlanningFy(newId, "FY2027");
        when(fiscalYearRepository.findByIdAndDeletedAtIsNull(newId)).thenReturn(Optional.of(newFy));

        FiscalYear currentlyActive = new FiscalYear();
        currentlyActive.setId(UUID.randomUUID());
        currentlyActive.setName("FY2026");
        currentlyActive.setStatus(FiscalYearStatus.ACTIVE);
        when(fiscalYearRepository.findByStatusAndDeletedAtIsNull(FiscalYearStatus.ACTIVE))
            .thenReturn(List.of(currentlyActive));

        assertThatThrownBy(() -> service.activate(newId))
            .isInstanceOf(FiscalYearActivationConflictException.class)
            .hasMessageContaining("FY2026");

        // No save attempted on the new FY.
        verify(fiscalYearRepository, never()).save(any(FiscalYear.class));
    }

    @Test
    @DisplayName("activate is idempotent — already-ACTIVE returns response without writing")
    void activateAlreadyActive() {
        UUID id = UUID.randomUUID();
        FiscalYear fy = new FiscalYear();
        fy.setId(id);
        fy.setName("FY2026");
        fy.setStatus(FiscalYearStatus.ACTIVE);
        when(fiscalYearRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(fy));

        FiscalYearResponse response = service.activate(id);
        assertThat(response.status()).isEqualTo(FiscalYearStatus.ACTIVE);
        verify(fiscalYearRepository, never()).save(any(FiscalYear.class));
    }

    @Test
    @DisplayName("activate rejects a CLOSED FY (one-way lifecycle)")
    void activateClosedRejected() {
        UUID id = UUID.randomUUID();
        FiscalYear fy = new FiscalYear();
        fy.setId(id);
        fy.setName("FY2024");
        fy.setStatus(FiscalYearStatus.CLOSED);
        when(fiscalYearRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(fy));

        assertThatThrownBy(() -> service.activate(id))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("activate flips PLANNING → ACTIVE when no sibling is ACTIVE")
    void activateHappyPath() {
        UUID id = UUID.randomUUID();
        FiscalYear fy = freshPlanningFy(id, "FY2026");
        when(fiscalYearRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(fy));
        when(fiscalYearRepository.findByStatusAndDeletedAtIsNull(FiscalYearStatus.ACTIVE)).thenReturn(List.of());
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(inv -> inv.getArgument(0));

        FiscalYearResponse response = service.activate(id);
        assertThat(response.status()).isEqualTo(FiscalYearStatus.ACTIVE);
    }

    // ── close() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("close flips ACTIVE → CLOSED")
    void closeHappyPath() {
        UUID id = UUID.randomUUID();
        FiscalYear fy = new FiscalYear();
        fy.setId(id);
        fy.setName("FY2026");
        fy.setStatus(FiscalYearStatus.ACTIVE);
        when(fiscalYearRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(fy));
        when(fiscalYearRepository.save(any(FiscalYear.class))).thenAnswer(inv -> inv.getArgument(0));

        FiscalYearResponse response = service.close(id);
        assertThat(response.status()).isEqualTo(FiscalYearStatus.CLOSED);
    }

    @Test
    @DisplayName("close on PLANNING is rejected")
    void closePlanningRejected() {
        UUID id = UUID.randomUUID();
        FiscalYear fy = freshPlanningFy(id, "FY2026");
        when(fiscalYearRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(fy));

        assertThatThrownBy(() -> service.close(id))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("only ACTIVE");
    }

    // ── bootstrap() ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("bootstrapForNewTenant returns existing ACTIVE FY (idempotent — D4=A)")
    void bootstrapIdempotent() {
        FiscalYear existing = new FiscalYear();
        existing.setId(UUID.randomUUID());
        existing.setName("FY2026");
        existing.setStatus(FiscalYearStatus.ACTIVE);
        when(fiscalYearRepository.findByStatusAndDeletedAtIsNull(FiscalYearStatus.ACTIVE))
            .thenReturn(List.of(existing));

        FiscalYearResponse response = service.bootstrapForNewTenant();
        assertThat(response.id()).isEqualTo(existing.getId());
        // No save because we returned the existing entity.
        verify(fiscalYearRepository, never()).save(any(FiscalYear.class));
    }

    // ── delete() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete blocked when any journal entries reference the FY's child periods (d11)")
    void deleteBlockedByJournalEntries() {
        UUID fyId = UUID.randomUUID();
        FiscalYear fy = freshPlanningFy(fyId, "FY2025");
        when(fiscalYearRepository.findByIdAndDeletedAtIsNull(fyId)).thenReturn(Optional.of(fy));
        when(fiscalPeriodRepository.findIdsByFiscalYearId(fyId)).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(journalEntryRepository.countByPeriodIdInAndDeletedAtIsNull(anyCollection())).thenReturn(42L);

        assertThatThrownBy(() -> service.delete(fyId))
            .isInstanceOf(FiscalYearHasJournalEntriesException.class)
            .hasMessageContaining("42 journal entries");

        verify(fiscalYearRepository, never()).save(any(FiscalYear.class));
    }

    @Test
    @DisplayName("delete soft-deletes the FY when no JEs reference its periods")
    void deleteHappyPath() {
        UUID fyId = UUID.randomUUID();
        FiscalYear fy = freshPlanningFy(fyId, "FY2025");
        when(fiscalYearRepository.findByIdAndDeletedAtIsNull(fyId)).thenReturn(Optional.of(fy));
        when(fiscalPeriodRepository.findIdsByFiscalYearId(fyId)).thenReturn(List.of(UUID.randomUUID()));
        when(journalEntryRepository.countByPeriodIdInAndDeletedAtIsNull(anyCollection())).thenReturn(0L);

        service.delete(fyId);

        ArgumentCaptor<FiscalYear> captor = ArgumentCaptor.forClass(FiscalYear.class);
        verify(fiscalYearRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static FiscalYear freshPlanningFy(UUID id, String name) {
        FiscalYear fy = new FiscalYear();
        fy.setId(id);
        fy.setName(name);
        fy.setStartDate(LocalDate.of(2026, 1, 1));
        fy.setEndDate(LocalDate.of(2026, 12, 31));
        fy.setStatus(FiscalYearStatus.PLANNING);
        return fy;
    }
}
