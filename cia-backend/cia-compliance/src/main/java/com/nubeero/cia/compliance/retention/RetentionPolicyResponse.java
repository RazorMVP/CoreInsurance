package com.nubeero.cia.compliance.retention;

import java.time.Instant;

public record RetentionPolicyResponse(
        int customerPiiRetentionDays,
        boolean purgeEnabled,
        String purgeFrequency,
        int purgeDayOfWeek,
        int purgeHourUtc,
        Instant lastPurgeRunAt
) {
    public static RetentionPolicyResponse from(DataRetentionPolicy p) {
        return new RetentionPolicyResponse(
                p.getCustomerPiiRetentionDays(), p.isPurgeEnabled(), p.getPurgeFrequency(),
                p.getPurgeDayOfWeek(), p.getPurgeHourUtc(), p.getLastPurgeRunAt());
    }
}
