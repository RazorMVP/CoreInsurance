package com.nubeero.cia.claims.dto;

import com.nubeero.cia.claims.ClaimDocumentType;

import java.time.Instant;
import java.util.UUID;

public record ClaimDocumentResponse(
        UUID id,
        UUID claimId,
        ClaimDocumentType documentType,
        String fileName,
        String filePath,
        Long fileSize,
        String uploadedBy,
        Instant createdAt
) {}
