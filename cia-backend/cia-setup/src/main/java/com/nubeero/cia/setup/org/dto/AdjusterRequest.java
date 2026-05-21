package com.nubeero.cia.setup.org.dto;

import com.nubeero.cia.setup.org.AdjusterType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdjusterRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;

    @NotNull
    private AdjusterType type;

    private String licenseNumber;

    @Email
    private String email;

    private String phone;

    private String address;
}
