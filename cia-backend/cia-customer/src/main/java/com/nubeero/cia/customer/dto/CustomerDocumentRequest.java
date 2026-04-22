package com.nubeero.cia.customer.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CustomerDocumentRequest {

    @NotBlank
    private String documentType;

    @NotBlank
    private String documentName;

    @NotBlank
    private String documentPath;

    private String mimeType;

    private Long fileSizeBytes;
}
