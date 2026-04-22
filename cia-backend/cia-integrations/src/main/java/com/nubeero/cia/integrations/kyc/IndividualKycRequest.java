package com.nubeero.cia.integrations.kyc;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class IndividualKycRequest {
    private String idType;
    private String idNumber;
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
}
