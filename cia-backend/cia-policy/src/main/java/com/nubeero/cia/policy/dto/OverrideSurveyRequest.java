package com.nubeero.cia.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OverrideSurveyRequest {

    @NotBlank
    @Size(min = 5, max = 1000, message = "Override reason must be at least 5 characters")
    private String reason;
}
