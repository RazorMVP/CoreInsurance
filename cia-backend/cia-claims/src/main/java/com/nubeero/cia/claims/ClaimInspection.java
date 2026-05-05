package com.nubeero.cia.claims;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Post-loss inspection record. One row per claim maximum (enforced by a
 * unique constraint on claim_id at the schema level).
 */
@Entity
@Table(name = "claim_inspections")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClaimInspection extends BaseEntity {

    @Column(name = "claim_id", nullable = false, unique = true)
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private InspectionStatus status = InspectionStatus.ASSIGNED;

    /** "INTERNAL" or "EXTERNAL". Free-form so future categories don't need a schema change. */
    @Column(name = "surveyor_type", length = 20)
    private String surveyorType;

    @Column(name = "surveyor_id")
    private UUID surveyorId;

    @Column(name = "surveyor_name", length = 200)
    private String surveyorName;

    @Column(name = "assigned_by", length = 100)
    private String assignedBy;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    /** Storage path/key for the uploaded report file (optional). */
    @Column(name = "report_path", length = 500)
    private String reportPath;

    @Column(name = "report_notes", columnDefinition = "TEXT")
    private String reportNotes;

    @Column(name = "report_uploaded_by", length = 100)
    private String reportUploadedBy;

    @Column(name = "report_uploaded_at")
    private Instant reportUploadedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_notes", columnDefinition = "TEXT")
    private String approvalNotes;

    @Column(name = "declined_by", length = 100)
    private String declinedBy;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "decline_reason", columnDefinition = "TEXT")
    private String declineReason;

    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "overridden_by", length = 100)
    private String overriddenBy;

    @Column(name = "overridden_at")
    private Instant overriddenAt;
}
