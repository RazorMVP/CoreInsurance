package com.nubeero.cia.customer.dto;

import com.nubeero.cia.customer.IdType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CustomerDirectorRequest {

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    private LocalDate dateOfBirth;

    private IdType idType;

    private String idNumber;
}
