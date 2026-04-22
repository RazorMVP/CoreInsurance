package com.nubeero.cia.setup.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CurrencyRequest {

    @NotBlank
    @Size(min = 3, max = 3)
    private String code;

    @NotBlank
    private String name;

    private String symbol;

    private boolean isDefault;
}
