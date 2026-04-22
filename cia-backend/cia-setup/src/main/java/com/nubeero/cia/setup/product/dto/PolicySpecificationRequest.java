package com.nubeero.cia.setup.product.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PolicySpecificationRequest {

    @NotBlank
    private String content;
}
