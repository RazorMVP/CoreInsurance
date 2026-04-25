package com.nubeero.cia.reports.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportField {

    private String key;
    private String label;
    /** STRING | MONEY | PERCENT | DATE | NUMBER | INTEGER */
    private String type;
    /** True when value is derived from formula, not a raw DB column */
    private boolean computed;
}
