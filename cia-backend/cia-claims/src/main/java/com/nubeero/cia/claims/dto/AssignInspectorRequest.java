package com.nubeero.cia.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignInspectorRequest {

    /** "INTERNAL" or "EXTERNAL". */
    @NotBlank
    private String surveyorType;

    @NotNull
    private UUID surveyorId;

    @NotBlank
    private String surveyorName;
}
