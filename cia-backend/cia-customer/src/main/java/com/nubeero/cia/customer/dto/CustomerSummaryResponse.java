package com.nubeero.cia.customer.dto;

import com.nubeero.cia.customer.CustomerStatus;
import com.nubeero.cia.customer.CustomerType;
import com.nubeero.cia.customer.KycStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class CustomerSummaryResponse {
    private UUID id;
    private String customerNumber;
    private CustomerType customerType;
    private CustomerStatus customerStatus;
    private KycStatus kycStatus;
    private String displayName;
    private String email;
    private String phone;
    private Instant createdAt;
}
