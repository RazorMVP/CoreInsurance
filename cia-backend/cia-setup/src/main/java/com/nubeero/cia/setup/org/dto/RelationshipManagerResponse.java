package com.nubeero.cia.setup.org.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class RelationshipManagerResponse {
    private UUID id;
    private String name;
    private String email;
    private String phone;
    private UUID branchId;
    private String branchName;
    private Instant createdAt;
    private Instant updatedAt;
}
