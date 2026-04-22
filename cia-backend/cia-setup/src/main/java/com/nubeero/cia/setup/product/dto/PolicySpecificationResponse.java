package com.nubeero.cia.setup.product.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class PolicySpecificationResponse {
    private UUID id;
    private UUID productId;
    private String content;
    private Instant createdAt;
    private Instant updatedAt;
}
