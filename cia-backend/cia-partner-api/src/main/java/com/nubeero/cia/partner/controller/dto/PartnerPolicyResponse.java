package com.nubeero.cia.partner.controller.dto;

import com.nubeero.cia.policy.PolicyStatus;
import com.nubeero.cia.policy.dto.PolicyResponse;
import com.nubeero.cia.quotation.BusinessType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Policy record returned to Insurtech partners")
public class PartnerPolicyResponse {

    @Schema(description = "Unique policy identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Human-readable policy number", example = "POL-2026-00001")
    private String policyNumber;

    @Schema(description = "Current lifecycle status of the policy", example = "ACTIVE")
    private PolicyStatus status;

    @Schema(description = "Source quote ID if bound from a quote", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID quoteId;

    @Schema(description = "Source quote number", example = "QUO-2026-00010")
    private String quoteNumber;

    @Schema(description = "Policyholder customer ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID customerId;

    @Schema(description = "Policyholder name", example = "Chukwuemeka Obi")
    private String customerName;

    @Schema(description = "Insurance product ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID productId;

    @Schema(description = "Insurance product name", example = "Comprehensive Motor")
    private String productName;

    @Schema(description = "Product code", example = "CMV")
    private String productCode;

    @Schema(description = "Class of business ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID classOfBusinessId;

    @Schema(description = "Class of business name", example = "Motor")
    private String classOfBusinessName;

    @Schema(description = "Broker ID if placed through a broker", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID brokerId;

    @Schema(description = "Broker name", example = "Leadway Insurance Brokers")
    private String brokerName;

    @Schema(description = "Policy business type", example = "DIRECT")
    private BusinessType businessType;

    @Schema(description = "Policy commencement date", example = "2026-05-01")
    private LocalDate policyStartDate;

    @Schema(description = "Policy expiry date", example = "2027-04-30")
    private LocalDate policyEndDate;

    @Schema(description = "Total sum insured across all risks (NGN)", example = "5000000.00")
    private BigDecimal totalSumInsured;

    @Schema(description = "Gross premium before discount (NGN)", example = "250000.00")
    private BigDecimal totalPremium;

    @Schema(description = "Discount amount (NGN)", example = "12500.00")
    private BigDecimal discount;

    @Schema(description = "Net premium payable after discount (NGN)", example = "237500.00")
    private BigDecimal netPremium;

    @Schema(description = "Reason for rejection if status is REJECTED", example = "Sum insured exceeds underwriting limit")
    private String rejectionReason;

    @Schema(description = "Reason for cancellation if status is CANCELLED", example = "Customer requested cancellation")
    private String cancellationReason;

    @Schema(description = "NAICOM unique identifier; PENDING until the upload is confirmed", example = "NAICOM-2026-9876543")
    private String naicomUid;

    @Schema(description = "Timestamp of successful NAICOM upload", example = "2026-04-20T15:00:00Z")
    private Instant naicomUploadedAt;

    @Schema(description = "NIID reference number (motor/marine only)", example = "NIID-2026-12345")
    private String niidRef;

    @Schema(description = "Timestamp of approval", example = "2026-04-20T14:23:00Z")
    private Instant approvedAt;

    @Schema(description = "Record creation timestamp", example = "2026-04-18T09:00:00Z")
    private Instant createdAt;

    @Schema(description = "Record last-updated timestamp", example = "2026-04-20T14:23:00Z")
    private Instant updatedAt;

    public static PartnerPolicyResponse from(PolicyResponse p) {
        return PartnerPolicyResponse.builder()
                .id(p.getId())
                .policyNumber(p.getPolicyNumber())
                .status(p.getStatus())
                .quoteId(p.getQuoteId())
                .quoteNumber(p.getQuoteNumber())
                .customerId(p.getCustomerId())
                .customerName(p.getCustomerName())
                .productId(p.getProductId())
                .productName(p.getProductName())
                .productCode(p.getProductCode())
                .classOfBusinessId(p.getClassOfBusinessId())
                .classOfBusinessName(p.getClassOfBusinessName())
                .brokerId(p.getBrokerId())
                .brokerName(p.getBrokerName())
                .businessType(p.getBusinessType())
                .policyStartDate(p.getPolicyStartDate())
                .policyEndDate(p.getPolicyEndDate())
                .totalSumInsured(p.getTotalSumInsured())
                .totalPremium(p.getTotalPremium())
                .discount(p.getDiscount())
                .netPremium(p.getNetPremium())
                .rejectionReason(p.getRejectionReason())
                .cancellationReason(p.getCancellationReason())
                .naicomUid(p.getNaicomUid())
                .naicomUploadedAt(p.getNaicomUploadedAt())
                .niidRef(p.getNiidRef())
                .approvedAt(p.getApprovedAt())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
