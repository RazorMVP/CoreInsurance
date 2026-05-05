package com.nubeero.cia.claims.dto;

import com.nubeero.cia.claims.InspectionStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class ClaimInspectionResponse {

    UUID             id;
    UUID             claimId;
    InspectionStatus status;

    String  surveyorType;
    UUID    surveyorId;
    String  surveyorName;
    String  assignedBy;
    Instant assignedAt;

    String  reportPath;
    String  reportNotes;
    String  reportUploadedBy;
    Instant reportUploadedAt;

    String  approvedBy;
    Instant approvedAt;
    String  approvalNotes;

    String  declinedBy;
    Instant declinedAt;
    String  declineReason;

    String  overrideReason;
    String  overriddenBy;
    Instant overriddenAt;

    Instant createdAt;
}
