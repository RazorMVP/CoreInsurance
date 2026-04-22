package com.nubeero.cia.integrations.naicom;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NaicomUploadResult {
    private boolean success;
    private String naicomUid;
    private String errorMessage;
}
