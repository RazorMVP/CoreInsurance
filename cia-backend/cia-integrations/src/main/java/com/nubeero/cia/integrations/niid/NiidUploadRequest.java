package com.nubeero.cia.integrations.niid;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NiidUploadRequest {
    private String policyNumber;
    private String vehicleRegNumber;
    private String tenantId;
    private String policyJson;
}
