package com.nubeero.cia.policy.dto;

import com.nubeero.cia.policy.PolicyStatus;
import com.nubeero.cia.quotation.BusinessType;
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

    private BusinessType businessType;
    private boolean niidRequired;

    private LocalDate policyStartDate;
    private LocalDate policyEndDate;

    private BigDecimal totalSumInsured;
    private BigDecimal totalPremium;
    private BigDecimal discount;
    private BigDecimal netPremium;

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

    private Instant createdAt;
    private Instant updatedAt;
}
