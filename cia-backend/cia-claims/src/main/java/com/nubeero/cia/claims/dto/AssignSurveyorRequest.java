package com.nubeero.cia.claims.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignSurveyorRequest(@NotNull UUID surveyorId) {}
