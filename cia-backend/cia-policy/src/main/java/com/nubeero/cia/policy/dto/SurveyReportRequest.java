package com.nubeero.cia.policy.dto;

import lombok.Data;

/**
 * Records the survey report. Either {@code reportPath} (a storage key
 * for a previously-uploaded file) or {@code notes} should be supplied;
 * usually both. The frontend uploads the file via the storage service
 * separately and passes the resulting path here.
 */
@Data
public class SurveyReportRequest {

    private String reportPath;
    private String notes;
}
