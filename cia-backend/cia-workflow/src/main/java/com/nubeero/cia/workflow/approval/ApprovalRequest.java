package com.nubeero.cia.workflow.approval;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ApprovalRequest {
    private String entityType;
    private String entityId;
    private String tenantId;
    private String initiatedBy;
    private BigDecimal amount;
    private String currency;
}
