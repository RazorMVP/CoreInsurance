package com.nubeero.cia.setup.org.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class InsuranceCompanyRequest {

    @NotBlank
    private String name;

    private String rcNumber;
    private String naicomLicense;
    private String address;

    @Email
    private String email;

    private String phone;
}
