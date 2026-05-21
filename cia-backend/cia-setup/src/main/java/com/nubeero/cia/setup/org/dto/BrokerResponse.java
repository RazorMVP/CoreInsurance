package com.nubeero.cia.setup.org.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class BrokerResponse {
    private UUID id;
    private String name;
    private String code;
    private String rcNumber;
    /** NAICOM broker licence number (V49). */
    private String licenseNumber;
    private String address;
    private String email;
    private String phone;
    private Instant createdAt;
    private Instant updatedAt;
}
