package com.nubeero.cia.integrations.niid;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NiidUploadResult {
    private boolean success;
    private String niidRef;
    private String errorMessage;
}
