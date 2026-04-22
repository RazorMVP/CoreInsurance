package com.nubeero.cia.policy.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class PolicyCoinsuranceParticipantResponse {
    private UUID id;
    private UUID insuranceCompanyId;
    private String insuranceCompanyName;
    private BigDecimal sharePercentage;
}
