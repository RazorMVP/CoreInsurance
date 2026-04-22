package com.nubeero.cia.policy.dto;

import com.nubeero.cia.policy.PolicyStatus;
import com.nubeero.cia.quotation.BusinessType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class PolicySummaryResponse {
    private UUID id;
    private String policyNumber;
    private PolicyStatus status;
    private UUID customerId;
    private String customerName;
    private String productName;
    private String classOfBusinessName;
    private String brokerName;
    private BusinessType businessType;
    private LocalDate policyStartDate;
    private LocalDate policyEndDate;
    private BigDecimal netPremium;
    private String naicomUid;
    private Instant createdAt;
}
