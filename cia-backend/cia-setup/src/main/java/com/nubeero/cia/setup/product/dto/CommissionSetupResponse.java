package com.nubeero.cia.setup.product.dto;

import com.nubeero.cia.setup.product.CommissionSourceType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class CommissionSetupResponse {
    private UUID id;
    private UUID productId;
    private CommissionSourceType commissionSource;
    private BigDecimal rate;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Instant createdAt;
    private Instant updatedAt;
}
