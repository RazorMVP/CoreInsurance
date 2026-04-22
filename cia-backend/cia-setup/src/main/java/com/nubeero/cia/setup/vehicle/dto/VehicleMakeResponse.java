package com.nubeero.cia.setup.vehicle.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class VehicleMakeResponse {
    private UUID id;
    private String name;
    private Instant createdAt;
    private Instant updatedAt;
}
