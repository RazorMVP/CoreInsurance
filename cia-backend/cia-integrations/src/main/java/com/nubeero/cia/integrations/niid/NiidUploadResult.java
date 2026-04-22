package com.nubeero.cia.integrations.niid;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NiidUploadResult {
    private boolean success;
    private String niidRef;
    private String errorMessage;
}
