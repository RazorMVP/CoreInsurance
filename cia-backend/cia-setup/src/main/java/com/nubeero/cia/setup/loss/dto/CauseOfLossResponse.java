package com.nubeero.cia.setup.loss.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CauseOfLossResponse {
    private UUID id;
    private String name;
    private String code;
    private UUID natureOfLossId;
    private String natureOfLossName;
    private Instant createdAt;
    private Instant updatedAt;
}
