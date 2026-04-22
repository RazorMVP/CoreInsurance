package com.nubeero.cia.setup.product.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class PolicyNumberFormatResponse {
    private UUID id;
    private UUID productId;
    private String prefix;
    private boolean includeYear;
    private boolean includeClassCode;
    private int sequenceLength;
    private long lastSequence;
    private Instant createdAt;
    private Instant updatedAt;
}
