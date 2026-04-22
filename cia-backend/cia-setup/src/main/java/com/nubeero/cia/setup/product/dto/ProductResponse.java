package com.nubeero.cia.setup.product.dto;

import com.nubeero.cia.setup.product.ProductType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ProductResponse {
    private UUID id;
    private String name;
    private String code;
    private UUID classOfBusinessId;
    private String classOfBusinessName;
    private ProductType type;
    private BigDecimal rate;
    private BigDecimal minPremium;
    private boolean active;
    private List<ProductSectionResponse> sections;
    private Instant createdAt;
    private Instant updatedAt;
}
