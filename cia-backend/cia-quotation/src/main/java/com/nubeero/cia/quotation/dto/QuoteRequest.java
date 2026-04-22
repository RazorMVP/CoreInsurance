package com.nubeero.cia.quotation.dto;

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
public class QuoteRequest {

    @NotNull
    private UUID customerId;

    @NotNull
    private UUID productId;

    private UUID brokerId;

    @NotNull
    private BusinessType businessType;

    @NotNull
    private LocalDate policyStartDate;

    @NotNull
    private LocalDate policyEndDate;

    @DecimalMin("0.00")
    private BigDecimal discount;

    private String notes;

    @NotEmpty
    @Valid
    private List<QuoteRiskRequest> risks;

    @Valid
    private List<QuoteCoinsuranceParticipantRequest> coinsuranceParticipants;
}
