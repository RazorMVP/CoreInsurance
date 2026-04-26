package com.nubeero.cia.customer.dto;

import com.nubeero.cia.customer.IdType;
import jakarta.validation.constraints.Email;
import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class CustomerUpdateRequest {

    // ── Contact fields (both types) ──────────────────────────────────
    @Email
    private String email;
    private String phone;
    private String alternatePhone;
    private String address;
    private String city;
    private String state;
    private String country;

    // ── Individual contact ───────────────────────────────────────────
    private String firstName;
    private String lastName;
    private String otherNames;
    private LocalDate dateOfBirth;
    private String gender;
    private String maritalStatus;

    // ── Corporate contact ────────────────────────────────────────────
    private String companyName;
    private LocalDate incorporationDate;
    private String industry;
    private String contactPerson;

    // ── Channel ─────────────────────────────────────────────────────
    private UUID brokerId;

    // ── KYC fields (individual + corporate directors) ────────────────
    private IdType idType;
    private String idNumber;
    private LocalDate idExpiryDate;
    // idDocumentUrl is set from the uploaded file — not accepted from client

    // ── KYC update reason (required when any KYC field changes) ──────
    private String kycUpdateReason;   // one of the predefined dropdown values
    private String kycUpdateNotes;    // optional free-text
}
