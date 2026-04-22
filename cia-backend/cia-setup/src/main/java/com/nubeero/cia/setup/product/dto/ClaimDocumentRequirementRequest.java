package com.nubeero.cia.setup.product.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClaimDocumentRequirementRequest {

    @NotBlank
    private String documentName;

    private boolean mandatory;
}
