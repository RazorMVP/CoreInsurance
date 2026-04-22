package com.nubeero.cia.setup.product.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ClaimDocumentRequirementResponse {
    private UUID id;
    private UUID productId;
    private String documentName;
    private boolean mandatory;
    private Instant createdAt;
    private Instant updatedAt;
}
