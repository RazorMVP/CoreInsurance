package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelFacCoverRequest(@NotBlank String reason) {}
