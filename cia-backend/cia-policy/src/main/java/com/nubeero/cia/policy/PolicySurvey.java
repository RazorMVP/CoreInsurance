package com.nubeero.cia.policy;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Pre-loss survey record. One row per policy maximum (enforced by a unique
 * constraint on policy_id at the schema level).
 */
@Entity
@Table(name = "policy_surveys")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicySurvey extends BaseEntity {

    @Column(name = "policy_id", nullable = false, unique = true)
    private UUID policyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private SurveyStatus status = SurveyStatus.ASSIGNED;

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

    @Column(name = "override_reason", columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "overridden_by", length = 100)
    private String overriddenBy;

    @Column(name = "overridden_at")
    private Instant overriddenAt;
}
