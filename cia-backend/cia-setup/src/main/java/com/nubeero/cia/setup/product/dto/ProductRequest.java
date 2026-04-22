package com.nubeero.cia.setup.product.dto;

import com.nubeero.cia.setup.product.ProductType;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
public class ProductRequest {
    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;

    @NotNull
    private UUID classOfBusinessId;

    @NotNull
    private ProductType type;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal rate;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal minPremium;

    private boolean active = true;

    private List<ProductSectionRequest> sections;
}
