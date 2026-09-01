package com.nubeero.cia.partner.usage;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface PartnerUsageFlushActivities {

    /** Drains yesterday's (UTC) {@link PartnerUsageRollupStore} counters into {@link PartnerRequestDaily}. */
    void flushYesterday();
}
