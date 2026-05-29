package com.nubeero.cia.policy.dto;

import com.nubeero.cia.policy.PolicyStatus;
import com.nubeero.cia.quotation.BusinessType;
import com.nubeero.cia.setup.product.CommissionSourceType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class PolicyResponse {
    private UUID id;
    private String policyNumber;
    private PolicyStatus status;

    private UUID quoteId;
    private String quoteNumber;

    private UUID customerId;
    private String customerName;

    private UUID productId;
    private String productName;
    private String productCode;
    private BigDecimal productRate;

    private UUID classOfBusinessId;
    private String classOfBusinessName;
    private String classOfBusinessCode;

    private UUID brokerId;
    private String brokerName;

    // ── Agent (V53 — Slice 84d) ──────────────────────────────────────────
    // Mutually exclusive with brokerId via the V53 CHECK constraint.
    private UUID agentId;
    private String agentName;

    // ── Relationship Manager (B2 — Task 5.1) ─────────────────────────────
    // Snapshotted at policy creation. RM is an accrual-only commission source
    // (Dr 5130 / Cr 2520) — payroll-paid, never producing a CreditNote/payment.
    private UUID relationshipManagerId;
    private String relationshipManagerName;

    private BusinessType businessType;
    private boolean niidRequired;

    private LocalDate policyStartDate;
    private LocalDate policyEndDate;

    private BigDecimal totalSumInsured;
    private BigDecimal totalPremium;
    private BigDecimal discount;
    private BigDecimal netPremium;

    // ── Commission snapshot (Slice 84b — V51; surfaced by 84e) ────────────
    // All three are null when no commission is configured at issuance. Pair
    // semantics enforced by V51's ck_policies_commission_pair: source + rate
    // are both-set or both-null. commissionAmount is computed at response
    // time from netPremium × commissionRate / 100 (HALF_UP, 2dp) — same
    // formula PolicyService used to populate PolicyApprovedEvent in Slice 84c.
    private CommissionSourceType commissionSourceType;
    private BigDecimal commissionRate;
    private BigDecimal commissionAmount;

    private String notes;
    private String workflowId;

    private String approvedBy;
    private Instant approvedAt;
    private String rejectedBy;
    private Instant rejectedAt;
    private String rejectionReason;

    private String cancelledBy;
    private Instant cancelledAt;
    private String cancellationReason;

    private String naicomUid;
    private Instant naicomUploadedAt;
    private String naicomCertificatePath;

    private String niidRef;
    private Instant niidUploadedAt;

    private String policyDocumentPath;

    private Instant documentSentAt;
    private String  documentSentBy;
    private Instant documentAcknowledgedAt;
    private String  documentAcknowledgedBy;

    private List<PolicyRiskResponse> risks;
    private List<PolicyCoinsuranceParticipantResponse> coinsuranceParticipants;
    private PolicySurveyResponse survey;

    private Instant createdAt;
    private Instant updatedAt;
}
