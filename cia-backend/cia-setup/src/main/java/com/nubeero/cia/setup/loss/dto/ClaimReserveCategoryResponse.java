package com.nubeero.cia.setup.loss.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ClaimReserveCategoryResponse {
    private UUID id;
    private String name;
    private String code;
    private Instant createdAt;
    private Instant updatedAt;
}
