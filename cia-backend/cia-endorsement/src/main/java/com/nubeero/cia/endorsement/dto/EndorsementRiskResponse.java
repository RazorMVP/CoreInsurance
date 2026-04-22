package com.nubeero.cia.endorsement.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record EndorsementRiskResponse(
        UUID id,
        String description,
        BigDecimal sumInsured,
        BigDecimal premium,
        UUID sectionId,
        String sectionName,
        Map<String, Object> riskDetails,
        String vehicleRegNumber,
        int orderNo
) {}
