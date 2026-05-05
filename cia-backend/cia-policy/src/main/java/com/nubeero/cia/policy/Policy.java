package com.nubeero.cia.policy;

import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.quotation.BusinessType;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "policies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Policy extends BaseEntity {

    @Column(name = "policy_number", unique = true, length = 60)
    private String policyNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private PolicyStatus status = PolicyStatus.DRAFT;

    // ── Quote linkage ────────────────────────────────────────────────────
    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(name = "quote_number", length = 30)
    private String quoteNumber;

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

    @Column(name = "class_of_business_code", nullable = false, length = 20)
    private String classOfBusinessCode;

    // ── Broker ───────────────────────────────────────────────────────────
    @Column(name = "broker_id")
    private UUID brokerId;

    @Column(name = "broker_name", length = 100)
    private String brokerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 30)
    @Builder.Default
    private BusinessType businessType = BusinessType.DIRECT;

    @Column(name = "niid_required", nullable = false)
    @Builder.Default
    private boolean niidRequired = false;

    @Column(name = "policy_start_date", nullable = false)
    private LocalDate policyStartDate;

    @Column(name = "policy_end_date", nullable = false)
    private LocalDate policyEndDate;

    // ── Financials ───────────────────────────────────────────────────────
    @Column(name = "total_sum_insured", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalSumInsured = BigDecimal.ZERO;

    @Column(name = "total_premium", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalPremium = BigDecimal.ZERO;

    @Column(nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "net_premium", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal netPremium = BigDecimal.ZERO;

    @Column(columnDefinition = "TEXT")
    private String notes;

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

    // ── NAICOM ───────────────────────────────────────────────────────────
    @Column(name = "naicom_uid", length = 100)
    private String naicomUid;

    @Column(name = "naicom_uploaded_at")
    private Instant naicomUploadedAt;

    @Column(name = "naicom_certificate_path", length = 500)
    private String naicomCertificatePath;

    // ── NIID ─────────────────────────────────────────────────────────────
    @Column(name = "niid_ref", length = 100)
    private String niidRef;

    @Column(name = "niid_uploaded_at")
    private Instant niidUploadedAt;

    // ── Document paths ───────────────────────────────────────────────────
    @Column(name = "policy_document_path", length = 500)
    private String policyDocumentPath;

    // ── Document delivery / acknowledgement ─────────────────────────────
    @Column(name = "document_sent_at")
    private Instant documentSentAt;

    @Column(name = "document_sent_by", length = 100)
    private String documentSentBy;

    @Column(name = "document_acknowledged_at")
    private Instant documentAcknowledgedAt;

    @Column(name = "document_acknowledged_by", length = 100)
    private String documentAcknowledgedBy;

    // ── Relationships ─────────────────────────────────────────────────────
    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderNo ASC")
    @Builder.Default
    private List<PolicyRisk> risks = new ArrayList<>();

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PolicyCoinsuranceParticipant> coinsuranceParticipants = new ArrayList<>();
}
