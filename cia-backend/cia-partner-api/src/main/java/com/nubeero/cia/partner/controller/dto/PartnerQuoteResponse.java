package com.nubeero.cia.partner.controller.dto;

import com.nubeero.cia.quotation.BusinessType;
import com.nubeero.cia.quotation.QuoteStatus;
import com.nubeero.cia.quotation.dto.QuoteResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Insurance quote returned to Insurtech partners")
public class PartnerQuoteResponse {

    @Schema(description = "Unique quote identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Human-readable quote reference number", example = "QUO-2026-00010")
    private String quoteNumber;

    @Schema(description = "Current status of the quote", example = "PENDING_APPROVAL")
    private QuoteStatus status;

    @Schema(description = "Customer the quote is for", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID customerId;

    @Schema(description = "Customer name", example = "Chukwuemeka Obi")
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

    @Schema(description = "Business type for this quote", example = "DIRECT")
    private BusinessType businessType;

    @Schema(description = "Proposed policy start date", example = "2026-05-01")
    private LocalDate policyStartDate;

    @Schema(description = "Proposed policy end date", example = "2027-04-30")
    private LocalDate policyEndDate;

    @Schema(description = "Total sum insured across all risks (NGN)", example = "5000000.00")
    private BigDecimal totalSumInsured;

    @Schema(description = "Gross premium before discount (NGN)", example = "250000.00")
    private BigDecimal totalPremium;

    @Schema(description = "Discount amount (NGN)", example = "12500.00")
    private BigDecimal discount;

    @Schema(description = "Net premium payable after discount (NGN)", example = "237500.00")
    private BigDecimal netPremium;

    @Schema(description = "Timestamp when the quote approval was confirmed", example = "2026-04-20T14:23:00Z")
    private Instant approvedAt;

    @Schema(description = "Timestamp when this quote expires", example = "2026-05-20T23:59:59Z")
    private Instant expiresAt;

    @Schema(description = "Reason for rejection if status is REJECTED", example = "Risk exceeds underwriting appetite")
    private String rejectionReason;

    @Schema(description = "Record creation timestamp", example = "2026-04-18T09:00:00Z")
    private Instant createdAt;

    @Schema(description = "Record last-updated timestamp", example = "2026-04-20T14:23:00Z")
    private Instant updatedAt;

    public static PartnerQuoteResponse from(QuoteResponse q) {
        return PartnerQuoteResponse.builder()
                .id(q.getId())
                .quoteNumber(q.getQuoteNumber())
                .status(q.getStatus())
                .customerId(q.getCustomerId())
                .customerName(q.getCustomerName())
                .productId(q.getProductId())
                .productName(q.getProductName())
                .productCode(q.getProductCode())
                .classOfBusinessId(q.getClassOfBusinessId())
                .classOfBusinessName(q.getClassOfBusinessName())
                .businessType(q.getBusinessType())
                .policyStartDate(q.getPolicyStartDate())
                .policyEndDate(q.getPolicyEndDate())
                .totalSumInsured(q.getTotalSumInsured())
                .totalPremium(q.getTotalPremium())
                .discount(q.getDiscount())
                .netPremium(q.getNetPremium())
                .approvedAt(q.getApprovedAt())
                .expiresAt(q.getExpiresAt())
                .rejectionReason(q.getRejectionReason())
                .createdAt(q.getCreatedAt())
                .updatedAt(q.getUpdatedAt())
                .build();
    }
}
