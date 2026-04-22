package com.nubeero.cia.setup.org.dto;

import com.nubeero.cia.setup.org.SurveyorType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class SurveyorResponse {
    private UUID id;
    private String name;
    private SurveyorType type;
    private String licenseNumber;
    private String email;
    private String phone;
    private Instant createdAt;
    private Instant updatedAt;
}
