package com.nubeero.cia.setup.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductSectionRequest {
    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal rate;

    private int orderNo;
}
