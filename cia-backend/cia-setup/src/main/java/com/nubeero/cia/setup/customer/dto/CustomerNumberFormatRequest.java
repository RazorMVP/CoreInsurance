package com.nubeero.cia.setup.customer.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CustomerNumberFormatRequest {

    @NotBlank
    @Size(max = 20)
    private String prefix;

    private boolean includeYear;

    private boolean includeType;

    @Min(5)
    private int sequenceLength;
}
