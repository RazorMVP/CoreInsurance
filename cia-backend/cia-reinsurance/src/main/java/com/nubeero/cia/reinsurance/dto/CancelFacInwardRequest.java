package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelFacInwardRequest(@NotBlank String reason) {}
