package com.nubeero.cia.setup.loss.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ClaimReserveCategoryRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;
}
