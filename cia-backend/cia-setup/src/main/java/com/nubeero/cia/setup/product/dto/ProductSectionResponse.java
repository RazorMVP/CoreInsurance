package com.nubeero.cia.setup.product.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class ProductSectionResponse {
    private UUID id;
    private String name;
    private String code;
    private BigDecimal rate;
    private int orderNo;
}
