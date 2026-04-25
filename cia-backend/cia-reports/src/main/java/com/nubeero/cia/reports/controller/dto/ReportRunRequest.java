package com.nubeero.cia.reports.controller.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
public class ReportRunRequest {

    @NotNull
    private UUID reportId;

    /** Key = filter key (e.g. "date_from"), value = filter value as string */
    private Map<String, String> filters;

    /** JSON | CSV | PDF — defaults to JSON if omitted */
    private String format;
}
