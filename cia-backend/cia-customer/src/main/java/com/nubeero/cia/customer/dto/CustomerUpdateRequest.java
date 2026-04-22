package com.nubeero.cia.customer.dto;

import jakarta.validation.constraints.Email;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CustomerUpdateRequest {
    private String firstName;
    private String lastName;
    private String otherNames;
    private LocalDate dateOfBirth;
    private String gender;
    private String maritalStatus;
    private String companyName;
    private LocalDate incorporationDate;
    private String industry;
    private String contactPerson;

    @Email
    private String email;

    private String phone;
    private String alternatePhone;
    private String address;
    private String city;
    private String state;
    private String country;
}
