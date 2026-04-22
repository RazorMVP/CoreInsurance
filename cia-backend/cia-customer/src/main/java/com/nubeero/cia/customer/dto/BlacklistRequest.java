package com.nubeero.cia.customer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BlacklistRequest {

    @NotBlank
    private String reason;
}
