package com.nubeero.cia.quotation;

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
@Table(name = "quotes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Quote extends BaseEntity {

    @Column(name = "quote_number", nullable = false, unique = true, length = 30)
    private String quoteNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private QuoteStatus status = QuoteStatus.DRAFT;

    // ── Customer snapshot ────────────────────────────────────────────────
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    // ── Product snapshot (locked at quote creation) ──────────────────────
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

    // ── Broker (nullable) ────────────────────────────────────────────────
    @Column(name = "broker_id")
    private UUID brokerId;

    @Column(name = "broker_name", length = 100)
    private String brokerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 30)
    @Builder.Default
    private BusinessType businessType = BusinessType.DIRECT;

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

    // ── Approval fields ──────────────────────────────────────────────────
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

    @Column(name = "expires_at")
    private Instant expiresAt;

    // ── Relationships ────────────────────────────────────────────────────
    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderNo ASC")
    @Builder.Default
    private List<QuoteRisk> risks = new ArrayList<>();

    @OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<QuoteCoinsuranceParticipant> coinsuranceParticipants = new ArrayList<>();
}
