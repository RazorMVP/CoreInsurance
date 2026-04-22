package com.nubeero.cia.quotation.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class QuoteRiskResponse {
    private UUID id;
    private String description;
    private BigDecimal sumInsured;
    private BigDecimal premium;
    private UUID sectionId;
    private String sectionName;
    private Map<String, Object> riskDetails;
    private int orderNo;
}
