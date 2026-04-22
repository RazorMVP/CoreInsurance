package com.nubeero.cia.claims.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelExpenseRequest(@NotBlank String reason) {}
