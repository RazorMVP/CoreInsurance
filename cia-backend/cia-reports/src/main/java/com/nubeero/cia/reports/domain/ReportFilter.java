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

    /**
     * Optional default value the Builder lets a creator set per-filter (e.g. a
     * year-start date on a Trial Balance "Date From" filter). Persists into the
     * JSONB config; the Viewer's ReportFilterForm uses it as the initial form
     * value so the user can run the report without re-typing.
     *
     * Existing SYSTEM reports (V18, V44) have no defaultValue in their seeded
     * JSON — Jackson deserializes them as null and the picker shows empty.
     */
    private String defaultValue;
}
