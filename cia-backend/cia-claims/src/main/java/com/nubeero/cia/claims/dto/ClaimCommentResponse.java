package com.nubeero.cia.claims.dto;

import java.time.Instant;
import java.util.UUID;

public record ClaimCommentResponse(
        UUID id,
        UUID claimId,
        String body,
        String authorName,
        String createdBy,
        Instant createdAt
) {}
