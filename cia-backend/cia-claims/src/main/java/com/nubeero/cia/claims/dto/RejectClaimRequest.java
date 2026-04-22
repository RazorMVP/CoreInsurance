package com.nubeero.cia.claims.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectClaimRequest(@NotBlank String reason) {}
