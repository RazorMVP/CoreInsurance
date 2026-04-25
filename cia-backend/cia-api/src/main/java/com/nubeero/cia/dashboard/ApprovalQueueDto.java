package com.nubeero.cia.dashboard;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ApprovalQueueDto {
    long policies;
    long quotes;
    long endorsements;
    long claims;
    long receipts;
    long payments;

    public long total() {
        return policies + quotes + endorsements + claims + receipts + payments;
    }
}
