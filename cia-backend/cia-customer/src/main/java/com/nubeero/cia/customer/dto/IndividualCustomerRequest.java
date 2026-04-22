package com.nubeero.cia.customer.dto;

import com.nubeero.cia.customer.IdType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class IndividualCustomerRequest {

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    private String otherNames;

    @NotNull
    private LocalDate dateOfBirth;

    private String gender;
    private String maritalStatus;

    @NotNull
    private IdType idType;

    @NotBlank
    private String idNumber;

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
}
