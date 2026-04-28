package com.nubeero.cia.quotation.dto;

import com.nubeero.cia.quotation.BusinessType;
import com.nubeero.cia.quotation.QuoteStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class QuoteResponse {
    private UUID id;
    private String quoteNumber;
    private QuoteStatus status;

    private UUID customerId;
    private String customerName;

    private UUID productId;
    private String productName;
    private String productCode;
    private BigDecimal productRate;

    private UUID classOfBusinessId;
    private String classOfBusinessName;

    private UUID brokerId;
    private String brokerName;

    private BusinessType businessType;

    private LocalDate policyStartDate;
    private LocalDate policyEndDate;

    private BigDecimal totalSumInsured;
    private BigDecimal totalGrossPremium;
    private BigDecimal totalNetPremium;

    private List<AdjustmentEntryResponse> quoteLoadings;
    private List<AdjustmentEntryResponse> quoteDiscounts;
    private List<String> selectedClauseIds;

    private String inputterName;
    private String approverName;

    private String notes;
    private String workflowId;

    private String approvedBy;
    private Instant approvedAt;
    private String rejectedBy;
    private Instant rejectedAt;
    private String rejectionReason;
    private Instant expiresAt;

    private List<QuoteRiskResponse> risks;
    private List<QuoteCoinsuranceParticipantResponse> coinsuranceParticipants;

    private Instant createdAt;
    private Instant updatedAt;
}
