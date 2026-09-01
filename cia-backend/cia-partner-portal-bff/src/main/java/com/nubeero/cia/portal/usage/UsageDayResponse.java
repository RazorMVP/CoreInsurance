package com.nubeero.cia.portal.usage;

import com.nubeero.cia.partner.usage.PartnerUsageRollupStore.DailyCounts;

/** {@code today}'s request counters — the live (not-yet-flushed) bucket. */
public record UsageDayResponse(long total, long success, long clientError, long serverError) {

    static UsageDayResponse from(DailyCounts counts) {
        return new UsageDayResponse(counts.total(), counts.success(), counts.clientError(), counts.serverError());
    }
}
