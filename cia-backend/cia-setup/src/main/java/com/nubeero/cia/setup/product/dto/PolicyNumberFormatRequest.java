package com.nubeero.cia.setup.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PolicyNumberFormatRequest {

    @NotBlank
    @Size(max = 20)
    private String prefix;

    private boolean includeYear;

    private boolean includeClassCode;

    @Min(3)
    private int sequenceLength;
}
