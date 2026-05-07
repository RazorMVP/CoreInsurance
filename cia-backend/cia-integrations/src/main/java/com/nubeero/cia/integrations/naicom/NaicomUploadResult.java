package com.nubeero.cia.integrations.naicom;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NaicomUploadResult {
    private boolean success;
    private String naicomUid;
    private String errorMessage;
}
