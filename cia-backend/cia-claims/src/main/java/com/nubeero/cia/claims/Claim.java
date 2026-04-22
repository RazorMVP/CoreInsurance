package com.nubeero.cia.claims;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "claims")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Claim extends BaseEntity {

    @Column(name = "claim_number", nullable = false, unique = true, length = 30)
    private String claimNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private ClaimStatus status = ClaimStatus.REGISTERED;

    // ── Policy link (snapshot at registration) ───────────────────────────
    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "policy_number", nullable = false, length = 60)
    private String policyNumber;

    @Column(name = "policy_start_date", nullable = false)
    private LocalDate policyStartDate;

    @Column(name = "policy_end_date", nullable = false)
    private LocalDate policyEndDate;

    // ── Customer snapshot ────────────────────────────────────────────────
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    // ── Product / class snapshot ─────────────────────────────────────────
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "class_of_business_id", nullable = false)
    private UUID classOfBusinessId;

    @Column(name = "class_of_business_name", nullable = false, length = 100)
    private String classOfBusinessName;

    // ── Broker ───────────────────────────────────────────────────────────
    @Column(name = "broker_id")
    private UUID brokerId;

    @Column(name = "broker_name", length = 100)
    private String brokerName;

    // ── Incident ─────────────────────────────────────────────────────────
    @Column(name = "incident_date", nullable = false)
    private LocalDate incidentDate;

    @Column(name = "reported_date", nullable = false)
    private LocalDate reportedDate;

    @Column(name = "loss_location", length = 500)
    private String lossLocation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "estimated_loss", precision = 18, scale = 2)
    private BigDecimal estimatedLoss;

    // ── Financials ───────────────────────────────────────────────────────
    @Column(name = "reserve_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal reserveAmount = BigDecimal.ZERO;

    @Column(name = "approved_amount", precision = 18, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "NGN";

    // ── Surveyor ─────────────────────────────────────────────────────────
    @Column(name = "surveyor_id")
    private UUID surveyorId;

    @Column(name = "surveyor_name", length = 200)
    private String surveyorName;

    @Column(name = "surveyor_assigned_at")
    private Instant surveyorAssignedAt;

    // ── Workflow ─────────────────────────────────────────────────────────
    @Column(name = "workflow_id", length = 200)
    private String workflowId;

    // ── Approval / rejection ─────────────────────────────────────────────
    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by", length = 100)
    private String rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    // ── Withdrawal ───────────────────────────────────────────────────────
    @Column(name = "withdrawn_by", length = 100)
    private String withdrawnBy;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawal_reason", columnDefinition = "TEXT")
    private String withdrawalReason;

    // ── Settlement ───────────────────────────────────────────────────────
    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // ── Document ─────────────────────────────────────────────────────────
    @Column(name = "dv_document_path", length = 500)
    private String dvDocumentPath;

    // ── Relationships ─────────────────────────────────────────────────────
    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt DESC")
    @Builder.Default
    private List<ClaimReserve> reserves = new ArrayList<>();

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ClaimExpense> expenses = new ArrayList<>();

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ClaimDocument> documents = new ArrayList<>();
}
