package com.nubeero.cia.customer.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CustomerDocumentResponse {
    private UUID id;
    private String documentType;
    private String documentName;
    private String documentPath;
    private String mimeType;
    private Long fileSizeBytes;
    private String uploadedBy;
    private Instant createdAt;
}
