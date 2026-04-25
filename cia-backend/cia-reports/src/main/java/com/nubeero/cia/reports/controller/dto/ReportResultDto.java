package com.nubeero.cia.reports.controller.dto;

import com.nubeero.cia.reports.domain.ReportField;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class ReportResultDto {

    List<ReportField> columns;
    List<Map<String, Object>> rows;
    long totalRows;
}
