package com.nubeero.cia.setup.vehicle.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class VehicleModelResponse {
    private UUID id;
    private String name;
    private UUID makeId;
    private String makeName;
    private Instant createdAt;
    private Instant updatedAt;
}
