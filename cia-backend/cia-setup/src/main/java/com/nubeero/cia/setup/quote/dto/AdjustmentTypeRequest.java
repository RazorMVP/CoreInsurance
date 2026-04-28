package com.nubeero.cia.setup.quote.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdjustmentTypeRequest {

    @NotBlank
    @Size(max = 200)
    private String name;
}
