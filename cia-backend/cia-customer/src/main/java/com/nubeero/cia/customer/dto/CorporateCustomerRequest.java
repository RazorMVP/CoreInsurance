package com.nubeero.cia.customer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class CorporateCustomerRequest {

    @NotBlank
    private String companyName;

    @NotBlank
    private String rcNumber;

    /** CAC certificate issue date — required. */
    private LocalDate cacIssuedDate;

    private LocalDate incorporationDate;
    private String industry;

    @NotBlank
    private String contactPerson;

    @Email
    private String email;

    @NotBlank
    private String phone;

    private String alternatePhone;
    private String address;
    private String city;
    private String state;

    @NotBlank
    private String country;

    @NotEmpty
    @Valid
    private List<CustomerDirectorRequest> directors;
}
