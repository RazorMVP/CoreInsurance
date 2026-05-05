package com.nubeero.cia.setup.product.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClaimDocumentRequirementRequest {

    @NotBlank
    private String documentName;

    private boolean mandatory;

    /**
     * Optional ClaimDocumentType enum name (CLAIM_FORM, POLICE_REPORT, etc.).
     * When present the per-claim checklist auto-matches this requirement
     * against uploaded documents of the same type.
     */
    private String documentType;
}
