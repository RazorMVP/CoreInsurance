package com.nubeero.cia.setup.product.dto;

import com.nubeero.cia.setup.product.SurveyThresholdType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class SurveyThresholdResponse {
    private UUID id;
    private UUID productId;
    private SurveyThresholdType thresholdType;
    private BigDecimal minSumInsured;
    private Instant createdAt;
    private Instant updatedAt;
}
