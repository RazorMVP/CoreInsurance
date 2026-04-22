package com.nubeero.cia.setup.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SbuRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;
}
