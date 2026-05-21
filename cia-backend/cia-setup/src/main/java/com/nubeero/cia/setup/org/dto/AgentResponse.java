package com.nubeero.cia.setup.org.dto;

import com.nubeero.cia.setup.org.AgentType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class AgentResponse {
    private UUID id;
    private String name;
    private String code;
    private AgentType type;
    private String licenseNumber;
    private String email;
    private String phone;
    private String address;
    private Instant createdAt;
    private Instant updatedAt;
}
