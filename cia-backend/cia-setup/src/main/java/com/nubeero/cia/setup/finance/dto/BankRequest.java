package com.nubeero.cia.setup.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BankRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 10)
    private String code;
}
