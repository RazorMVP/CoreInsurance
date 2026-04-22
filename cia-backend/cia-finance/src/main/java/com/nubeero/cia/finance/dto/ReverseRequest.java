package com.nubeero.cia.finance.dto;

import jakarta.validation.constraints.NotBlank;

public record ReverseRequest(
        @NotBlank String reason
) {}
