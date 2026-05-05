package com.nubeero.cia.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddClaimCommentRequest(
        @NotBlank @Size(min = 2, max = 4000) String body
) {}
