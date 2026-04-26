package com.nubeero.cia.customer.dto;

import com.nubeero.cia.customer.IdType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Data
@NoArgsConstructor
public class DirectorUpdateRequest {

    /** Present for existing directors; null for new directors. */
    private UUID id;

    /** True = soft-delete this director. */
    private boolean deleted;

    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private IdType idType;
    private String idNumber;
    private LocalDate idExpiryDate;

    /** Required when any KYC field changes on an existing director. */
    private String kycUpdateReason;
    private String kycUpdateNotes;
}
