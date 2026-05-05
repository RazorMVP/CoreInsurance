package com.nubeero.cia.policy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignSurveyorRequest {

    /** "INTERNAL" or "EXTERNAL". */
    @NotBlank
    private String surveyorType;

    @NotNull
    private UUID surveyorId;

    @NotBlank
    private String surveyorName;
}
