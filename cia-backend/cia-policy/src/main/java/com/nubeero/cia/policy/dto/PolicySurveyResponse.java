package com.nubeero.cia.policy.dto;

import com.nubeero.cia.policy.SurveyStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class PolicySurveyResponse {

    UUID         id;
    UUID         policyId;
    SurveyStatus status;

    String       surveyorType;
    UUID         surveyorId;
    String       surveyorName;
    String       assignedBy;
    Instant      assignedAt;

    String       reportPath;
    String       reportNotes;
    String       reportUploadedBy;
    Instant      reportUploadedAt;

    String       approvedBy;
    Instant      approvedAt;
    String       approvalNotes;

    String       overrideReason;
    String       overriddenBy;
    Instant      overriddenAt;

    Instant      createdAt;
}
