package com.nubeero.cia.reports.controller.dto;

import com.nubeero.cia.reports.domain.ReportCategory;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AccessPolicyUpdateRequest {

    @NotNull
    private UUID accessGroupId;

    /** Null for report-level policies */
    private ReportCategory category;

    /** Null for category-level policies */
    private UUID reportId;

    private boolean canView;
    private boolean canExportCsv;
    private boolean canExportPdf;
}
