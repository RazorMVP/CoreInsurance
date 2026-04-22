package com.nubeero.cia.setup.org.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class InsuranceCompanyResponse {
    private UUID id;
    private String name;
    private String rcNumber;
    private String naicomLicense;
    private String address;
    private String email;
    private String phone;
    private Instant createdAt;
    private Instant updatedAt;
}
