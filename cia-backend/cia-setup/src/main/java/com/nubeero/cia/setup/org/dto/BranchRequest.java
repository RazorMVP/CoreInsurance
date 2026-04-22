package com.nubeero.cia.setup.org.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class BranchRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;

    private UUID sbuId;

    private String address;
}
