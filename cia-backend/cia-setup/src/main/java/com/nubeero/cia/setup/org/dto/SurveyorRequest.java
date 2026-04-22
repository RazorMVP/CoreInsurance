package com.nubeero.cia.setup.org.dto;

import com.nubeero.cia.setup.org.SurveyorType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SurveyorRequest {

    @NotBlank
    private String name;

    @NotNull
    private SurveyorType type;

    private String licenseNumber;

    @Email
    private String email;

    private String phone;
}
