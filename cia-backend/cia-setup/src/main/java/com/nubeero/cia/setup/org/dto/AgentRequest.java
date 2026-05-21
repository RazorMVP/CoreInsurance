package com.nubeero.cia.setup.org.dto;

import com.nubeero.cia.setup.org.AgentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Size(max = 20)
    private String code;

    @NotNull
    private AgentType type;

    private String licenseNumber;

    @Email
    private String email;

    private String phone;

    private String address;
}
