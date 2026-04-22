package com.nubeero.cia.setup.access.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AccessGroupResponse {
    private UUID id;
    private String name;
    private String description;
    private List<String> permissions;
    private Instant createdAt;
    private Instant updatedAt;
}
