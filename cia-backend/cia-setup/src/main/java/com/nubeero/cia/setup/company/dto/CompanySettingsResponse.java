package com.nubeero.cia.setup.company.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CompanySettingsResponse {
    private UUID id;
    private String name;
    private String rcNumber;
    private String naicomLicenseNumber;
    private String address;
    private String city;
    private String state;
    private String email;
    private String phone;
    private String logoPath;
    private String website;
    private Instant createdAt;
    private Instant updatedAt;
}
