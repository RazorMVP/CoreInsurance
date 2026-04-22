package com.nubeero.cia.endorsement;

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
@Table(name = "endorsements")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Endorsement extends BaseEntity {

    @Column(name = "endorsement_number", nullable = false, unique = true, length = 30)
    private String endorsementNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private EndorsementStatus status = EndorsementStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "endorsement_type", nullable = false, length = 30)
    @Builder.Default
    private EndorsementType endorsementType = EndorsementType.NON_PREMIUM_BEARING;

    // ── Policy link ──────────────────────────────────────────────────────
    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "policy_number", nullable = false, length = 60)
    private String policyNumber;

    // ── Customer snapshot ────────────────────────────────────────────────
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    // ── Product snapshot ─────────────────────────────────────────────────
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "product_code", nullable = false, length = 20)
    private String productCode;

    @Column(name = "product_rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal productRate;

    // ── Class of business snapshot ───────────────────────────────────────
    @Column(name = "class_of_business_id", nullable = false)
    private UUID classOfBusinessId;

    @Column(name = "class_of_business_name", nullable = false, length = 100)
    private String classOfBusinessName;

    // ── Broker ───────────────────────────────────────────────────────────
    @Column(name = "broker_id")
    private UUID brokerId;

    @Column(name = "broker_name", length = 100)
    private String brokerName;

    // ── Period ───────────────────────────────────────────────────────────
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "policy_end_date", nullable = false)
    private LocalDate policyEndDate;

    @Column(name = "remaining_days", nullable = false)
    private int remainingDays;

    // ── Financials ───────────────────────────────────────────────────────
    @Column(name = "old_sum_insured", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal oldSumInsured = BigDecimal.ZERO;

    @Column(name = "new_sum_insured", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal newSumInsured = BigDecimal.ZERO;

    @Column(name = "old_net_premium", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal oldNetPremium = BigDecimal.ZERO;

    @Column(name = "new_net_premium", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal newNetPremium = BigDecimal.ZERO;

    // Positive = additional premium owed; negative = return premium due
    @Column(name = "premium_adjustment", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal premiumAdjustment = BigDecimal.ZERO;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "NGN";

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // ── Workflow ─────────────────────────────────────────────────────────
    @Column(name = "workflow_id", length = 200)
    private String workflowId;

    // ── Approval ─────────────────────────────────────────────────────────
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

    // ── Cancellation ─────────────────────────────────────────────────────
    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    // ── Document ─────────────────────────────────────────────────────────
    @Column(name = "document_path", length = 500)
    private String documentPath;

    // ── Risks (post-endorsement state) ───────────────────────────────────
    @OneToMany(mappedBy = "endorsement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderNo ASC")
    @Builder.Default
    private List<EndorsementRisk> risks = new ArrayList<>();
}
