package com.nubeero.cia.partner.controller.dto;

import com.nubeero.cia.setup.product.dto.ClassOfBusinessResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@Schema(description = "Class of business (line of insurance) available under a product")
public class PartnerClassOfBusinessResponse {

    @Schema(description = "Unique class of business identifier", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Class of business name", example = "Motor")
    private String name;

    @Schema(description = "Short code used in references and certificates", example = "MOT")
    private String code;

    @Schema(description = "Description of the class of business", example = "Covers private and commercial motor vehicles")
    private String description;

    @Schema(description = "Record creation timestamp", example = "2025-12-01T00:00:00Z")
    private Instant createdAt;

    public static PartnerClassOfBusinessResponse from(ClassOfBusinessResponse c) {
        return PartnerClassOfBusinessResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .code(c.getCode())
                .description(c.getDescription())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
