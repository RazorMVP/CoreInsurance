package com.nubeero.cia.reports.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportChart {

    /** BAR | LINE | PIE | TABLE_ONLY */
    private String type;
    private String xAxis;
    private String yAxis;
}
