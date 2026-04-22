package com.nubeero.cia.setup.org.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class BranchResponse {
    private UUID id;
    private String name;
    private String code;
    private UUID sbuId;
    private String sbuName;
    private String address;
    private Instant createdAt;
    private Instant updatedAt;
}
