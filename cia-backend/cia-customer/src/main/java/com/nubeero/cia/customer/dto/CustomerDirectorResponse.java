package com.nubeero.cia.customer.dto;

import com.nubeero.cia.customer.IdType;
import com.nubeero.cia.customer.KycStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class CustomerDirectorResponse {
    private UUID id;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private IdType idType;
    private String idNumber;
    private String idDocumentUrl;
    private LocalDate idExpiryDate;
    private KycStatus kycStatus;
    private String kycFailureReason;
}
