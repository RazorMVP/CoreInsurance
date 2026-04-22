package com.nubeero.cia.partner.controller.dto;

import com.nubeero.cia.customer.CustomerStatus;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.customer.KycStatus;
import com.nubeero.cia.customer.dto.CustomerResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Customer record returned to Insurtech partners")
public class PartnerCustomerResponse {

    @Schema(description = "Unique customer identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Customer type: INDIVIDUAL or CORPORATE", example = "INDIVIDUAL")
    private CustomerType customerType;

    @Schema(description = "Customer account status", example = "ACTIVE")
    private CustomerStatus customerStatus;

    @Schema(description = "KYC verification status", example = "VERIFIED")
    private KycStatus kycStatus;

    @Schema(description = "Reason KYC verification failed, if applicable", example = "Name mismatch against NIN database")
    private String kycFailureReason;

    @Schema(description = "Timestamp when KYC was successfully verified", example = "2026-04-18T10:30:00Z")
    private Instant kycVerifiedAt;

    // Individual fields
    @Schema(description = "First name (individual customers)", example = "Chukwuemeka")
    private String firstName;

    @Schema(description = "Last name (individual customers)", example = "Obi")
    private String lastName;

    @Schema(description = "Date of birth (individual customers)", example = "1985-03-15")
    private LocalDate dateOfBirth;

    @Schema(description = "Gender (individual customers)", example = "MALE")
    private String gender;

    // Corporate fields
    @Schema(description = "Registered company name (corporate customers)", example = "Obi Enterprises Limited")
    private String companyName;

    @Schema(description = "CAC Registration Number (corporate customers)", example = "RC-123456")
    private String rcNumber;

    @Schema(description = "Date of CAC incorporation (corporate customers)", example = "2010-06-01")
    private LocalDate incorporationDate;

    // Common fields
    @Schema(description = "Contact email address", example = "chukwuemeka@obienterprises.ng")
    private String email;

    @Schema(description = "Primary phone number", example = "+2348012345678")
    private String phone;

    @Schema(description = "Street address", example = "12 Broad Street")
    private String address;

    @Schema(description = "City", example = "Lagos")
    private String city;

    @Schema(description = "State", example = "Lagos State")
    private String state;

    @Schema(description = "Country", example = "Nigeria")
    private String country;

    @Schema(description = "Record creation timestamp", example = "2026-04-17T08:00:00Z")
    private Instant createdAt;

    @Schema(description = "Record last-updated timestamp", example = "2026-04-18T10:30:00Z")
    private Instant updatedAt;

    public static PartnerCustomerResponse from(CustomerResponse c) {
        return PartnerCustomerResponse.builder()
                .id(c.getId())
                .customerType(c.getCustomerType())
                .customerStatus(c.getCustomerStatus())
                .kycStatus(c.getKycStatus())
                .kycFailureReason(c.getKycFailureReason())
                .kycVerifiedAt(c.getKycVerifiedAt())
                .firstName(c.getFirstName())
                .lastName(c.getLastName())
                .dateOfBirth(c.getDateOfBirth())
                .gender(c.getGender())
                .companyName(c.getCompanyName())
                .rcNumber(c.getRcNumber())
                .incorporationDate(c.getIncorporationDate())
                .email(c.getEmail())
                .phone(c.getPhone())
                .address(c.getAddress())
                .city(c.getCity())
                .state(c.getState())
                .country(c.getCountry())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
