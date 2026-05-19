package com.nubeero.cia.finance.gl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1.7c — holiday-aware {@link PeriodLockService#addBusinessDays}
 * unit tests. The static overload is parameterised on the holiday set so
 * each test fixes its own NAICOM-aligned calendar without spinning up the
 * repository.
 */
class PeriodLockServiceHolidayTest {

    private static Instant utcMidnight(String iso) {
        return LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Test
    @DisplayName("with no holidays: 5-business-day grace from Wed 2026-05-13 lands on Wed 2026-05-20")
    void noHolidaysWedToWed() {
        Instant from = utcMidnight("2026-05-13");
        Instant result = PeriodLockService.addBusinessDays(from, 5, Set.of());
        assertThat(result).isEqualTo(utcMidnight("2026-05-20"));
    }

    @Test
    @DisplayName("with no holidays: 5 days from Friday 2026-05-15 lands on Friday 2026-05-22 (weekend skipped)")
    void noHolidaysFriToFri() {
        Instant from = utcMidnight("2026-05-15");
        Instant result = PeriodLockService.addBusinessDays(from, 5, Set.of());
        assertThat(result).isEqualTo(utcMidnight("2026-05-22"));
    }

    @Test
    @DisplayName("with a Monday holiday: 5 days from Wed 2026-05-13 lands on Thu 2026-05-21 (Mon 18th skipped)")
    void singleHolidayShiftsGraceByOneDay() {
        Instant from = utcMidnight("2026-05-13");
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 5, 18));   // Workers' Day observed (illustrative)

        Instant result = PeriodLockService.addBusinessDays(from, 5, holidays);

        assertThat(result)
            .as("Wed→Thu→Fri→[Sat,Sun skipped]→[Mon HOLIDAY skipped]→Tue→Wed→Thu = 5 business days")
            .isEqualTo(utcMidnight("2026-05-21"));
    }

    @Test
    @DisplayName("with two consecutive holidays: grace shifts by exactly two days")
    void twoConsecutiveHolidays() {
        Instant from = utcMidnight("2026-05-13");
        // Mon 18 + Tue 19 both holidays — two extra skips
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 19));

        Instant result = PeriodLockService.addBusinessDays(from, 5, holidays);

        assertThat(result)
            .as("each holiday displaces the cut-off by one additional calendar day")
            .isEqualTo(utcMidnight("2026-05-22"));
    }

    @Test
    @DisplayName("a holiday that falls on a weekend does NOT double-skip (weekend already excluded)")
    void holidayOnWeekendIsNoOp() {
        Instant from = utcMidnight("2026-05-13");
        // Sat 16 is already a weekend, so flagging it as a holiday must not
        // shift the result — defensive check against off-by-one when CFOs
        // load a calendar that includes Saturday observances.
        Set<LocalDate> weekendHoliday = Set.of(LocalDate.of(2026, 5, 16));

        Instant result = PeriodLockService.addBusinessDays(from, 5, weekendHoliday);

        assertThat(result).isEqualTo(utcMidnight("2026-05-20"));
    }

    @Test
    @DisplayName("static back-compat overload — no holidays parameter — matches the pre-1.7c contract")
    void backCompatStaticOverload() {
        Instant from = utcMidnight("2026-05-13");
        Instant via2arg = PeriodLockService.addBusinessDays(from, 5);
        Instant via3arg = PeriodLockService.addBusinessDays(from, 5, Set.of());
        assertThat(via2arg)
            .as("two-arg form must remain identical to three-arg with empty holiday set — preserves "
                + "every pre-1.7c assertion in PeriodLockServiceTest")
            .isEqualTo(via3arg);
    }
}
