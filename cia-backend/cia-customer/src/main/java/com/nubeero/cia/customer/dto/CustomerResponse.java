package com.nubeero.cia.customer.dto;

import com.nubeero.cia.customer.CustomerStatus;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.customer.IdType;
import com.nubeero.cia.customer.KycStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class CustomerResponse {
    private UUID id;
    private CustomerType customerType;
    private CustomerStatus customerStatus;
    private KycStatus kycStatus;
    private String kycProviderRef;
    private String kycFailureReason;
    private Instant kycVerifiedAt;

    // Individual
    private String firstName;
    private String lastName;
    private String otherNames;
    private LocalDate dateOfBirth;
    private String gender;
    private String maritalStatus;
    private IdType idType;
    private String idNumber;

    // Corporate
    private String companyName;
    private String rcNumber;
    private LocalDate incorporationDate;
    private String industry;
    private String contactPerson;

    // Common
    private String email;
    private String phone;
    private String alternatePhone;
    private String address;
    private String city;
    private String state;
    private String country;

    private List<CustomerDirectorResponse> directors;
    private List<CustomerDocumentResponse> documents;

    private Instant createdAt;
    private Instant updatedAt;
}
