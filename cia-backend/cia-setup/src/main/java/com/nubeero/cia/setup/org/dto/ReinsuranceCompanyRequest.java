package com.nubeero.cia.setup.org.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReinsuranceCompanyRequest {

    @NotBlank
    private String name;

    private String rcNumber;
    private String address;

    @Email
    private String email;

    private String phone;

    @NotBlank
    private String country;
}
