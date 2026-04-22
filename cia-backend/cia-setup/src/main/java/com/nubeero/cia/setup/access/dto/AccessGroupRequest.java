package com.nubeero.cia.setup.access.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class AccessGroupRequest {

    @NotBlank
    private String name;

    private String description;

    @NotEmpty
    private List<String> permissions;
}
