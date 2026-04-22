package com.nubeero.cia.endorsement.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectEndorsementRequest(@NotBlank String reason) {}
