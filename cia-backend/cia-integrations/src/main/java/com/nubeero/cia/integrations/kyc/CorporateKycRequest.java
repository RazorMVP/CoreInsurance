package com.nubeero.cia.integrations.kyc;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CorporateKycRequest {
    private String rcNumber;
    private String companyName;
}
