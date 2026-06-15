package com.nubeero.cia.compliance.retention;

/** Mutable fields of the per-tenant retention policy (the schedule + retention period + opt-in flag). */
public record RetentionPolicyRequest(
        int customerPiiRetentionDays,
        boolean purgeEnabled,
        String purgeFrequency,   // WEEKLY | MONTHLY
        int purgeDayOfWeek,      // 0..6
        int purgeHourUtc         // 0..23
) {}
