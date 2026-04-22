package com.nubeero.cia.partner.controller.dto;

import com.nubeero.cia.setup.product.ProductType;
import com.nubeero.cia.setup.product.dto.ProductResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Insurance product available through the partner API")
public class PartnerProductResponse {

    @Schema(description = "Unique product identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Product display name", example = "Comprehensive Motor Vehicle Insurance")
    private String name;

    @Schema(description = "Short product code used in policy numbers and references", example = "CMV")
    private String code;

    @Schema(description = "Class of business ID this product belongs to", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID classOfBusinessId;

    @Schema(description = "Class of business name", example = "Motor")
    private String classOfBusinessName;

    @Schema(description = "Product type: SINGLE_RISK or MULTI_RISK", example = "SINGLE_RISK")
    private ProductType type;

    @Schema(description = "Annual premium rate as a decimal fraction of sum insured", example = "0.050")
    private BigDecimal rate;

    @Schema(description = "Minimum premium enforced regardless of calculated premium (NGN)", example = "10000.00")
    private BigDecimal minPremium;

    @Schema(description = "Whether this product is currently available for new quotes", example = "true")
    private boolean active;

    @Schema(description = "Timestamp when the product was created", example = "2025-12-01T00:00:00Z")
    private Instant createdAt;

    @Schema(description = "Timestamp of the last product update", example = "2026-01-15T09:00:00Z")
    private Instant updatedAt;

    public static PartnerProductResponse from(ProductResponse p) {
        return PartnerProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .code(p.getCode())
                .classOfBusinessId(p.getClassOfBusinessId())
                .classOfBusinessName(p.getClassOfBusinessName())
                .type(p.getType())
                .rate(p.getRate())
                .minPremium(p.getMinPremium())
                .active(p.isActive())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
