package com.nubeero.cia.integrations.kyc;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class KycResult {
    private boolean verified;
    private String verificationId;
    private String failureReason;
    private Instant verifiedAt;
}
