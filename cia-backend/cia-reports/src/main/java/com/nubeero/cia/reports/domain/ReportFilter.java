package com.nubeero.cia.reports.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportFilter {

    private String key;
    private String label;
    /** DATE | DATE_RANGE | SELECT | MULTI_SELECT | TEXT | NUMBER */
    private String type;
    private boolean required;
}
