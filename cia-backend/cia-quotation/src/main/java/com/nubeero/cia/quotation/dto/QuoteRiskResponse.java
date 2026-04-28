package com.nubeero.cia.quotation.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class QuoteRiskResponse {
    private UUID id;
    private String description;
    private BigDecimal sumInsured;
    private BigDecimal rate;
    private BigDecimal grossPremium;
    private BigDecimal premium;
    private UUID sectionId;
    private String sectionName;
    private Map<String, Object> riskDetails;
    private List<AdjustmentEntryResponse> loadings;
    private List<AdjustmentEntryResponse> discounts;
    private int orderNo;
}
