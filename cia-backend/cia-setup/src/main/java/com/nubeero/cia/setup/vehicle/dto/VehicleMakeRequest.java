package com.nubeero.cia.setup.vehicle.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VehicleMakeRequest {

    @NotBlank
    private String name;
}
