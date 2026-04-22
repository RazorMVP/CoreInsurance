package com.nubeero.cia.setup.org.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

@Data
public class RelationshipManagerRequest {

    @NotBlank
    private String name;

    @Email
    private String email;

    private String phone;

    private UUID branchId;
}
