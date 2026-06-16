package com.nubeero.cia.compliance.purge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class PurgeWindowTest {

    // Sunday 2026-06-14 03:00:00 UTC
    private static final Instant SUNDAY_0300 =
            ZonedDateTime.of(2026, 6, 14, 3, 0, 0, 0, ZoneOffset.UTC).toInstant();

    @Test
    void weekly_matchesOnConfiguredDayAndHour() {
        assertThat(PurgeWindow.matches(SUNDAY_0300, "WEEKLY", 0, 3)).isTrue();   // Sunday=0, 03:00
    }

    @Test
    void weekly_noMatchOnWrongHour() {
        assertThat(PurgeWindow.matches(SUNDAY_0300, "WEEKLY", 0, 4)).isFalse();
    }

    @Test
    void weekly_noMatchOnWrongDay() {
        assertThat(PurgeWindow.matches(SUNDAY_0300, "WEEKLY", 1, 3)).isFalse(); // configured Monday
    }

    @Test
    void monthly_matchesOnlyOnDayOne() {
        Instant firstOfMonth0300 =
                ZonedDateTime.of(2026, 7, 1, 3, 0, 0, 0, ZoneOffset.UTC).toInstant();
        assertThat(PurgeWindow.matches(firstOfMonth0300, "MONTHLY", 5, 3)).isTrue(); // day-of-week ignored
        assertThat(PurgeWindow.matches(SUNDAY_0300, "MONTHLY", 0, 3)).isFalse();      // 14th, not the 1st
    }

    @Test
    void debounce_blocksWhenLastRunInsideWindow() {
        // last run 1h ago → still inside the 23h debounce → blocked
        assertThat(PurgeWindow.debouncePassed(SUNDAY_0300, SUNDAY_0300.minusSeconds(3600))).isFalse();
        // last run never → allowed
        assertThat(PurgeWindow.debouncePassed(SUNDAY_0300, null)).isTrue();
        // last run 24h ago → outside debounce → allowed
        assertThat(PurgeWindow.debouncePassed(SUNDAY_0300, SUNDAY_0300.minusSeconds(24 * 3600))).isTrue();
    }
}
