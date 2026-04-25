package com.nubeero.cia.reports.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportConfig {

    private List<ReportField> fields;
    private List<ReportFilter> filters;
    private String groupBy;
    private String sortBy;
    private String sortDir;
    private ReportChart chart;
}
