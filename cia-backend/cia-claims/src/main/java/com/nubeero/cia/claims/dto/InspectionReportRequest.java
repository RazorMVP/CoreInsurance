package com.nubeero.cia.claims.dto;

import lombok.Data;

/**
 * Records the inspection report. Either {@code reportPath} (a storage key
 * for a previously-uploaded file) or {@code notes} should be supplied;
 * usually both. The frontend uploads the file via the storage service
 * separately and passes the resulting path here.
 */
@Data
public class InspectionReportRequest {

    private String reportPath;
    private String notes;
}
