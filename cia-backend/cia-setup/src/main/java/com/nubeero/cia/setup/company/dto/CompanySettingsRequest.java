package com.nubeero.cia.setup.company.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CompanySettingsRequest {

    @NotBlank
    private String name;

    private String rcNumber;
    private String naicomLicenseNumber;
    private String address;
    private String city;
    private String state;

    @Email
    private String email;

    private String phone;
    private String logoPath;
    private String website;
}
