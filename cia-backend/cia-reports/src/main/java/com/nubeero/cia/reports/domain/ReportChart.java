package com.nubeero.cia.reports.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    // Explicit @JsonProperty prevents camelCase ambiguity with Lombok getter naming
    @JsonProperty("xAxis")
    private String xAxis;

    @JsonProperty("yAxis")
    private String yAxis;
}
