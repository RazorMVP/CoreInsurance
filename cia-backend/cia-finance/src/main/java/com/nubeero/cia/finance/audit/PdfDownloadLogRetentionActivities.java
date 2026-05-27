package com.nubeero.cia.finance.audit;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface PdfDownloadLogRetentionActivities {
    void purgeOlderThan30Days();
}
