package com.nubeero.cia.documents.dto;

import com.nubeero.cia.documents.DocumentTemplateType;

import java.time.Instant;
import java.util.UUID;

public record DocumentTemplateResponse(
        UUID id,
        DocumentTemplateType templateType,
        UUID productId,
        UUID classOfBusinessId,
        String storagePath,
        String description,
        boolean active,
        Instant createdAt
) {}
