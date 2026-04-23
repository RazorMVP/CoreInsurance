package com.nubeero.cia.audit.alert.dto;

import com.nubeero.cia.audit.alert.AuditAlertConfig;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@Builder
public class AuditAlertConfigResponse {

    private UUID id;
    private LocalTime businessHoursStart;
    private LocalTime businessHoursEnd;
    private String businessDays;
    private BigDecimal largeApprovalThreshold;
    private int maxFailedLoginAttempts;
    private int bulkDeleteCount;
    private int bulkDeleteWindowMinutes;
    private int retentionYears;

    public static AuditAlertConfigResponse from(AuditAlertConfig config) {
        return AuditAlertConfigResponse.builder()
                .id(config.getId())
                .businessHoursStart(config.getBusinessHoursStart())
                .businessHoursEnd(config.getBusinessHoursEnd())
                .businessDays(config.getBusinessDays())
                .largeApprovalThreshold(config.getLargeApprovalThreshold())
                .maxFailedLoginAttempts(config.getMaxFailedLoginAttempts())
                .bulkDeleteCount(config.getBulkDeleteCount())
                .bulkDeleteWindowMinutes(config.getBulkDeleteWindowMinutes())
                .retentionYears(config.getRetentionYears())
                .build();
    }
}
