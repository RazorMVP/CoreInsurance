package com.nubeero.cia.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeclineInspectionRequest {

    @NotBlank
    @Size(min = 5, max = 1000, message = "Decline reason must be at least 5 characters")
    private String reason;
}
