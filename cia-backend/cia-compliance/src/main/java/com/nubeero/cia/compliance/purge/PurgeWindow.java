package com.nubeero.cia.compliance.purge;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Pure decision logic for the hourly retention-purge cron: does a tenant's configured
 * window match the current UTC hour, and has the per-window debounce elapsed.
 * No clock, DB, or Temporal coupling — {@code now} is always supplied by the caller.
 */
public final class PurgeWindow {

    /** A window already fired this run if it ran less than this ago (≈ once per scheduled window). */
    private static final Duration DEBOUNCE = Duration.ofHours(23);

    private PurgeWindow() {}

    /** True iff {@code now} (UTC) falls in the tenant's configured purge window. */
    public static boolean matches(Instant now, String frequency, int dayOfWeek, int hourUtc) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        if (utc.getHour() != hourUtc) {
            return false;
        }
        if ("MONTHLY".equals(frequency)) {
            return utc.getDayOfMonth() == 1;
        }
        // WEEKLY: java DayOfWeek is MON=1..SUN=7; config is SUN=0..SAT=6.
        int configDow = utc.getDayOfWeek().getValue() % 7; // SUN(7)→0, MON(1)→1, … SAT(6)→6
        return configDow == dayOfWeek;
    }

    /** True iff enough time has elapsed since the last run to fire again. */
    public static boolean debouncePassed(Instant now, Instant lastPurgeRunAt) {
        return lastPurgeRunAt == null || lastPurgeRunAt.isBefore(now.minus(DEBOUNCE));
    }
}
