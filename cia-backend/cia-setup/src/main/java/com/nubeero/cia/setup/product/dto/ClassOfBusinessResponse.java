package com.nubeero.cia.setup.product.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ClassOfBusinessResponse {
    private UUID id;
    private String name;
    private String code;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
