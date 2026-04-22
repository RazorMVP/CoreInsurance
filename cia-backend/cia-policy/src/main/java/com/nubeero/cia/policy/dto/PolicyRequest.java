package com.nubeero.cia.policy.dto;

import com.nubeero.cia.quotation.BusinessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
public class PolicyRequest {

    @NotNull
    private UUID customerId;

    @NotNull
    private UUID productId;

    private UUID brokerId;

    @NotNull
    private BusinessType businessType;

    private boolean niidRequired;

    @NotNull
    private LocalDate policyStartDate;

    @NotNull
    private LocalDate policyEndDate;

    @DecimalMin("0.00")
    private BigDecimal discount;

    private String notes;

    @NotEmpty
    @Valid
    private List<PolicyRiskRequest> risks;

    @Valid
    private List<PolicyCoinsuranceParticipantRequest> coinsuranceParticipants;
}
