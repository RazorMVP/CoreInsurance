package com.nubeero.cia.endorsement.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelEndorsementRequest(@NotBlank String reason) {}
