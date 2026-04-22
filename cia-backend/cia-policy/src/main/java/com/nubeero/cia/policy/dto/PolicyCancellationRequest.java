package com.nubeero.cia.policy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PolicyCancellationRequest {

    @NotBlank
    private String reason;
}
