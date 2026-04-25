package com.nubeero.cia.reports.controller.dto;

import com.nubeero.cia.reports.domain.DataSource;
import com.nubeero.cia.reports.domain.ReportCategory;
import com.nubeero.cia.reports.domain.ReportConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateReportRequest {

    @NotBlank
    private String name;

    private String description;

    @NotNull
    private ReportCategory category;

    @NotNull
    private DataSource dataSource;

    @NotNull
    private ReportConfig config;
}
