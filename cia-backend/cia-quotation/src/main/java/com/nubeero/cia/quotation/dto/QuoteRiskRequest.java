package com.nubeero.cia.quotation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
public class QuoteRiskRequest {

    @NotBlank
    private String description;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal sumInsured;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal rate;

    private UUID sectionId;

    private Map<String, Object> riskDetails;

    @Valid
    private List<AdjustmentEntryRequest> loadings = new ArrayList<>();

    @Valid
    private List<AdjustmentEntryRequest> discounts = new ArrayList<>();
}
