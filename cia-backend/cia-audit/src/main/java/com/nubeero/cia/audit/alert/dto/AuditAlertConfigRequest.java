package com.nubeero.cia.audit.alert.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
public class AuditAlertConfigRequest {

    @NotNull
    private LocalTime businessHoursStart;

    @NotNull
    private LocalTime businessHoursEnd;

    @NotBlank
    private String businessDays;

    @NotNull
    @DecimalMin("0")
    private BigDecimal largeApprovalThreshold;

    @Min(1)
    private int maxFailedLoginAttempts;

    @Min(1)
    private int bulkDeleteCount;

    @Min(1)
    private int bulkDeleteWindowMinutes;

    @Min(1)
    @Max(30)
    private int retentionYears;
}
