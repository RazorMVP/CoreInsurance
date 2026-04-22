package com.nubeero.cia.policy.dto;

import com.nubeero.cia.quotation.BusinessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class PolicyUpdateRequest {

    private UUID brokerId;
    private BusinessType businessType;
    private Boolean niidRequired;
    private LocalDate policyStartDate;
    private LocalDate policyEndDate;

    @DecimalMin("0.00")
    private BigDecimal discount;

    private String notes;

    @Valid
    private List<PolicyRiskRequest> risks;

    @Valid
    private List<PolicyCoinsuranceParticipantRequest> coinsuranceParticipants;
}
