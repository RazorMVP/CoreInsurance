package com.nubeero.cia.setup.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClassOfBusinessRequest {
    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;

    private String description;
}
