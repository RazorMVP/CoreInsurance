package com.nubeero.cia.integrations.naicom;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NaicomUploadRequest {
    private String policyNumber;
    private String tenantId;
    private String policyJson;
}
