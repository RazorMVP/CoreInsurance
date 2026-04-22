package com.nubeero.cia.partner.controller.dto;

import com.nubeero.cia.claims.Claim;
import com.nubeero.cia.claims.ClaimStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Claim details returned to Insurtech partners")
public class PartnerClaimResponse {

    @Schema(description = "Unique claim identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Human-readable claim reference", example = "CLM-2026-00042")
    private String claimNumber;

    @Schema(description = "Current claim lifecycle status", example = "REGISTERED")
    private ClaimStatus status;

    @Schema(description = "Policy the claim is raised against", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID policyId;

    @Schema(description = "Policy number", example = "POL-2026-00001")
    private String policyNumber;

    @Schema(description = "Policyholder customer ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID customerId;

    @Schema(description = "Policyholder name", example = "Chukwuemeka Obi")
    private String customerName;

    @Schema(description = "Product ID associated with the claim", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID productId;

    @Schema(description = "Product name", example = "Comprehensive Motor")
    private String productName;

    @Schema(description = "Class of business ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID classOfBusinessId;

    @Schema(description = "Class of business name", example = "Motor")
    private String classOfBusinessName;

    @Schema(description = "Date the incident occurred", example = "2026-04-01")
    private LocalDate incidentDate;

    @Schema(description = "Date the claim was reported", example = "2026-04-03")
    private LocalDate reportedDate;

    @Schema(description = "Location of the loss event", example = "Lagos-Ibadan Expressway, Km 45")
    private String lossLocation;

    @Schema(description = "Description of the incident", example = "Collision with another vehicle at Lagos-Ibadan expressway")
    private String description;

    @Schema(description = "Claimant's estimated loss amount (NGN)", example = "800000.00")
    private BigDecimal estimatedLoss;

    @Schema(description = "Claim reserve set by the insurer (NGN)", example = "750000.00")
    private BigDecimal reserveAmount;

    @Schema(description = "Amount approved for settlement (NGN); null if not yet approved", example = "700000.00")
    private BigDecimal approvedAmount;

    @Schema(description = "Currency code (ISO 4217)", example = "NGN")
    private String currencyCode;

    @Schema(description = "Reason for rejection if status is REJECTED", example = "Incident occurred outside policy coverage period")
    private String rejectionReason;

    @Schema(description = "Timestamp when claim was approved", example = "2026-04-10T14:23:00Z")
    private Instant approvedAt;

    @Schema(description = "Timestamp when claim was settled (payment executed)", example = "2026-04-15T09:00:00Z")
    private Instant settledAt;

    @Schema(description = "Timestamp when the claim record was created", example = "2026-04-03T08:15:00Z")
    private Instant createdAt;

    @Schema(description = "Timestamp of the last update", example = "2026-04-10T14:23:00Z")
    private Instant updatedAt;

    public static PartnerClaimResponse from(Claim c) {
        return PartnerClaimResponse.builder()
                .id(c.getId())
                .claimNumber(c.getClaimNumber())
                .status(c.getStatus())
                .policyId(c.getPolicyId())
                .policyNumber(c.getPolicyNumber())
                .customerId(c.getCustomerId())
                .customerName(c.getCustomerName())
                .productId(c.getProductId())
                .productName(c.getProductName())
                .classOfBusinessId(c.getClassOfBusinessId())
                .classOfBusinessName(c.getClassOfBusinessName())
                .incidentDate(c.getIncidentDate())
                .reportedDate(c.getReportedDate())
                .lossLocation(c.getLossLocation())
                .description(c.getDescription())
                .estimatedLoss(c.getEstimatedLoss())
                .reserveAmount(c.getReserveAmount())
                .approvedAmount(c.getApprovedAmount())
                .currencyCode(c.getCurrencyCode())
                .rejectionReason(c.getRejectionReason())
                .approvedAt(c.getApprovedAt())
                .settledAt(c.getSettledAt())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
