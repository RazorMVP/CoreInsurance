package com.nubeero.cia.reports.controller.dto;

import com.nubeero.cia.reports.domain.*;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class ReportDefinitionDto {

    UUID id;
    String name;
    String description;
    ReportCategory category;
    ReportType type;
    DataSource dataSource;
    ReportConfig config;
    boolean pinnable;
    boolean active;
    Instant createdAt;

    public static ReportDefinitionDto from(ReportDefinition r) {
        return ReportDefinitionDto.builder()
                .id(r.getId())
                .name(r.getName())
                .description(r.getDescription())
                .category(r.getCategory())
                .type(r.getType())
                .dataSource(r.getDataSource())
                .config(r.getConfig())
                .pinnable(r.isPinnable())
                .active(r.isActive())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
