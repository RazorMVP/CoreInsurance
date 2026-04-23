package com.nubeero.cia.audit.report.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserActivitySummary {
    private String userId;
    private String userName;
    private long actionCount;
}
